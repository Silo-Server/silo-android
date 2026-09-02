package org.siloserver.silo.common.player

import android.util.Log
import androidx.annotation.Keep
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

internal const val DV7_TRANSFORM_MAX_PENDING_BYTES = 32 * 1024 * 1024
private val KEEP_ORIGINAL_NAL = ByteArray(0)

internal enum class DolbyVisionTransformMode {
    DISABLED,
    PROFILE7_TO_PROFILE81,
    PROFILE7_TO_HDR10,
}

internal data class SiloMediaTransformTag(
    val dolbyVisionMode: DolbyVisionTransformMode,
    val expectedDynamicRange: String? = null,
    val expectedColorRange: String? = null,
    /**
     * The plan authorised the Profile 8 base-layer fallback: the extractor
     * repairs colour info from the base layer's compatibility id rather than
     * assuming PQ, and [expectedDynamicRange] names the promised base range.
     */
    val dolbyVisionBaseLayerRoute: Boolean = false,
)

internal class DolbyVisionTransformException(
    val classification: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun interface DolbyVisionRpuConverter {
    fun convertToProfile81(nal: ByteArray): ByteArray
}

/** JNI bridge to the pinned libdovi build packaged in silo-dovi-bridge. */
@Keep
internal object NativeDolbyVisionRpuConverter : DolbyVisionRpuConverter {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("silo_dovi")
        nativeIsReady()
    }.onFailure { Log.w("SiloDovi", "libdovi bridge unavailable", it) }
        .getOrDefault(false)

    val isAvailable: Boolean get() = loaded
    val version: String get() = if (loaded) nativeVersion() else "unavailable"

    override fun convertToProfile81(nal: ByteArray): ByteArray {
        if (!loaded) {
            throw DolbyVisionTransformException(
                "dv7_transform_unavailable",
                "The libdovi conversion bridge is unavailable.",
            )
        }
        return nativeConvertToProfile81(nal)
            ?: throw DolbyVisionTransformException(
                "dv7_rpu_conversion_failed",
                "libdovi could not convert a Profile 7 RPU.",
            )
    }

    @JvmStatic private external fun nativeConvertToProfile81(nal: ByteArray): ByteArray?
    @JvmStatic private external fun nativeIsReady(): Boolean
    @JvmStatic private external fun nativeVersion(): String
}

/**
 * Strict Annex-B access-unit transformer. Media3's bundled MKV/MP4 extractors
 * emit Annex-B at TrackOutput. Unknown framing is rejected instead of sending
 * unmodified Profile 7 bytes under Profile 8/HDR10 signaling.
 */
