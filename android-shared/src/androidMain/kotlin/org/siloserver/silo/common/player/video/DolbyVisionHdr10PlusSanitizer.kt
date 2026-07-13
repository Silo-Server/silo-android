package org.siloserver.silo.common.player.video

import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Removes HDR10+ registered-user-data SEI NAL units from an unencrypted,
 * Annex-B Dolby Vision Profile 8 sample before it reaches a native DV decoder.
 *
 * Some Fire TV Dolby Vision decoders fail when the same sample carries both a
 * Dolby Vision RPU and HDR10+ dynamic metadata. The guard intentionally does
 * nothing for length-prefixed or malformed samples and preserves every SEI
 * payload that does not carry the standardized HDR10+ T.35 signature.
 */
internal object DolbyVisionHdr10PlusSanitizer {
    fun sanitize(data: ByteBuffer): Boolean {
        if (!data.hasRemaining()) return false
        when (stripStandaloneHdr10PlusSeiInPlace(data)) {
            FastPathResult.CHANGED -> return true
            FastPathResult.UNCHANGED -> return false
            FastPathResult.NEEDS_GENERAL_PATH -> Unit
        }
        val start = data.position()
        val source = ByteArray(data.remaining())
        data.duplicate().get(source)
        val sanitized = sanitizeAnnexB(source) ?: return false
        data.limit(data.capacity())
        data.position(start)
        data.put(sanitized)
        data.limit(start + sanitized.size)
        data.position(start)
        return true
    }

    /** Allocation-free path for the normal case where HDR10+ owns its SEI NAL. */
    private fun stripStandaloneHdr10PlusSeiInPlace(data: ByteBuffer): FastPathResult {
        val starts = findNalStarts(data)
        if (starts.isEmpty() || starts.first().offset != data.position()) return FastPathResult.UNCHANGED
        val removals = mutableListOf<IntRange>()
        for (index in starts.indices) {
            val nalStart = starts[index].offset + starts[index].length
            val nalEnd = starts.getOrNull(index + 1)?.offset ?: data.limit()
            if (nalStart + HEVC_NAL_HEADER_SIZE > nalEnd) continue
            val nalType = (data.get(nalStart).toInt() ushr 1) and 0x3f
            if (nalType !in SEI_NAL_TYPES) continue
            when (classifySeiNal(data, nalStart + HEVC_NAL_HEADER_SIZE, nalEnd)) {
                SeiClassification.HDR10_PLUS_ONLY -> removals += starts[index].offset until nalEnd
                SeiClassification.NEEDS_GENERAL_PATH -> return FastPathResult.NEEDS_GENERAL_PATH
                SeiClassification.UNRELATED -> Unit
            }
        }
        if (removals.isEmpty()) return FastPathResult.UNCHANGED

        val originalPosition = data.position()
        var readPosition = originalPosition
        var writePosition = originalPosition
        for (range in removals) {
            for (index in readPosition until range.first) {
                data.put(writePosition++, data.get(index))
            }
            readPosition = range.last + 1
        }
        for (index in readPosition until data.limit()) {
            data.put(writePosition++, data.get(index))
        }
        data.limit(writePosition)
        data.position(originalPosition)
        return FastPathResult.CHANGED
    }

    private fun classifySeiNal(data: ByteBuffer, from: Int, to: Int): SeiClassification {
        var cursor = from
        val type = readExtendedValue(data, to, cursor) ?: return SeiClassification.UNRELATED
        cursor = type.next
        val size = readExtendedValue(data, to, cursor) ?: return SeiClassification.UNRELATED
        cursor = size.next
        val signatureMatches = type.value == USER_DATA_REGISTERED_ITU_T_T35 &&
            size.value >= HDR10_PLUS_SIGNATURE.size + 1 &&
            cursor + HDR10_PLUS_SIGNATURE.size < to &&
            HDR10_PLUS_SIGNATURE.indices.all { offset ->
                data.get(cursor + offset) == HDR10_PLUS_SIGNATURE[offset]
            } && (data.get(cursor + HDR10_PLUS_SIGNATURE.size).toInt() and 0xff) in 0..1
        if (!signatureMatches) {
            return if (containsHdr10PlusSignature(data, from, to)) {
                SeiClassification.NEEDS_GENERAL_PATH
            } else SeiClassification.UNRELATED
        }

        cursor = advanceRbspBytes(data, cursor, size.value, to)
            ?: return SeiClassification.NEEDS_GENERAL_PATH
        if (cursor >= to || (data.get(cursor).toInt() and 0xff) != 0x80) {
            return SeiClassification.NEEDS_GENERAL_PATH
        }
        cursor++
        while (cursor < to && data.get(cursor) == 0.toByte()) cursor++
        return if (cursor == to) SeiClassification.HDR10_PLUS_ONLY else SeiClassification.NEEDS_GENERAL_PATH
    }

