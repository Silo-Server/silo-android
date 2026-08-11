package org.siloserver.silo.common.player.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueEncoder
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream

/**
 * Incrementally turns a WebVTT response into Media3 cue samples.
 *
 * Silo can expose an embedded text track as a bounded, streaming WebVTT
 * extract. The first cues arrive quickly, but the HTTP response stays open
 * while FFmpeg scans the rest of the window. Media3's stock [androidx.media3.extractor.text.SubtitleExtractor]
 * buffers that response to EOF before it publishes any cue. In a
 * [androidx.media3.exoplayer.source.MergingMediaSource] that makes an otherwise
 * reusable HLS stream buffer until the complete subtitle window is extracted.
 *
 * WebVTT cue blocks are independently parseable once the header is supplied.
 * This extractor therefore emits each complete block immediately while the
 * response continues loading. It keeps only one unfinished block in memory,
 * preserves the configured parser (including Silo's timeline/user offset),
 * and fails closed at the same total byte limit as other subtitle loaders.
 */
@UnstableApi
class StreamingWebvttExtractor(
    private val subtitleParser: SubtitleParser,
    private val sourceFormat: Format,
    private val maxBytes: Long,
) : Extractor {
    private val cueEncoder = CueEncoder()
    private val readBuffer = ByteArray(READ_BUFFER_BYTES)
    private val pending = ByteArrayOutputStream()
    private val preambleBlocks = mutableListOf<ByteArray>()

    private var trackOutput: TrackOutput? = null
    private var seekTimeUs = C.TIME_UNSET
    private var bytesRead = 0L
    private var emittedSamples = 0
    private var sawCue = false
    private var failedClosed = false

    private val seekMap = IndexSeekMap(
        longArrayOf(0L),
        longArrayOf(0L),
        C.TIME_UNSET,
    )

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        val track = output.track(0, C.TRACK_TYPE_TEXT)
        track.format(
            sourceFormat.buildUpon()
                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                .setCodecs(sourceFormat.sampleMimeType)
                .setCueReplacementBehavior(subtitleParser.cueReplacementBehavior)
                .build(),
        )
        trackOutput = track
        output.endTracks()
        output.seekMap(seekMap)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (failedClosed) return Extractor.RESULT_END_OF_INPUT

        val read = input.read(readBuffer, 0, readBuffer.size)
        if (read == C.RESULT_END_OF_INPUT) {
            processPending(final = true)
            org.siloserver.silo.common.player.SubDiag.log(
                "streaming VTT complete bytes=$bytesRead samples=$emittedSamples",
            )
            return Extractor.RESULT_END_OF_INPUT
        }
        if (read <= 0) return Extractor.RESULT_CONTINUE

        bytesRead += read
        if (bytesRead > maxBytes || pending.size() + read > MAX_PENDING_BLOCK_BYTES) {
            failedClosed = true
            pending.reset()
            org.siloserver.silo.common.player.SubDiag.log(
                "streaming VTT rejected: subtitle exceeded bounded input",
            )
            return Extractor.RESULT_END_OF_INPUT
        }
        pending.write(readBuffer, 0, read)
        processPending(final = false)
        return Extractor.RESULT_CONTINUE
    }

    private fun processPending(final: Boolean) {
        var bytes = pending.toByteArray()
        var consumed = 0
        while (consumed < bytes.size) {
            val boundary = findBlockBoundary(bytes, consumed)
            if (boundary == null) break
            processBlock(bytes.copyOfRange(consumed, boundary.start))
            consumed = boundary.start + boundary.length
        }
        if (final && consumed < bytes.size) {
            processBlock(bytes.copyOfRange(consumed, bytes.size))
            consumed = bytes.size
        }
        if (consumed == 0) return

        val remainder = bytes.copyOfRange(consumed, bytes.size)
        pending.reset()
        pending.write(remainder)
    }

    private fun processBlock(rawBlock: ByteArray) {
        val block = rawBlock.trimAsciiLineBreaks()
        if (block.isEmpty()) return

        val text = block.decodeToString()
        when {
            text.removePrefix("\uFEFF").startsWith("WEBVTT") -> {
                preambleBlocks.clear()
                preambleBlocks += block
            }
            !sawCue && (text.startsWith("STYLE") || text.startsWith("REGION")) -> {
                preambleBlocks += block
            }
            text.startsWith("NOTE") -> Unit
            "-->" in text -> {
                sawCue = true
                parseAndPublish(block)
            }
        }
    }

    private fun parseAndPublish(cueBlock: ByteArray) {
        val document = ByteArrayOutputStream().apply {
            if (preambleBlocks.isEmpty()) {
                write(WEBVTT_HEADER)
            } else {
                preambleBlocks.forEach { block ->
                    write(block)
                    write(BLOCK_SEPARATOR)
                }
            }
            write(cueBlock)
            write(BLOCK_SEPARATOR)
        }.toByteArray()

        try {
            subtitleParser.parse(
                document,
                0,
                document.size,
                SubtitleParser.OutputOptions.allCues(),
            ) { cues ->
                if (seekTimeUs != C.TIME_UNSET && cues.endTimeUs < seekTimeUs) return@parse
                publish(cues.startTimeUs, cueEncoder.encode(cues.cues, cues.durationUs))
            }
        } catch (error: RuntimeException) {
            org.siloserver.silo.common.player.SubDiag.log(
                "streaming VTT cue rejected: ${error::class.simpleName}: ${error.message}",
            )
        }
    }

    private fun publish(timeUs: Long, encoded: ByteArray) {
        val output = trackOutput ?: return
        output.sampleData(ParsableByteArray(encoded), encoded.size)
        output.sampleMetadata(
            timeUs.coerceAtLeast(0L),
            C.BUFFER_FLAG_KEY_FRAME,
            encoded.size,
            0,
            null,
        )
        emittedSamples++
        if (emittedSamples <= 3) {
            org.siloserver.silo.common.player.SubDiag.log(
                "streaming VTT sample=$emittedSamples at=${timeUs / 1000}ms",
            )
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        pending.reset()
        preambleBlocks.clear()
        seekTimeUs = timeUs
        bytesRead = 0L
        emittedSamples = 0
        sawCue = false
        failedClosed = false
        subtitleParser.reset()
    }

    override fun release() {
        pending.reset()
        preambleBlocks.clear()
        trackOutput = null
        subtitleParser.reset()
    }

    private data class BlockBoundary(val start: Int, val length: Int)

    private companion object {
        val WEBVTT_HEADER = "WEBVTT\n\n".encodeToByteArray()
        val BLOCK_SEPARATOR = "\n\n".encodeToByteArray()
        const val READ_BUFFER_BYTES = 16 * 1024
        const val MAX_PENDING_BLOCK_BYTES = 1024 * 1024

        fun findBlockBoundary(bytes: ByteArray, from: Int): BlockBoundary? {
            var index = from
            while (index < bytes.lastIndex) {
                if (bytes[index] == '\n'.code.toByte() && bytes[index + 1] == '\n'.code.toByte()) {
                    return BlockBoundary(index, 2)
                }
                if (
                    index + 3 < bytes.size &&
                    bytes[index] == '\r'.code.toByte() &&
                    bytes[index + 1] == '\n'.code.toByte() &&
                    bytes[index + 2] == '\r'.code.toByte() &&
                    bytes[index + 3] == '\n'.code.toByte()
                ) {
                    return BlockBoundary(index, 4)
                }
                index++
            }
            return null
        }

        fun ByteArray.trimAsciiLineBreaks(): ByteArray {
            var start = 0
            var end = size
            while (start < end && (this[start] == '\r'.code.toByte() || this[start] == '\n'.code.toByte())) start++
            while (end > start && (this[end - 1] == '\r'.code.toByte() || this[end - 1] == '\n'.code.toByte())) end--
            return copyOfRange(start, end)
        }
    }
}
