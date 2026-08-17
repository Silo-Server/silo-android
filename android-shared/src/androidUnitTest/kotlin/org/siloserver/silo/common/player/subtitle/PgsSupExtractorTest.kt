package org.siloserver.silo.common.player.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * Framing only. Building a real PGS bitmap by hand would test libpgs, not this
 * extractor; what matters here is that the stream is split into display sets,
 * each set reaches the parser in the container-shaped form it expects, and the
 * PTS from the `.sup` prefix becomes the sample timestamp.
 */
@RunWith(RobolectricTestRunner::class)
class PgsSupExtractorTest {

    @Test
    fun sniffsThePgMagic() {
        val extractor = PgsSupExtractor(RecordingParserFactory(), { 0L }, pgsFormat())

        assertTrue(extractor.sniff(FakeExtractorInput.Builder().setData(supStream()).build()))
        assertEquals(
            false,
            extractor.sniff(
                FakeExtractorInput.Builder().setData(byteArrayOf(0x00, 0x01, 0x02)).build(),
            ),
        )
    }

    @Test
    fun advertisesASeekableStartForMergedNonZeroResume() {
        val extractor = PgsSupExtractor(RecordingParserFactory(), { 0L }, pgsFormat())
        val output = FakeExtractorOutput()

        extractor.init(output)

        assertTrue(output.seekMap.isSeekable)
        val seekPoints = output.seekMap.getSeekPoints(15_000_000L)
        assertEquals(0L, seekPoints.first.timeUs)
        assertEquals(0L, seekPoints.first.position)
    }

    @Test
    fun indexesParsedDisplaySetsByTimestampAndBytePosition() {
        val extractor = PgsSupExtractor(RecordingParserFactory(), { 0L }, pgsFormat())
        val output = FakeExtractorOutput()
        extractor.init(output)

        drain(extractor, FakeExtractorInput.Builder().setData(supStream()).build())

        val seekPoints = output.seekMap.getSeekPoints(3_500_000L)
        assertEquals(3_000_000L, seekPoints.first.timeUs)
        assertTrue(seekPoints.first.position > 0L)
    }

    @Test
    fun eachDisplaySetBecomesOneSampleAtItsOwnPts() {
        val factory = RecordingParserFactory()
        val extractor = PgsSupExtractor(factory, { 0L }, pgsFormat())
        val output = FakeExtractorOutput()
        extractor.init(output)

        drain(extractor, FakeExtractorInput.Builder().setData(supStream()).build())

        // Two sets in, two parse calls out — not one call with the whole file,
        // which is what made a mounted `.sup` render nothing.
        assertEquals(2, factory.parsed.size)
        val track = output.trackOutputs[0]!!
        assertEquals(2, track.sampleCount)
        assertEquals(1_000_000L, track.getSampleTimeUs(0))
        assertEquals(3_000_000L, track.getSampleTimeUs(1))
    }

    // The mount resolver matches on the track's id/language/label, so losing
    // them makes a perfectly good sidecar unselectable.
    // Sync and the re-anchor delta have to reach these cues; PGS carries no
    // cue-relative time for the parser's offset wrapper to shift, so the
    // extractor applies it to the sample timestamp.
    @Test
    fun theOffsetShiftsTheSampleTimestamp() {
        val factory = RecordingParserFactory()
        val extractor = PgsSupExtractor(factory, { -500_000L }, pgsFormat())
        val output = FakeExtractorOutput()
        extractor.init(output)

        drain(extractor, FakeExtractorInput.Builder().setData(supStream()).build())

        val track = output.trackOutputs[0]!!
        assertEquals(500_000L, track.getSampleTimeUs(0))
        assertEquals(2_500_000L, track.getSampleTimeUs(1))
    }

    /**
     * A SUP is read from the top on every seek, so every caption before the
     * target streams through first. PGS is REPLACE with no duration: publish
     * those and each one is "the newest cue at or before the position" for as
     * long as the next takes to download — the film's caption history replays
     * on screen while the video buffers at the resume point (seen on an onn
     * box and reproduced on the TV emulator: a fresh caption every ~0.8s at a
     * pinned position). Only the set in force at the seek point survives, and
     * it lands AT the seek point.
     */
    @Test
    fun captionsBeforeTheSeekPointAreNotPublishedExceptTheOneInForce() {
        val factory = RecordingParserFactory()
        val extractor = PgsSupExtractor(factory, { 0L }, pgsFormat())
        val output = FakeExtractorOutput()
        extractor.init(output)
        extractor.seek(0L, 4_000_000L)

        drain(extractor, FakeExtractorInput.Builder().setData(threeSetStream()).build())

        // 1s never reached the parser; 3s (in force at 4s) and 5s did.
        assertEquals(2, factory.parsed.size)
        val track = output.trackOutputs[0]!!
        assertEquals(2, track.sampleCount)
        assertEquals(4_000_000L, track.getSampleTimeUs(0))
        assertEquals(5_000_000L, track.getSampleTimeUs(1))
    }

