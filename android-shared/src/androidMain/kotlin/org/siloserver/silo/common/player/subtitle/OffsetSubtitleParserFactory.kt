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
    /**
     * Whether a track whose cue times are relative to their sample keeps a
     * signed start. Set for parsers that extractors run per sample (embedded
     * tracks): Media3 adds the start to the sample time, so a negative start
     * advances the cue and clamping it would drop the offset. Left unset for
     * sidecar extractors, which use the start as the absolute cue time.
     */
    private val signedSampleRelativeStarts: Boolean = false,
) : SubtitleParser.Factory {

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser =
        OffsetSubtitleParser(
            delegate.create(format),
            offsetUsProvider,
            format.sampleMimeType,
            signedStarts = signedSampleRelativeStarts && format.subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE,
        )
}

@UnstableApi
private class OffsetSubtitleParser(
    private val delegate: SubtitleParser,
    private val offsetUsProvider: () -> Long,
    private val sampleMimeType: String?,
    private val signedStarts: Boolean,
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
            // starts at its sample, and Media3 accepts that only for a track
            // whose cue times are sample-relative. The offset becomes that
            // relative start, signed, so Media3 moves the sample by it and a
            // negative offset advances the cue. Adding it to the TIME_UNSET
            // sentinel instead clamped a positive offset away and overflowed a
            // negative one into a time the cue never reaches.
            if (cues.startTimeUs == C.TIME_UNSET) {
                output.accept(CuesWithTiming(cues.cues, offsetUs, cues.durationUs))
                return@Consumer
            }
            // A known sample-relative start (VobSub's control sequence delay,
            // usually 0) moves the same way.
            if (signedStarts) {
                output.accept(CuesWithTiming(cues.cues, cues.startTimeUs + offsetUs, cues.durationUs))
                return@Consumer
            }
            // Clamp the shifted start to 0, and shrink the duration by however
            // much we clamped so the cue still ends when it should — otherwise a
            // negative offset (advance subtitles) makes early cues linger past
            // their intended end and overlap the next one.
            val rawStart = cues.startTimeUs + offsetUs
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