    private fun advanceRbspBytes(data: ByteBuffer, start: Int, count: Int, limit: Int): Int? {
        var cursor = start
        var consumed = 0
        var zeroRun = 0
        while (cursor < limit && consumed < count) {
            val value = data.get(cursor).toInt() and 0xff
            val next = if (cursor + 1 < limit) data.get(cursor + 1).toInt() and 0xff else null
            if (zeroRun >= 2 && value == 0x03 && next != null && next <= 0x03) {
                zeroRun = 0
                cursor++
                continue
            }
            consumed++
            zeroRun = if (value == 0) zeroRun + 1 else 0
            cursor++
        }
        return cursor.takeIf { consumed == count }
    }

    private fun containsHdr10PlusSignature(data: ByteBuffer, from: Int, to: Int): Boolean {
        val needed = HDR10_PLUS_SIGNATURE.size + 1
        for (index in from..(to - needed)) {
            if (HDR10_PLUS_SIGNATURE.indices.all { offset ->
                    data.get(index + offset) == HDR10_PLUS_SIGNATURE[offset]
                } && (data.get(index + HDR10_PLUS_SIGNATURE.size).toInt() and 0xff) in 0..1
            ) return true
        }
        return false
    }

    /** Returns null when the sample is not confirmed Annex-B or is unchanged. */
    fun sanitizeAnnexB(source: ByteArray): ByteArray? {
        val starts = findNalStarts(source)
        if (starts.isEmpty() || starts.first().offset != 0) return null

        val output = ByteArrayOutputStream(source.size)
        var changed = false
        for (index in starts.indices) {
            val nalStart = starts[index].offset + starts[index].length
            val nalEnd = starts.getOrNull(index + 1)?.offset ?: source.size
            if (nalStart + HEVC_NAL_HEADER_SIZE > nalEnd) {
                output.write(source, starts[index].offset, nalEnd - starts[index].offset)
                continue
            }
            val nalType = (source[nalStart].toInt() ushr 1) and 0x3f
            val sanitizedRbsp = if (nalType in SEI_NAL_TYPES) {
                sanitizeSeiRbsp(source, nalStart + HEVC_NAL_HEADER_SIZE, nalEnd)
            } else null
            if (sanitizedRbsp == null) {
                output.write(source, starts[index].offset, nalEnd - starts[index].offset)
                continue
            }
            changed = true
            if (sanitizedRbsp.isEmpty()) continue
            output.write(source, starts[index].offset, starts[index].length + HEVC_NAL_HEADER_SIZE)
            val escaped = escapeRbsp(sanitizedRbsp)
            output.write(escaped, 0, escaped.size)
        }
        return if (changed) output.toByteArray() else null
    }

    private fun findNalStarts(source: ByteArray): List<NalStart> {
        val starts = mutableListOf<NalStart>()
        var index = 0
        while (index + 2 < source.size) {
            if (source[index] == 0.toByte() && source[index + 1] == 0.toByte()) {
                when {
                    source[index + 2] == 1.toByte() -> {
                        starts += NalStart(index, 3)
                        index += 3
                        continue
                    }
                    index + 3 < source.size &&
                        source[index + 2] == 0.toByte() &&
                        source[index + 3] == 1.toByte() -> {
                        starts += NalStart(index, 4)
                        index += 4
                        continue
                    }
                }
            }
            index++
        }
        return starts
    }

    private fun findNalStarts(source: ByteBuffer): List<NalStart> {
        val starts = mutableListOf<NalStart>()
        var index = source.position()
        while (index + 2 < source.limit()) {
            if (source.get(index) == 0.toByte() && source.get(index + 1) == 0.toByte()) {
                when {
                    source.get(index + 2) == 1.toByte() -> {
                        starts += NalStart(index, 3)
                        index += 3
                        continue
                    }
                    index + 3 < source.limit() && source.get(index + 2) == 0.toByte() &&
                        source.get(index + 3) == 1.toByte() -> {
                        starts += NalStart(index, 4)
                        index += 4
                        continue
                    }
                }
            }
            index++
        }
        return starts
    }

