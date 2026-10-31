package org.siloserver.silo.common.player.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.collect.ImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals

class OffsetSubtitleParserFactoryTest {
    /** Reports cues the way Media3's PGS and DVB parsers do: no start or duration of their own. */
    private class SampleRelativeBitmapParser : SubtitleParser {
        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) = output.accept(CuesWithTiming(ImmutableList.of<Cue>(), C.TIME_UNSET, C.TIME_UNSET))

        override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
    }

    private class Factory : SubtitleParser.Factory {
        override fun supportsFormat(format: Format): Boolean = true
        override fun getCueReplacementBehavior(format: Format): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        override fun create(format: Format): SubtitleParser = SampleRelativeBitmapParser()
    }

    private fun startFor(offsetUs: Long): CuesWithTiming {
        var parsed: CuesWithTiming? = null
        OffsetSubtitleParserFactory({ offsetUs }, Factory())
            .create(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build())
            .parse(ByteArray(1), SubtitleParser.OutputOptions.allCues()) { parsed = it }
        return parsed!!
    }

    @Test
    fun `a bitmap cue without its own start is shifted from its sample`() {
        assertEquals(0L, startFor(0L).startTimeUs)
        assertEquals(250_000L, startFor(250_000L).startTimeUs)
        // Advancing clamps at the sample instead of overflowing into a time the cue never reaches.
        assertEquals(0L, startFor(-100_000L).startTimeUs)
        assertEquals(C.TIME_UNSET, startFor(-100_000L).durationUs)
    }
}
