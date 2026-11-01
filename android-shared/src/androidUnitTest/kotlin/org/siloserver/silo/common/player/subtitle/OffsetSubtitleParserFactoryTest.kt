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
    /** Emits one cue with [startTimeUs] and [durationUs], like a Media3 parser for one sample. */
    private class FixedParser(private val startTimeUs: Long, private val durationUs: Long) : SubtitleParser {
        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) = output.accept(CuesWithTiming(ImmutableList.of<Cue>(), startTimeUs, durationUs))

        override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
    }

    private class Factory(private val startTimeUs: Long, private val durationUs: Long) : SubtitleParser.Factory {
        override fun supportsFormat(format: Format): Boolean = true
        override fun getCueReplacementBehavior(format: Format): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        override fun create(format: Format): SubtitleParser = FixedParser(startTimeUs, durationUs)
    }

    private fun parse(
        offsetUs: Long,
        startTimeUs: Long,
        durationUs: Long = C.TIME_UNSET,
        embedded: Boolean = true,
        mimeType: String = MimeTypes.APPLICATION_PGS,
    ): CuesWithTiming {
        var parsed: CuesWithTiming? = null
        // A Matroska track's format keeps Media3's default, sample-relative cue times.
        OffsetSubtitleParserFactory({ offsetUs }, Factory(startTimeUs, durationUs), signedSampleRelativeStarts = embedded)
            .create(Format.Builder().setSampleMimeType(mimeType).build())
            .parse(ByteArray(1), SubtitleParser.OutputOptions.allCues()) { parsed = it }
        return parsed!!
    }

    @Test
    fun `a bitmap cue without its own start is shifted from its sample`() {
        // PGS and DVB report TIME_UNSET: the cue starts at its sample.
        assertEquals(0L, parse(0L, C.TIME_UNSET).startTimeUs)
        assertEquals(250_000L, parse(250_000L, C.TIME_UNSET).startTimeUs)
        // Sample-relative, so a negative offset advances the cue instead of
        // being clamped away or overflowing into a time the cue never reaches.
        assertEquals(-100_000L, parse(-100_000L, C.TIME_UNSET).startTimeUs)
        assertEquals(C.TIME_UNSET, parse(-100_000L, C.TIME_UNSET).durationUs)
    }

    @Test
    fun `an embedded cue with a sample-relative start keeps a negative offset`() {
        // VobSub reports its control sequence delay, usually 0. A cue in the
        // sample at 20 s with a -1 s offset must start at 19 s, not 20 s.
        val advanced = parse(-1_000_000L, startTimeUs = 0L, durationUs = 3_000_000L, mimeType = MimeTypes.APPLICATION_VOBSUB)
        assertEquals(-1_000_000L, advanced.startTimeUs)
        assertEquals(3_000_000L, advanced.durationUs)
        assertEquals(1_500_000L, parse(1_000_000L, startTimeUs = 500_000L, mimeType = MimeTypes.APPLICATION_VOBSUB).startTimeUs)
    }

    @Test
    fun `a sidecar cue keeps its absolute start clamped at zero`() {
        // A sidecar extractor writes the start as the cue's time in the film.
        val early = parse(-1_000_000L, startTimeUs = 400_000L, durationUs = 2_000_000L, embedded = false, mimeType = MimeTypes.TEXT_VTT)
        assertEquals(0L, early.startTimeUs)
        assertEquals(1_400_000L, early.durationUs)
        assertEquals(9_000_000L, parse(-1_000_000L, startTimeUs = 10_000_000L, embedded = false, mimeType = MimeTypes.TEXT_VTT).startTimeUs)
    }
}