    /** Null means unchanged; an empty array means the SEI NAL has no messages left. */
    private fun sanitizeSeiRbsp(source: ByteArray, from: Int, to: Int): ByteArray? {
        val rbsp = ByteArray((to - from).coerceAtLeast(0))
        var rbspSize = 0
        var zeroRun = 0
        for (index in from until to) {
            val value = source[index].toInt() and 0xff
            val next = source.getOrNull(index + 1)?.toInt()?.and(0xff)
            if (zeroRun >= 2 && value == 0x03 && next != null && next <= 0x03) {
                zeroRun = 0
                continue
            }
            rbsp[rbspSize++] = source[index]
            zeroRun = if (value == 0) zeroRun + 1 else 0
        }

        var cursor = 0
        var removed = false
        var keptMessage = false
        val filtered = ByteArrayOutputStream(rbspSize)
        while (cursor < rbspSize) {
            if ((rbsp[cursor].toInt() and 0xff) == 0x80) {
                filtered.write(rbsp, cursor, rbspSize - cursor)
                break
            }
            val messageStart = cursor
            val type = readExtendedValue(rbsp, rbspSize, cursor) ?: return null
            cursor = type.next
            val size = readExtendedValue(rbsp, rbspSize, cursor) ?: return null
            cursor = size.next
            if (size.value < 0 || cursor + size.value > rbspSize) return null
            val isHdr10Plus = type.value == USER_DATA_REGISTERED_ITU_T_T35 &&
                size.value >= HDR10_PLUS_SIGNATURE.size + 1 &&
                HDR10_PLUS_SIGNATURE.indices.all { offset ->
                    rbsp[cursor + offset] == HDR10_PLUS_SIGNATURE[offset]
                } && (rbsp[cursor + HDR10_PLUS_SIGNATURE.size].toInt() and 0xff) in 0..1
            val messageEnd = cursor + size.value
            if (isHdr10Plus) {
                removed = true
            } else {
                filtered.write(rbsp, messageStart, messageEnd - messageStart)
                keptMessage = true
            }
            cursor = messageEnd
        }
        if (!removed) return null
        return if (keptMessage) filtered.toByteArray() else byteArrayOf()
    }

    private fun escapeRbsp(rbsp: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(rbsp.size)
        var zeroRun = 0
        for (value in rbsp) {
            val unsigned = value.toInt() and 0xff
            if (zeroRun >= 2 && unsigned <= 0x03) {
                output.write(0x03)
                zeroRun = 0
            }
            output.write(unsigned)
            zeroRun = if (unsigned == 0) zeroRun + 1 else 0
        }
        return output.toByteArray()
    }

    private fun readExtendedValue(data: ByteArray, limit: Int, start: Int): ExtendedValue? {
        var cursor = start
        var value = 0
        while (cursor < limit && (data[cursor].toInt() and 0xff) == 0xff) {
            value += 0xff
            cursor++
        }
        if (cursor >= limit) return null
        value += data[cursor].toInt() and 0xff
        return ExtendedValue(value, cursor + 1)
    }

    private fun readExtendedValue(data: ByteBuffer, limit: Int, start: Int): ExtendedValue? {
        var cursor = start
        var value = 0
        while (cursor < limit && (data.get(cursor).toInt() and 0xff) == 0xff) {
            value += 0xff
            cursor++
        }
        if (cursor >= limit) return null
        value += data.get(cursor).toInt() and 0xff
        return ExtendedValue(value, cursor + 1)
    }

    private data class NalStart(val offset: Int, val length: Int)
    private data class ExtendedValue(val value: Int, val next: Int)
    private enum class FastPathResult { CHANGED, UNCHANGED, NEEDS_GENERAL_PATH }
    private enum class SeiClassification { HDR10_PLUS_ONLY, UNRELATED, NEEDS_GENERAL_PATH }

    private const val HEVC_NAL_HEADER_SIZE = 2
    private const val USER_DATA_REGISTERED_ITU_T_T35 = 4
    private val SEI_NAL_TYPES = setOf(39, 40)
    private val HDR10_PLUS_SIGNATURE = byteArrayOf(
        0xB5.toByte(), // ITU-T country code: United States
        0x00,
        0x3C, // terminal provider code
        0x00,
        0x01, // provider-oriented code
        0x04, // ST 2094-40 application identifier
    )
}

object PlaybackRuntimeCorrectionMetrics {
    private val sanitizedSamples = AtomicInteger(0)

    fun recordDolbyVisionHdr10PlusSample() {
        sanitizedSamples.incrementAndGet()
    }

    fun consumeDolbyVisionHdr10PlusSamples(): Int = sanitizedSamples.getAndSet(0)

    fun reset() {
        sanitizedSamples.set(0)
    }
}