internal class DolbyVisionAnnexBTransformer(
    private val mode: DolbyVisionTransformMode,
    private val converter: DolbyVisionRpuConverter,
    private val maxOutputBytes: Int = DV7_TRANSFORM_MAX_PENDING_BYTES + 4096,
) {
    var convertedRpuCount: Long = 0
        private set
    var droppedEnhancementNalCount: Long = 0
        private set
    var sampleCount: Long = 0
        private set
    private var outputBuffer = ByteArray(256 * 1024)

    internal data class TransformedSample(val data: ByteArray, val length: Int)

    fun transform(input: ByteArray, length: Int = input.size): ByteArray {
        val transformed = transformSample(input, length)
        return transformed.data.copyOf(transformed.length)
    }

    fun transformSample(input: ByteArray, length: Int = input.size): TransformedSample {
        if (length < 5 || length > input.size) malformed("Invalid access-unit length $length")
        var start = findStartCode(input, 0, length)
            ?: malformed("Profile 7 access unit is not Annex-B framed")
        if (start.offset != 0) {
            malformed("Profile 7 access unit is not Annex-B framed")
        }
        var outputSize = 0
        var keptNalCount = 0
        while (true) {
            val nalStart = start.offset + start.length
            val next = findStartCode(input, nalStart + 2, length)
            val nalEnd = next?.offset ?: length
            if (nalEnd - nalStart < 2) malformed("Empty HEVC NAL unit")
            val byte0 = input[nalStart].toInt() and 0xff
            val byte1 = input[nalStart + 1].toInt() and 0xff
            val nalType = (byte0 ushr 1) and 0x3f
            val layerId = ((byte0 and 1) shl 5) or ((byte1 ushr 3) and 0x1f)

            val replacement: ByteArray? = when {
                layerId > 0 || nalType == 63 -> {
                    droppedEnhancementNalCount++
                    null
                }
                nalType == 62 && mode == DolbyVisionTransformMode.PROFILE7_TO_PROFILE81 -> {
                    val nal = input.copyOfRange(nalStart, nalEnd)
                    try {
                        converter.convertToProfile81(nal)
                    } catch (error: DolbyVisionTransformException) {
                        throw error
                    } catch (error: Throwable) {
                        throw DolbyVisionTransformException(
                            "dv7_rpu_conversion_failed",
                            "libdovi could not convert a Profile 7 RPU.",
                            error,
                        )
                    }.also {
                        if (it.size < 2) malformed("libdovi returned an empty RPU")
                        convertedRpuCount++
                    }
                }
                nalType == 62 -> null
                else -> KEEP_ORIGINAL_NAL
            }
            if (replacement != null) {
                val payloadSize = if (replacement.isEmpty()) nalEnd - nalStart else replacement.size
                ensureOutputCapacity(outputSize + start.length + payloadSize)
                repeat(start.length - 1) { outputBuffer[outputSize++] = 0 }
                outputBuffer[outputSize++] = 1
                if (replacement.isEmpty()) {
                    input.copyInto(outputBuffer, outputSize, nalStart, nalEnd)
                } else {
                    replacement.copyInto(outputBuffer, outputSize)
                }
                outputSize += payloadSize
                keptNalCount++
            }
            start = next ?: break
        }
        if (keptNalCount == 0 || outputSize == 0) malformed("Profile 7 sample has no base-layer NAL units")
        sampleCount++
        return TransformedSample(outputBuffer, outputSize)
    }

    private data class StartCode(val offset: Int, val length: Int)

    private fun findStartCode(data: ByteArray, fromIndex: Int, length: Int): StartCode? {
        var i = fromIndex
        while (i + 2 < length) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (i + 3 < length && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    return StartCode(i, 4)
                }
                if (data[i + 2] == 1.toByte()) {
                    return StartCode(i, 3)
                }
            }
            i++
        }
        return null
    }

    private fun ensureOutputCapacity(required: Int) {
        if (required > maxOutputBytes) {
            throw DolbyVisionTransformException(
                "dv7_transform_oversized",
                "Transformed access unit exceeds the bounded output buffer.",
            )
        }
        if (required <= outputBuffer.size) return
        var next = outputBuffer.size
        while (next < required) next = minOf(maxOutputBytes, next * 2)
        outputBuffer = outputBuffer.copyOf(next)
    }

    private fun malformed(message: String): Nothing =
        throw DolbyVisionTransformException("dv7_transform_malformed", message)
}

