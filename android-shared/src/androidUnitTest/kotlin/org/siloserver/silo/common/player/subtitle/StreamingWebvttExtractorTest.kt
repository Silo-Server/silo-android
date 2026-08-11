package org.siloserver.silo.common.player.subtitle

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamingWebvttExtractorTest {
    @Test
    fun `first complete cue is published before the response reaches eof`() {
        val trailingResponse = "NOTE\n${"x".repeat(20_000)}"
        val payload = (
            "WEBVTT\n\n" +
                "00:01.000 --> 00:02.000\nHello\n\n" +
                trailingResponse
            ).encodeToByteArray()
        val input = FakeExtractorInput.Builder().setData(payload).build()
        val output = FakeExtractorOutput()
        val extractor = extractor()
        extractor.init(output)

        assertEquals(Extractor.RESULT_CONTINUE, extractor.read(input, PositionHolder()))

        assertTrue(input.position < payload.size)
        assertEquals(1, output.trackOutputs[0]!!.sampleCount)
        assertEquals(1_000_000L, output.trackOutputs[0]!!.getSampleTimeUs(0))
    }

    @Test
    fun `crlf cue blocks are emitted independently and keep track identity`() {
        val payload = (
            "WEBVTT\r\n\r\n" +
                "00:01.000 --> 00:02.000\r\nOne\r\n\r\n" +
                "00:03.000 --> 00:04.000\r\nTwo\r\n\r\n"
            ).encodeToByteArray()
        val input = FakeExtractorInput.Builder().setData(payload).build()
        val output = FakeExtractorOutput()
        val extractor = extractor()
        extractor.init(output)

        drain(extractor, input)

        val track = output.trackOutputs[0]!!
        assertEquals(2, track.sampleCount)
        assertEquals(1_000_000L, track.getSampleTimeUs(0))
        assertEquals(3_000_000L, track.getSampleTimeUs(1))
        assertEquals("silo-subtitle:7", track.lastFormat!!.id)
        assertEquals("en", track.lastFormat!!.language)
        assertEquals("English", track.lastFormat!!.label)
        assertEquals(MimeTypes.TEXT_VTT, track.lastFormat!!.codecs)
    }

    @Test
    fun `configured parser offset is applied to each incremental cue`() {
        val payload = (
            "WEBVTT\n\n" +
                "00:01.000 --> 00:02.000\nShifted\n\n"
            ).encodeToByteArray()
        val input = FakeExtractorInput.Builder().setData(payload).build()
        val output = FakeExtractorOutput()
        val parser = OffsetSubtitleParserFactory(
            offsetUsProvider = { 1_500_000L },
        ).create(sourceFormat())
        val extractor = StreamingWebvttExtractor(
            parser,
            sourceFormat(),
            maxBytes = 32L * 1024 * 1024,
        )
        extractor.init(output)

        drain(extractor, input)

        assertEquals(2_500_000L, output.trackOutputs[0]!!.getSampleTimeUs(0))
    }

    @Test
    fun `input above the subtitle byte budget fails closed`() {
        val payload = ("WEBVTT\n\n" + "x".repeat(256)).encodeToByteArray()
        val input = FakeExtractorInput.Builder().setData(payload).build()
        val output = FakeExtractorOutput()
        val extractor = StreamingWebvttExtractor(
            WebvttParser(),
            sourceFormat(),
            maxBytes = 32,
        )
        extractor.init(output)

        assertEquals(Extractor.RESULT_END_OF_INPUT, extractor.read(input, PositionHolder()))
        assertEquals(0, output.trackOutputs[0]!!.sampleCount)
    }

    private fun extractor() = StreamingWebvttExtractor(
        WebvttParser(),
        sourceFormat(),
        maxBytes = 32L * 1024 * 1024,
    )

    private fun sourceFormat() = Format.Builder()
        .setId("silo-subtitle:7")
        .setSampleMimeType(MimeTypes.TEXT_VTT)
        .setLanguage("en")
        .setLabel("English")
        .build()

    private fun drain(extractor: Extractor, input: FakeExtractorInput) {
        val position = PositionHolder()
        var guard = 0
        while (extractor.read(input, position) != Extractor.RESULT_END_OF_INPUT) {
            if (++guard > 100) error("extractor did not terminate")
        }
    }
}