    /**
     * The re-anchored case that bit in the field: the server starts the
     * stream at the resume point, so the player timeline is 0 there and the
     * offset shifts the SUP's absolute times back by that much. Everything
     * before the resume point goes negative — it must not clamp to zero and
     * publish, it must be dropped, bar the one caption in force.
     */
    @Test
    fun aReanchoredTimelineDropsTheNegativeHistoryInsteadOfClampingIt() {
        val factory = RecordingParserFactory()
        val extractor = PgsSupExtractor(factory, { -3_500_000L }, pgsFormat())
        val output = FakeExtractorOutput()
        extractor.init(output)
        extractor.seek(0L, 0L)

        drain(extractor, FakeExtractorInput.Builder().setData(threeSetStream()).build())

        val track = output.trackOutputs[0]!!
        assertEquals(2, track.sampleCount)
        // 3s (in force at the resume point) lands at 0; 5s lands at 1.5s.
        assertEquals(0L, track.getSampleTimeUs(0))
        assertEquals(1_500_000L, track.getSampleTimeUs(1))
    }

    /**
     * With the sidecar taken out of the loading gate the video runs ahead of
     * this download, so a set can arrive after the playhead has passed it.
     * That set would flash for one render tick; the live floor drops it too.
     */
    @Test
    fun aSetThePlayheadHasAlreadyPassedIsHistoryToo() {
        val factory = RecordingParserFactory()
        var playheadUs = 0L
        val extractor = PgsSupExtractor(factory, { 0L }, pgsFormat(), { playheadUs })
        val output = FakeExtractorOutput()
        extractor.init(output)
        extractor.seek(0L, 0L)
        playheadUs = 4_000_000L // playing at 4s while the SUP is still arriving

        drain(extractor, FakeExtractorInput.Builder().setData(threeSetStream()).build())

        val track = output.trackOutputs[0]!!
        assertEquals(2, track.sampleCount)
        // 1s dropped; 3s is the caption in force at 4s and keeps its own time
        // (the seek point is 0); 5s is ahead of the playhead and published as is.
        assertEquals(3_000_000L, track.getSampleTimeUs(0))
        assertEquals(5_000_000L, track.getSampleTimeUs(1))
    }

    /** A resume past the last caption still gets the set in force there. */
    @Test
    fun aSeekPastEveryCaptionPublishesTheLastOneAtTheSeekPoint() {
        val factory = RecordingParserFactory()
        val extractor = PgsSupExtractor(factory, { 0L }, pgsFormat())
        val output = FakeExtractorOutput()
        extractor.init(output)
        extractor.seek(0L, 9_000_000L)

        drain(extractor, FakeExtractorInput.Builder().setData(threeSetStream()).build())

        val track = output.trackOutputs[0]!!
        assertEquals(1, track.sampleCount)
        assertEquals(9_000_000L, track.getSampleTimeUs(0))
    }

    @Test
    fun theEmittedTrackKeepsTheSidecarIdentity() {
        val extractor = PgsSupExtractor(RecordingParserFactory(), { 0L }, pgsFormat())
        val output = FakeExtractorOutput()
        extractor.init(output)

        val emitted = output.trackOutputs[0]!!.lastFormat!!
        assertEquals("silo-subtitle:8", emitted.id)
        assertEquals("en", emitted.language)
        assertEquals("English (SDH)", emitted.label)
        assertEquals("application/pgs", emitted.codecs)
    }