@UnstableApi
internal class DolbyVisionTransformingTrackOutput(
    private val delegate: TrackOutput,
    private val mode: DolbyVisionTransformMode,
    converter: DolbyVisionRpuConverter = NativeDolbyVisionRpuConverter,
    private val maxPendingBytes: Int = DV7_TRANSFORM_MAX_PENDING_BYTES,
) : TrackOutput {
    private enum class State { AWAITING_FORMAT, PASSTHROUGH, ACTIVE, FAILED }

    private var state = State.AWAITING_FORMAT
    private var pendingMain = ByteArray(256 * 1024)
    private var pendingMainSize = 0
    private var pendingSupplemental = ByteArray(4 * 1024)
    private var pendingSupplementalSize = 0
    private val readScratch = ByteArray(64 * 1024)
    private val transformer = DolbyVisionAnnexBTransformer(mode, converter)

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun format(format: Format) {
        if (state == State.FAILED) fail("dv7_transform_failed", "Format received after transform failure")
        if (pendingMainSize != 0 || pendingSupplementalSize != 0) {
            fail("dv7_transform_malformed", "Format changed with a partial sample buffered")
        }
        val profile = dolbyVisionProfile(format.codecs)
        if (mode == DolbyVisionTransformMode.DISABLED) {
            state = State.PASSTHROUGH
            delegate.format(format.withDolbyVisionHdrColorInfo())
            return
        }
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION || profile != 7) {
            fail(
                "dv7_transform_source_mismatch",
                "Server requested Profile 7 conversion for ${format.sampleMimeType}/${format.codecs}",
            )
        }
        state = State.ACTIVE
        val colorInfo = hdr10ColorInfo(format.colorInfo)
        val filteredInitializationData = format.initializationData.mapNotNull(::filterHevcInitializationData)
        val rewritten = when (mode) {
            DolbyVisionTransformMode.PROFILE7_TO_PROFILE81 -> format.buildUpon()
                .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
                .setCodecs("dvhe.08.${dolbyVisionLevel(format.codecs).toString().padStart(2, '0')}")
                .setColorInfo(colorInfo)
                .setInitializationData(
                    filteredInitializationData + buildDolbyVisionProfile81Config(dolbyVisionLevel(format.codecs)),
                )
                .build()
            DolbyVisionTransformMode.PROFILE7_TO_HDR10 -> format.buildUpon()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(null)
                .setColorInfo(colorInfo)
                .setInitializationData(filteredInitializationData)
                .build()
            DolbyVisionTransformMode.DISABLED -> format
        }
        delegate.format(rewritten)
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (state == State.PASSTHROUGH) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        requireActiveState()
        requireSupportedPart(sampleDataPart)
        val requested = minOf(length, readScratch.size)
        val read = input.read(readScratch, 0, requested)
        if (read == -1 && !allowEndOfInput) throw EOFException("Unexpected EOF in Profile 7 sample")
        if (read > 0) append(readScratch, read, sampleDataPart)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (state == State.PASSTHROUGH) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }
        requireActiveState()
        requireSupportedPart(sampleDataPart)
        when (sampleDataPart) {
            TrackOutput.SAMPLE_DATA_PART_MAIN -> {
                ensureMainCapacity(length)
                data.readBytes(pendingMain, pendingMainSize, length)
                pendingMainSize += length
            }
            TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL -> {
                ensureSupplementalCapacity(length)
                data.readBytes(pendingSupplemental, pendingSupplementalSize, length)
                pendingSupplementalSize += length
            }
        }
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (state == State.PASSTHROUGH) {
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        requireActiveState()
        if (cryptoData != null || flags and C.BUFFER_FLAG_ENCRYPTED != 0) {
            fail("dv7_transform_encrypted", "Encrypted Profile 7 samples cannot be transformed")
        }
        if (size < 0 || offset < 0 || pendingMainSize + pendingSupplementalSize != size + offset) {
            fail(
                "dv7_transform_malformed",
                "Invalid sample boundary size=$size offset=$offset " +
                    "main=$pendingMainSize supplemental=$pendingSupplementalSize",
            )
        }
        val supplementalSize: Int
        val mainSize: Int
        if (pendingSupplementalSize > 0 || flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA != 0) {
            if (pendingSupplementalSize < 4) {
                fail("dv7_transform_malformed", "Supplemental sample is missing its main-data size prefix")
            }
            mainSize = readBigEndianInt(pendingSupplemental)
            supplementalSize = size - mainSize
            val remainingMain = pendingMainSize - mainSize
            val remainingSupplemental = pendingSupplementalSize - supplementalSize
            if (mainSize <= 0 || supplementalSize < 4 ||
                remainingMain < 0 || remainingSupplemental < 0 ||
                remainingMain + remainingSupplemental != offset
            ) {
                fail(
                    "dv7_transform_malformed",
                    "Invalid supplemental sample boundary size=$size offset=$offset " +
                        "declaredMain=$mainSize main=$pendingMainSize supplemental=$pendingSupplementalSize",
                )
            }
            if (supplementalSize > 4) {
                fail(
                    "dv7_transform_supplemental_unsupported",
                    "Profile 7 sample contains unsupported BlockAdditional payload data",
                )
            }
        } else {
            supplementalSize = 0
            mainSize = size
            if (pendingMainSize - mainSize != offset) {
                fail(
                    "dv7_transform_malformed",
                    "Invalid main sample boundary size=$size offset=$offset pending=$pendingMainSize",
                )
            }
        }
        val transformed = try {
            transformer.transformSample(pendingMain, mainSize)
        } catch (error: DolbyVisionTransformException) {
            state = State.FAILED
            throw error
        }
        val remainingMain = pendingMainSize - mainSize
        val remainingSupplemental = pendingSupplementalSize - supplementalSize
        if (remainingMain > 0) {
            pendingMain.copyInto(pendingMain, 0, mainSize, pendingMainSize)
        }
        if (remainingSupplemental > 0) {
            pendingSupplemental.copyInto(
                pendingSupplemental,
                0,
                supplementalSize,
                pendingSupplementalSize,
            )
        }
        pendingMainSize = remainingMain
        pendingSupplementalSize = remainingSupplemental
        if (supplementalSize > 0) {
            val rewrittenPrefix = ByteArray(4)
            writeBigEndianInt(rewrittenPrefix, transformed.length)
            delegate.sampleData(
                ParsableByteArray(rewrittenPrefix),
                rewrittenPrefix.size,
                TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL,
            )
        }
        delegate.sampleData(
            ParsableByteArray(transformed.data, transformed.length),
            transformed.length,
            TrackOutput.SAMPLE_DATA_PART_MAIN,
        )
        delegate.sampleMetadata(timeUs, flags, transformed.length + supplementalSize, 0, null)
    }

    fun reset() {
        pendingMainSize = 0
        pendingSupplementalSize = 0
    }

    private fun requireActiveState() {
        if (state != State.ACTIVE) {
            fail(
                "dv7_transform_failed",
                "Profile 7 sample data arrived while the transformer was $state",
            )
        }
    }

    private fun append(bytes: ByteArray, length: Int, sampleDataPart: Int) {
        when (sampleDataPart) {
            TrackOutput.SAMPLE_DATA_PART_MAIN -> {
                ensureMainCapacity(length)
                bytes.copyInto(pendingMain, pendingMainSize, 0, length)
                pendingMainSize += length
            }
            TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL -> {
                ensureSupplementalCapacity(length)
                bytes.copyInto(pendingSupplemental, pendingSupplementalSize, 0, length)
                pendingSupplementalSize += length
            }
        }
    }

    private fun ensureMainCapacity(additional: Int) {
        val required = pendingMainSize.toLong() + additional.toLong()
        ensureTotalCapacity(additional)
        if (required <= pendingMain.size) return
        var next = pendingMain.size
        while (next < required) next = minOf(maxPendingBytes, next * 2)
        pendingMain = pendingMain.copyOf(next)
    }

    private fun ensureSupplementalCapacity(additional: Int) {
        val required = pendingSupplementalSize.toLong() + additional.toLong()
        ensureTotalCapacity(additional)
        if (required <= pendingSupplemental.size) return
        var next = pendingSupplemental.size
        while (next < required) next = minOf(maxPendingBytes, next * 2)
        pendingSupplemental = pendingSupplemental.copyOf(next)
    }

    private fun ensureTotalCapacity(additional: Int) {
        val required = pendingMainSize.toLong() + pendingSupplementalSize.toLong() + additional.toLong()
        if (required > maxPendingBytes) {
            fail("dv7_transform_oversized", "Profile 7 sample exceeded $maxPendingBytes bytes")
        }
    }

    private fun requireSupportedPart(sampleDataPart: Int) {
        when (sampleDataPart) {
            TrackOutput.SAMPLE_DATA_PART_MAIN,
            TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL,
            -> Unit
            TrackOutput.SAMPLE_DATA_PART_ENCRYPTION ->
                fail("dv7_transform_encrypted", "Encrypted Profile 7 samples cannot be transformed")
            else -> fail("dv7_transform_malformed", "Unknown Profile 7 sample-data part $sampleDataPart")
        }
    }

    private fun readBigEndianInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)

    private fun writeBigEndianInt(bytes: ByteArray, value: Int) {
        bytes[0] = (value ushr 24).toByte()
        bytes[1] = (value ushr 16).toByte()
        bytes[2] = (value ushr 8).toByte()
        bytes[3] = value.toByte()
    }

    private fun fail(classification: String, message: String): Nothing {
        state = State.FAILED
        throw DolbyVisionTransformException(classification, message)
    }
}

