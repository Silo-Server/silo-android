package org.siloserver.silo.common.player.subtitle

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
 * gets [SubtitleOffsetHolder.getOffsetUs] added to its `startTimeUs`.
 * Reads the live offset on each emission — no need to recreate the parser
 * when the offset changes.
 */
@UnstableApi
class OffsetSubtitleParserFactory(
    private val holder: SubtitleOffsetHolder,
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser =
        OffsetSubtitleParser(delegate.create(format), holder, format.sampleMimeType)
}

@UnstableApi
private class OffsetSubtitleParser(
    private val delegate: SubtitleParser,
    private val holder: SubtitleOffsetHolder,
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
        val offsetUs = holder.getOffsetUs()
        val normalizedSubrip = if (sampleMimeType == MimeTypes.APPLICATION_SUBRIP) {
            normalizeSubripPayloadIfNeeded(data, offset, length)
        } else {
            null
        }
        val shifted = Consumer<CuesWithTiming> { cues ->
            output.accept(
                CuesWithTiming(
                    cues.cues,
                    (cues.startTimeUs + offsetUs).coerceAtLeast(0L),
                    cues.durationUs,
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