    @Test
    fun segmentsReachTheParserWithoutTheSupPrefix() {
        val factory = RecordingParserFactory()
        val extractor = PgsSupExtractor(factory, { 0L }, pgsFormat())
        extractor.init(FakeExtractorOutput())

        drain(extractor, FakeExtractorInput.Builder().setData(supStream()).build())

        // [type][len-hi][len-lo][payload] — the shape Matroska hands the parser.
        val first = factory.parsed.first()
        assertEquals(SEGMENT_TYPE_PCS.toByte(), first[0])
        assertEquals(0, first[1].toInt())
        assertEquals(2, first[2].toInt())
        assertEquals(0xAA.toByte(), first[3])
        assertEquals(0xBB.toByte(), first[4])
        // The END section is kept, not stripped: Media3's PgsParser builds the
        // cue when it reads one, so a set without it parses to nothing.
        assertEquals(PgsSupExtractor.SEGMENT_TYPE_END.toByte(), first[5])
        assertEquals(8, first.size)
    }

    @Test
    fun aTruncatedTrailingSegmentDoesNotInventACue() {
        val factory = RecordingParserFactory()
        val extractor = PgsSupExtractor(factory, { 0L }, pgsFormat())
        val output = FakeExtractorOutput()
        extractor.init(output)

        val truncated = supStream().copyOf(supStream().size - 4)
        drain(extractor, FakeExtractorInput.Builder().setData(truncated).build())

        // The complete first set still parses; the severed one is dropped.
        assertEquals(1, factory.parsed.size)
    }

    @Test
    fun anOversizedDisplaySetWithoutEndFailsClosedBeforeConsumingTheStream() {
        val factory = RecordingParserFactory()
        val extractor = PgsSupExtractor(factory, { 0L }, pgsFormat())
        extractor.init(FakeExtractorOutput())
        val oversized = missingEndStream(
            segmentCount = 300,
            payloadSize = 65_535,
        )
        val input = FakeExtractorInput.Builder().setData(oversized).build()

        drain(extractor, input)

        assertTrue(input.position < oversized.size)
        assertTrue(factory.parsed.isEmpty())
    }

    @Test
    fun tooManySegmentsWithoutEndFailClosedBeforeConsumingTheStream() {
        val factory = RecordingParserFactory()
        val extractor = PgsSupExtractor(factory, { 0L }, pgsFormat())
        extractor.init(FakeExtractorOutput())
        val oversized = missingEndStream(
            segmentCount = 3_000,
            payloadSize = 0,
        )
        val input = FakeExtractorInput.Builder().setData(oversized).build()

        drain(extractor, input)

        assertTrue(input.position < oversized.size)
        assertTrue(factory.parsed.isEmpty())
    }

    private fun pgsFormat(): Format = Format.Builder()
        .setId("silo-subtitle:8")
        .setSampleMimeType("application/pgs")
        .setLanguage("en")
        .setLabel("English (SDH)")
        .build()

    private fun drain(extractor: Extractor, input: FakeExtractorInput) {
        val position = PositionHolder()
        var guard = 0
        while (extractor.read(input, position) != Extractor.RESULT_END_OF_INPUT) {
            if (++guard > 1000) error("extractor did not terminate")
        }
    }

    /**
     * A caption declaring an enormous bitmap must never reach the parser.
     *
     * Media3 trusts these two 16-bit fields: it allocates IntArray(w * h) and
     * an ARGB bitmap from them. 40000x40000 asks for 1.6 billion pixels — over
     * 6 GB — from eleven bytes of input. Catching the failure afterwards is too
     * late on a TV box, where the process simply disappears.
     */
    @Test
    fun anObjectDeclaringAnUnreasonableBitmapIsRejected() {
        val out = ByteArrayOutputStream()
        out.writeSegment(pts90kHz = 90_000, type = SEGMENT_TYPE_PCS, payload = byteArrayOf(0xAA.toByte()))
        out.writeSegment(
            pts90kHz = 90_000,
            type = PgsSupExtractor.SEGMENT_TYPE_OBJECT,
            payload = objectSegment(width = 40_000, height = 40_000),
        )
        out.writeSegment(pts90kHz = 90_000, type = PgsSupExtractor.SEGMENT_TYPE_END, payload = ByteArray(0))

        val factory = RecordingParserFactory()
        val extractor = PgsSupExtractor(factory, { 0L }, pgsFormat())
        extractor.init(FakeExtractorOutput())
        drain(extractor, FakeExtractorInput.Builder().setData(out.toByteArray()).build())

        assertEquals(0, factory.parsed.size)
    }