internal fun dolbyVisionProfile(codecs: String?): Int? =
    Regex("""(?i)dv(?:he|h1|av|a1)\.(\d{2})""").find(codecs.orEmpty())
        ?.groupValues?.getOrNull(1)?.toIntOrNull()

internal fun dolbyVisionLevel(codecs: String?): Int =
    Regex("""(?i)dv(?:he|h1|av|a1)\.\d{2}\.(\d{2})""").find(codecs.orEmpty())
        ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 6

private fun hdr10ColorInfo(current: ColorInfo?): ColorInfo =
    (current?.buildUpon() ?: ColorInfo.Builder())
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorTransfer(C.COLOR_TRANSFER_ST2084)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setHdrStaticInfo(current?.hdrStaticInfo ?: defaultDolbyVisionHdrStaticInfo())
        .build()

/** Removes enhancement-layer/type-63 parameter sets from Annex-B or hvcC. */
internal fun filterHevcInitializationData(data: ByteArray): ByteArray? {
    if (isDolbyVisionConfigurationRecord(data)) return null
    if (data.size >= 4 &&
        data[0] == 0.toByte() &&
        data[1] == 0.toByte() &&
        (data[2] == 1.toByte() || data[2] == 0.toByte() && data[3] == 1.toByte())
    ) {
        return filterAnnexBInitializationData(data)
    }
    if (data.size >= 23 && data[0] == 1.toByte() && (data[22].toInt() and 0xff) > 0) {
        return filterHvcCInitializationData(data)
            ?: throw DolbyVisionTransformException(
                "dv7_transform_malformed",
                "Malformed HEVC decoder configuration record.",
            )
    }
    throw DolbyVisionTransformException(
        "dv7_transform_malformed",
        "Unrecognized Profile 7 decoder initialization data.",
    )
}

