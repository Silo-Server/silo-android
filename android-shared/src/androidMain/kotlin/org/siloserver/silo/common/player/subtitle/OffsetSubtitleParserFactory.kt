package org.siloserver.silo.common.player.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleParser.OutputOptions

/**
 * Wraps [DefaultSubtitleParserFactory] so every emitted [CuesWithTiming]
 * gets the current offset added to its `startTimeUs`.
 * Reads the live offset on each emission — no need to recreate the parser
 * when the offset changes.
 */
@UnstableApi
class OffsetSubtitleParserFactory(
    private val offsetUsProvider: () -> Long,
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser =
        OffsetSubtitleParser(delegate.create(format), offsetUsProvider, format.sampleMimeType)
}

@UnstableApi
private class OffsetSubtitleParser(
    private val delegate: SubtitleParser,
    private val offsetUsProvider: () -> Long,
    private val sampleMimeType: String?,
) : SubtitleParser {

    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val offsetUs = offsetUsProvider()
        val normalizedSubrip = if (sampleMimeType == MimeTypes.APPLICATION_SUBRIP) {
            normalizeSubripPayloadIfNeeded(data, offset, length)
        } else {
            null
        }
        val shifted = Consumer<CuesWithTiming> { cues ->
            // Bitmap parsers (PGS, DVB) report no start of their own: the cue
            // starts at its sample, which in a Matroska track is the same as a
            // sample-relative start of 0. Adding the offset to the TIME_UNSET
            // sentinel instead clamped a positive offset away and overflowed a
            // negative one into a time the cue never reaches.
            val start = if (cues.startTimeUs == C.TIME_UNSET) 0L else cues.startTimeUs
            // Clamp the shifted start to 0, and shrink the duration by however
            // much we clamped so the cue still ends when it should — otherwise a
            // negative offset (advance subtitles) makes early cues linger past
            // their intended end and overlap the next one.
            val rawStart = start + offsetUs
            val clampedStart = rawStart.coerceAtLeast(0L)
            val clampLossUs = clampedStart - rawStart
            val adjustedDuration = if (cues.durationUs == C.TIME_UNSET) {
                cues.durationUs
            } else {
                (cues.durationUs - clampLossUs).coerceAtLeast(0L)
            }
            output.accept(
                CuesWithTiming(
                    cues.cues,
                    clampedStart,
                    adjustedDuration,
                )
            )
        }
        if (normalizedSubrip != null) {
            delegate.parse(normalizedSubrip, 0, normalizedSubrip.size, outputOptions, shifted)
        } else {
            delegate.parse(data, offset, length, outputOptions, shifted)
        }
    }

    override fun reset() {
        delegate.reset()
    }
}