    /** A full-frame 1080p caption is legitimate and must still play. */
    @Test
    fun aFullFrameObjectIsAccepted() {
        val out = ByteArrayOutputStream()
        out.writeSegment(pts90kHz = 90_000, type = SEGMENT_TYPE_PCS, payload = byteArrayOf(0xAA.toByte()))
        out.writeSegment(
            pts90kHz = 90_000,
            type = PgsSupExtractor.SEGMENT_TYPE_OBJECT,
            payload = objectSegment(width = 1920, height = 1080),
        )
        out.writeSegment(pts90kHz = 90_000, type = PgsSupExtractor.SEGMENT_TYPE_END, payload = ByteArray(0))

        val factory = RecordingParserFactory()
        val extractor = PgsSupExtractor(factory, { 0L }, pgsFormat())
        extractor.init(FakeExtractorOutput())
        drain(extractor, FakeExtractorInput.Builder().setData(out.toByteArray()).build())

        assertEquals(1, factory.parsed.size)
    }

    /** First-sequence ODS payload: id, version, descriptor, length, w, h. */
    private fun objectSegment(width: Int, height: Int): ByteArray = byteArrayOf(
        0x00, 0x01, // object id
        0x00, // version
        0x80.toByte(), // first sequence
        0x00, 0x00, 0x10, // object data length (>= 4)
        (width shr 8 and 0xFF).toByte(), (width and 0xFF).toByte(),
        (height shr 8 and 0xFF).toByte(), (height and 0xFF).toByte(),
        0x00, 0x00, // token RLE bytes
    )

    /** Two display sets: PTS 1s and 3s, each one PCS segment then END. */
    private fun supStream(): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeSegment(pts90kHz = 90_000, type = SEGMENT_TYPE_PCS, payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        out.writeSegment(pts90kHz = 90_000, type = PgsSupExtractor.SEGMENT_TYPE_END, payload = ByteArray(0))
        out.writeSegment(pts90kHz = 270_000, type = SEGMENT_TYPE_PCS, payload = byteArrayOf(0xCC.toByte()))
        out.writeSegment(pts90kHz = 270_000, type = PgsSupExtractor.SEGMENT_TYPE_END, payload = ByteArray(0))
        return out.toByteArray()
    }

    /** Three display sets: PTS 1s, 3s and 5s. */
    private fun threeSetStream(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(supStream())
        out.writeSegment(pts90kHz = 450_000, type = SEGMENT_TYPE_PCS, payload = byteArrayOf(0xDD.toByte()))
        out.writeSegment(pts90kHz = 450_000, type = PgsSupExtractor.SEGMENT_TYPE_END, payload = ByteArray(0))
        return out.toByteArray()
    }

    private fun missingEndStream(segmentCount: Int, payloadSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val payload = ByteArray(payloadSize) { 0x5A }
        repeat(segmentCount) {
            out.writeSegment(
                pts90kHz = 90_000,
                type = SEGMENT_TYPE_PCS,
                payload = payload,
            )
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeSegment(pts90kHz: Long, type: Int, payload: ByteArray) {
        write('P'.code)
        write('G'.code)
        write((pts90kHz shr 24 and 0xFF).toInt())
        write((pts90kHz shr 16 and 0xFF).toInt())
        write((pts90kHz shr 8 and 0xFF).toInt())
        write((pts90kHz and 0xFF).toInt())
        repeat(4) { write(0) } // DTS
        write(type)
        write(payload.size shr 8 and 0xFF)
        write(payload.size and 0xFF)
        write(payload)
    }

    private class RecordingParserFactory : SubtitleParser.Factory {
        val parsed = mutableListOf<ByteArray>()

        override fun supportsFormat(format: Format): Boolean = true

        override fun getCueReplacementBehavior(format: Format): Int =
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

        override fun create(format: Format): SubtitleParser = object : SubtitleParser {
            override fun getCueReplacementBehavior(): Int =
                Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

            override fun parse(
                data: ByteArray,
                offset: Int,
                length: Int,
                outputOptions: SubtitleParser.OutputOptions,
                output: Consumer<CuesWithTiming>,
            ) {
                parsed += data.copyOfRange(offset, offset + length)
                output.accept(
                    CuesWithTiming(listOf(Cue.Builder().setText("x").build()), 0L, C.TIME_UNSET),
                )
            }

            override fun reset() = Unit
        }
    }

    private companion object {
        const val SEGMENT_TYPE_PCS = 0x16
    }
}