private fun isDolbyVisionConfigurationRecord(data: ByteArray): Boolean {
    if (data.size != 24 || data[0] != 1.toByte()) return false
    val profile = (data[2].toInt() and 0xff) ushr 1
    return profile in 4..9
}

internal fun buildDolbyVisionProfile81Config(level: Int): ByteArray = ByteArray(24).also { record ->
    val boundedLevel = level.coerceIn(0, 63)
    record[0] = 1 // dv_version_major
    record[1] = 0 // dv_version_minor
    record[2] = ((8 shl 1) or ((boundedLevel ushr 5) and 1)).toByte()
    record[3] = (((boundedLevel and 0x1f) shl 3) or 0x05).toByte() // rpu=1, el=0, bl=1
    record[4] = (1 shl 4).toByte() // HDR10 compatible base layer
}

fun Throwable.dolbyVisionTransformClassification(): String? {
    var current: Throwable? = this
    repeat(12) {
        val transform = current as? DolbyVisionTransformException
        if (transform != null) return transform.classification
        current = current?.cause
    }
    return null
}

private fun filterAnnexBInitializationData(data: ByteArray): ByteArray {
    val starts = ArrayList<Pair<Int, Int>>()
    var i = 0
    while (i + 2 < data.size) {
        val length = when {
            i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
            data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> 3
            else -> 0
        }
        if (length > 0) {
            starts += i to length
            i += length
        } else i++
    }
    if (starts.isEmpty() || starts.first().first != 0) return data
    val output = ArrayList<Byte>(data.size)
    starts.forEachIndexed { index, (offset, prefixLength) ->
        val nalStart = offset + prefixLength
        val nalEnd = starts.getOrNull(index + 1)?.first ?: data.size
        if (nalEnd - nalStart < 2) return@forEachIndexed
        val byte0 = data[nalStart].toInt() and 0xff
        val byte1 = data[nalStart + 1].toInt() and 0xff
        val nalType = (byte0 ushr 1) and 0x3f
        val layer = ((byte0 and 1) shl 5) or ((byte1 ushr 3) and 0x1f)
        if (layer == 0 && nalType != 62 && nalType != 63) {
            for (p in offset until nalEnd) output += data[p]
        }
    }
    if (output.isEmpty()) {
        throw DolbyVisionTransformException(
            "dv7_transform_malformed",
            "HEVC initialization data contains no base-layer parameter sets.",
        )
    }
    return output.toByteArray()
}

private fun filterHvcCInitializationData(source: ByteArray): ByteArray? {
    var cursor = 23
    val arrays = source[22].toInt() and 0xff
    val kept = ArrayList<Byte>(source.size)
    for (i in 0 until 22) kept += source[i]
    val payloads = ArrayList<Byte>(source.size)
    var keptArrays = 0
    repeat(arrays) {
        if (cursor + 3 > source.size) return null
        val header = source[cursor++]
        val count = ((source[cursor].toInt() and 0xff) shl 8) or (source[cursor + 1].toInt() and 0xff)
        cursor += 2
        val nals = ArrayList<ByteArray>()
        repeat(count) {
            if (cursor + 2 > source.size) return null
            val size = ((source[cursor].toInt() and 0xff) shl 8) or (source[cursor + 1].toInt() and 0xff)
            cursor += 2
            if (size < 2 || cursor + size > source.size) return null
            val nal = source.copyOfRange(cursor, cursor + size)
            cursor += size
            val b0 = nal[0].toInt() and 0xff
            val b1 = nal[1].toInt() and 0xff
            val type = (b0 ushr 1) and 0x3f
            val layer = ((b0 and 1) shl 5) or ((b1 ushr 3) and 0x1f)
            if (layer == 0 && type != 62 && type != 63) nals += nal
        }
        if (nals.isNotEmpty()) {
            keptArrays++
            payloads += header
            payloads += ((nals.size ushr 8) and 0xff).toByte()
            payloads += (nals.size and 0xff).toByte()
            nals.forEach { nal ->
                payloads += ((nal.size ushr 8) and 0xff).toByte()
                payloads += (nal.size and 0xff).toByte()
                nal.forEach(payloads::add)
            }
        }
    }
    if (keptArrays == 0) return null
    kept += keptArrays.toByte()
    kept += payloads
    return kept.toByteArray()
}
