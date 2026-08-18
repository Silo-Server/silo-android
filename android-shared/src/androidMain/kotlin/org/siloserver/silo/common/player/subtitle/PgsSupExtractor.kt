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
import java.io.EOFException

/**
 * Extractor for a raw PGS elementary stream (`.sup`), which Media3 has no
 * extractor for.
 *
 * Media3 parses PGS but only as one *display set* at a time, timestamped by
 * whatever container carried it — inside Matroska the block supplies the time
 * and the payload is bare `[type][len][payload]` segments. A `.sup` file is the
 * same segments with a 10-byte `PG`+PTS+DTS prefix on each and no container at
 * all, so handing the whole file to the parser (which is what SubtitleExtractor
 * does) yields nothing: it sees one enormous sample with no timing. That is why
 * a mounted `.sup` sidecar selected cleanly and drew nothing.
 *
 * So: frame the stream into display sets, strip each segment's prefix back to
 * the container-shaped form the parser expects, and emit one parsed cue sample
 * per set at the PTS the prefix carried.
 *
 * The parser is injected rather than constructed here so the caller's offset
 * wrapper still applies — subtitle sync and the re-anchor delta have to reach
 * these cues like any other.
 */
@UnstableApi
class PgsSupExtractor(
    private val parserFactory: SubtitleParser.Factory,
    /**
     * Subtitle sync + the server re-anchor delta, in microseconds. Applied to
     * the sample timestamp here because PGS carries no cue-relative time for
     * the parser's own offset wrapper to shift — it reports TIME_UNSET, and
     * adding that produced timestamps ~9.2e15 ms into the future.
     */
    private val offsetUsProvider: () -> Long,
    /**
     * The sidecar's own format. Emitting a freshly built one instead drops the
     * stable track id, language and label the mount resolver matches on — the
     * track then appears as `mounted=[1:]` and the pick dies on its deadline.
     */
    private val sourceFormat: Format,
    /**
     * The live player-timeline position, in microseconds, when the sidecar is
     * mounted through [SidecarSubtitleMediaSource]. The video is allowed to
     * run ahead of this download, so a set can arrive after the playhead has
     * already passed it; the guard in [flushDisplaySet] uses whichever is
     * later — the seek point or this — so such a set is history too.
     */
    private val playbackFloorUsProvider: () -> Long = { 0L },
) : Extractor {

    private val cueEncoder = CueEncoder()
    private val headerScratch = ByteArray(SEGMENT_HEADER_SIZE)

    private var trackOutput: TrackOutput? = null
    private var parser: SubtitleParser? = null

    /** Segments of the display set being accumulated, already prefix-stripped. */
    private var displaySet = ByteArrayBuilder()
    private var displaySetTimeUs = C.TIME_UNSET
    private var displaySetPosition: Long = C.POSITION_UNSET.toLong()
    private var displaySetSegmentCount = 0
    private var failedClosed = false
    private var emittedSets = 0
    /** Display sets dropped because the parser could not survive them. */
    private var malformedSets = 0
    private var emittedCues = 0
    private var lastIndexedTimeUs = 0L
    private var lastIndexedPosition = 0L

    /**
     * The player-timeline position this read started from — Media3's own seek
     * target for a resume or scrub, or the reset seek at first load. Anything
     * that lands before it is history and never becomes a sample; see
     * [flushDisplaySet].
     */
    private var seekTimeUs = 0L

    /**
     * The last history display set seen, still framed. PGS ends a caption
     * only with the next set, so the newest set at or before the seek point
     * IS the caption on screen at that moment; it is decoded and published
     * once — at the seek point — before the first in-window set.
     */
    private var carriedSet: ByteArray? = null
    private var carriedSetTimeUs = C.TIME_UNSET
    private var skippedHistorySets = 0

    // ProgressiveMediaPeriod coerces every seek to zero when an extractor
    // advertises an unseekable map. In a MergingMediaSource that makes a PGS
    // child return 0 while the video child accepts the requested resume point,
    // and Media3 fails the whole selection with "Children enabled at different
    // positions." A raw SUP stream can always be restarted at byte zero and
    // scanned forward, so advertise that truthful (if conservative) seek map.
    private val seekMap = IndexSeekMap(
        longArrayOf(0L),
        longArrayOf(0L),
        C.TIME_UNSET,
    )

    override fun sniff(input: ExtractorInput): Boolean {
        val probe = ByteArray(2)
        return try {
            input.peekFully(probe, 0, probe.size)
            probe[0] == MAGIC_P && probe[1] == MAGIC_G
        } catch (_: EOFException) {
            false
        }
    }

    override fun init(output: ExtractorOutput) {
        val track = output.track(0, C.TRACK_TYPE_TEXT)
        parser = parserFactory.create(sourceFormat)
        // Everything except the sample mime carries over: id, language, label,
        // selection and role flags are what identify this track downstream.
        track.format(
            sourceFormat.buildUpon()
                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                .setCodecs(sourceFormat.sampleMimeType)
                .setCueReplacementBehavior(
                    parserFactory.getCueReplacementBehavior(sourceFormat),
                )
                .build(),
        )
        trackOutput = track
        output.endTracks()
        // The cues are held in the sample queue once read, so backward seeks are
        // served from memory; a seek before the read completes just restarts it.
        output.seekMap(seekMap)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (failedClosed) return Extractor.RESULT_END_OF_INPUT
        val output = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        val segmentPosition = input.position
        try {
            input.readFully(headerScratch, 0, SEGMENT_HEADER_SIZE)
        } catch (_: EOFException) {
            discardPendingDisplaySet()
            // A resume past the last caption: whatever was in force there —
            // usually the clear that ended it — is still the truth on screen.
            parser?.let { publishCarriedSet(it, output) }
            return Extractor.RESULT_END_OF_INPUT
        }
        val header = ParsableByteArray(headerScratch)
        if (header.readUnsignedByte() != MAGIC_P_INT || header.readUnsignedByte() != MAGIC_G_INT) {
            // Resynchronising mid-stream would mean guessing where the next
            // segment starts; a truncated or mislabelled artifact is better
            // reported as end-of-input than turned into fabricated cues.
            discardPendingDisplaySet()
            return Extractor.RESULT_END_OF_INPUT
        }
        val pts90kHz = header.readUnsignedInt()
        header.skipBytes(4) // DTS: unused, presentation time is what cues need.
        val segmentType = header.readUnsignedByte()
        val segmentLength = header.readUnsignedShort()

        if (displaySetSegmentCount >= MAX_DISPLAY_SET_SEGMENTS ||
            displaySet.size + CONTAINER_SEGMENT_HEADER_SIZE + segmentLength > MAX_DISPLAY_SET_BYTES
        ) {
            failClosed()
            return Extractor.RESULT_END_OF_INPUT
        }

        val payload = ByteArray(segmentLength)
        if (segmentLength > 0) {
            try {
                input.readFully(payload, 0, segmentLength)
            } catch (_: EOFException) {
                discardPendingDisplaySet()
                return Extractor.RESULT_END_OF_INPUT
            }
        }

        if (segmentType == SEGMENT_TYPE_END) {
            // The END section stays in the buffer: Media3's PgsParser builds the
            // cue when it reads one. Stripping it as pure framing produced a set
            // the parser accepted and returned zero cues for — a mounted,
            // selected, correctly timed track that drew nothing.
            appendSegment(segmentType, segmentLength, payload)
            flushDisplaySet(output)
            return Extractor.RESULT_CONTINUE
        }

        // Reject a hostile bitmap BEFORE Media3 sizes an allocation from it.
        // The parser trusts the declared width and height, allocating
        // IntArray(width * height) plus an ARGB bitmap, so a few bytes can ask
        // for hundreds of megabytes. Catching the failure afterwards is too
        // late — the memory pressure has already happened, and on a small box
        // the process simply goes.
        if (segmentType == SEGMENT_TYPE_OBJECT && !isPgsObjectWithinBudget(payload)) {
            malformedSets++
            org.siloserver.silo.common.player.SubDiag.log(
                "SUP object rejected: declared bitmap outside budget",
            )
            discardPendingDisplaySet()
            return Extractor.RESULT_CONTINUE
        }

        // First segment of a set carries the time the whole set is shown at.
        if (displaySet.isEmpty()) {
            displaySetTimeUs = pts90kHz * C.MICROS_PER_SECOND / PTS_CLOCK_HZ
            displaySetPosition = segmentPosition
        }
        appendSegment(segmentType, segmentLength, payload)
        return Extractor.RESULT_CONTINUE
    }

    /** Back to the container-shaped form the parser reads: [type][length][payload]. */
    private fun appendSegment(segmentType: Int, segmentLength: Int, payload: ByteArray) {
        displaySet.append(segmentType.toByte())
        displaySet.append((segmentLength shr 8 and 0xFF).toByte())
        displaySet.append((segmentLength and 0xFF).toByte())
        displaySet.append(payload)
        displaySetSegmentCount += 1
    }

    /**
     * A set is only complete once its END segment arrives, so a stream that
     * stops mid-set has nothing renderable — parsing what arrived would risk a
     * half-built caption from a composition with no bitmap yet.
     */
    private fun discardPendingDisplaySet() {
        displaySet = ByteArrayBuilder()
        displaySetTimeUs = C.TIME_UNSET
        displaySetPosition = C.POSITION_UNSET.toLong()
        displaySetSegmentCount = 0
    }

    private fun failClosed() {
        discardPendingDisplaySet()
        failedClosed = true
    }

    private fun flushDisplaySet(output: TrackOutput) {
        if (displaySet.isEmpty()) return
        val bytes = displaySet.toByteArray()
        val timeUs = displaySetTimeUs
        val position = displaySetPosition
        displaySet = ByteArrayBuilder()
        displaySetTimeUs = C.TIME_UNSET
        displaySetPosition = C.POSITION_UNSET.toLong()
        displaySetSegmentCount = 0
        val activeParser = parser ?: return
        val output = trackOutput ?: return
        if (timeUs == C.TIME_UNSET) return

        // A SUP is always read from byte zero (or from a coarse indexed point
        // below the target), so every set before the seek point streams
        // through here first. PGS is CUE_REPLACEMENT_BEHAVIOR_REPLACE with no
        // duration: were those history sets published — clamped to zero on a
        // re-anchored timeline, or simply timestamped in the past — each one
        // would be "the newest cue at or before the position" for as long as
        // it took the next to download, and the viewer would watch the film's
        // entire caption history replay while the video buffered at the resume
        // point. Hold only the newest history set and let the rest go.
        //
        // The same applies past the seek point once the video has been let
        // run ahead of this download: a set the playhead has already passed
        // would flash for one render tick if published, so the floor is the
        // later of the seek point and the live position.
        val adjustedTimeUs = timeUs + offsetUsProvider()
        val floorUs = maxOf(seekTimeUs, playbackFloorUsProvider())
        if (adjustedTimeUs < floorUs) {
            carriedSet = bytes
            carriedSetTimeUs = timeUs
            skippedHistorySets++
            if (skippedHistorySets == 1 || skippedHistorySets % 500 == 0) {
                org.siloserver.silo.common.player.SubDiag.log(
                    "SUP history skipped=$skippedHistorySets t=${timeUs / 1000}ms " +
                        "floor=${floorUs / 1000}ms",
                )
            }
            return
        }
        publishCarriedSet(activeParser, output)

        emittedSets++
        if (emittedSets <= 3 || emittedSets % 200 == 0) {
            org.siloserver.silo.common.player.SubDiag.log(
                "SUP set=$emittedSets t=${timeUs / 1000}ms bytes=${bytes.size}",
            )
        }
        val decoded = decodeGuarded(activeParser, bytes, timeUs) ?: return
        publishDisplaySet(output, decoded, adjustedTimeUs.coerceAtLeast(0L))
        val indexedTimeUs = adjustedTimeUs.coerceAtLeast(0L)
        if (
            position > lastIndexedPosition &&
            indexedTimeUs > lastIndexedTimeUs
        ) {
            seekMap.addSeekPoint(indexedTimeUs, position)
            lastIndexedTimeUs = indexedTimeUs
            lastIndexedPosition = position
        }
    }

    /**
     * Publish the caption in force at the point the read caught up, if one was
     * carried past it. It is timestamped at its own time, or at the seek point
     * if that is later — the player's queue starts there, and on a re-anchored
     * timeline its own time may well be negative. Every set that follows it
     * lies at or after the floor, which is at or after the seek point, so it
     * never pre-empts one; and until then it is "the newest cue at or before
     * the position", which for REPLACE is exactly what shows.
     */
    private fun publishCarriedSet(activeParser: SubtitleParser, output: TrackOutput) {
        val bytes = carriedSet ?: return
        val timeUs = carriedSetTimeUs
        carriedSet = null
        carriedSetTimeUs = C.TIME_UNSET
        val decoded = decodeGuarded(activeParser, bytes, timeUs) ?: return
        val sampleTimeUs = maxOf(timeUs + offsetUsProvider(), seekTimeUs).coerceAtLeast(0L)
        org.siloserver.silo.common.player.SubDiag.log(
            "SUP carried caption t=${timeUs / 1000}ms -> ${sampleTimeUs / 1000}ms " +
                "after skipping $skippedHistorySets",
        )
        publishDisplaySet(output, decoded, sampleTimeUs)
    }

    /**
     * The bundled Media3 PGS parser trusts the display set's own 16-bit
     * width/height: it allocates IntArray(width * height) and applies RLE
     * runs with no pixel bound of its own. A corrupt or hostile set can
     * therefore throw NegativeArraySizeException, an oversized-run
     * IllegalArgumentException, or ask for an allocation large enough to
     * take the process down on a low-memory box.
     *
     * Bounding the byte length upstream does not help — a handful of bytes
     * can declare an enormous bitmap. So the parse is contained here, and a
     * damaged caption costs one missing subtitle rather than the film.
     *
     * OutOfMemoryError is caught deliberately. It is not an error this
     * process caused by being unhealthy; it is one specific allocation
     * sized by untrusted input, and refusing to catch it on principle means
     * a bad caption kills playback. Narrow by construction: the block
     * contains only parsing and cue encoding, both sized by the display
     * set's own declared dimensions — the sample queue is not inside it.
     */
    private fun decodeGuarded(
        activeParser: SubtitleParser,
        bytes: ByteArray,
        timeUs: Long,
    ): List<ByteArray>? = try {
        decodeDisplaySet(activeParser, bytes, timeUs)
    } catch (e: Exception) {
        malformedSets++
        org.siloserver.silo.common.player.SubDiag.log(
            "SUP set $emittedSets rejected: ${e::class.simpleName}: ${e.message}",
        )
        null
    } catch (e: OutOfMemoryError) {
        malformedSets++
        org.siloserver.silo.common.player.SubDiag.log(
            "SUP set $emittedSets exhausted memory and was dropped",
        )
        null
    }

    /**
     * Decode a display set WITHOUT touching the sample queue.
     *
     * Parsing and publication are separated on purpose. Writing samples from
     * inside the parse callback means a failure partway through — after
     * sampleData and before sampleMetadata — leaves uncommitted bytes in the
     * queue, and the next sample then lands on a boundary the queue disagrees
     * about. A dropped caption is recoverable; a corrupt queue is not.
     *
     * So everything untrusted happens here and produces plain byte arrays, and
     * the caller publishes only if this returned normally.
     */
    private fun decodeDisplaySet(
        activeParser: SubtitleParser,
        bytes: ByteArray,
        timeUs: Long,
    ): List<ByteArray> {
        val encodedSamples = mutableListOf<ByteArray>()
        activeParser.parse(
            bytes,
            0,
            bytes.size,
            SubtitleParser.OutputOptions.allCues(),
        ) { cues ->
            emittedCues += cues.cues.size
            if (emittedCues <= 3) {
                org.siloserver.silo.common.player.SubDiag.log(
                    "SUP cue n=${cues.cues.size} at=${(timeUs + offsetUsProvider()) / 1000}ms",
                )
            }
            // Duration stays unset: PGS ends a caption with the next display
            // set, and the parser's REPLACE behaviour already means a new
            // sample supersedes the last one.
            encodedSamples += cueEncoder.encode(cues.cues, C.TIME_UNSET)
        }
        return encodedSamples
    }

    /**
     * Publish decoded samples at [sampleTimeUs], already on the player
     * timeline. Nothing here can throw on untrusted input.
     */
    private fun publishDisplaySet(
        output: TrackOutput,
        samples: List<ByteArray>,
        sampleTimeUs: Long,
    ) {
        samples.forEach { encoded ->
            output.sampleData(ParsableByteArray(encoded), encoded.size)
            output.sampleMetadata(
                sampleTimeUs,
                C.BUFFER_FLAG_KEY_FRAME,
                encoded.size,
                0,
                null,
            )
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        displaySet = ByteArrayBuilder()
        displaySetTimeUs = C.TIME_UNSET
        displaySetPosition = C.POSITION_UNSET.toLong()
        displaySetSegmentCount = 0
        failedClosed = false
        seekTimeUs = timeUs
        carriedSet = null
        carriedSetTimeUs = C.TIME_UNSET
        skippedHistorySets = 0
        parser?.reset()
    }

    override fun release() {
        parser = null
    }

    /** Grow-on-append byte buffer; a display set is a handful of small segments. */
    private class ByteArrayBuilder {
        private var buffer = ByteArray(INITIAL_CAPACITY)
        var size = 0
            private set

        fun isEmpty(): Boolean = size == 0

        fun append(value: Byte) {
            ensure(1)
            buffer[size++] = value
        }

        fun append(values: ByteArray) {
            if (values.isEmpty()) return
            ensure(values.size)
            values.copyInto(buffer, size)
            size += values.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        private fun ensure(extra: Int) {
            if (size + extra <= buffer.size) return
            var capacity = buffer.size
            while (capacity < size + extra) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }

        private companion object {
            const val INITIAL_CAPACITY = 4096
        }
    }

    companion object {
        /** `PG` magic, 4-byte PTS, 4-byte DTS, type, 2-byte length. */
        const val SEGMENT_HEADER_SIZE = 13
        const val SEGMENT_TYPE_END = 0x80
        /** ODS — the only segment that declares bitmap dimensions. */
        const val SEGMENT_TYPE_OBJECT = 0x15
        private const val CONTAINER_SEGMENT_HEADER_SIZE = 3
        private const val MAX_DISPLAY_SET_BYTES = 16 * 1024 * 1024
        private const val MAX_DISPLAY_SET_SEGMENTS = 512
        private const val PTS_CLOCK_HZ = 90_000L
        private const val MAGIC_P_INT = 0x50
        private const val MAGIC_G_INT = 0x47
        private const val MAGIC_P = MAGIC_P_INT.toByte()
        private const val MAGIC_G = MAGIC_G_INT.toByte()
    }
}

/**
 * Whether an ODS payload declares a bitmap this device should attempt.
 *
 * Reads only the object header, which is not a second PGS parser: two 16-bit
 * fields at a fixed offset. RLE correctness stays Media3's problem; this exists
 * solely so the allocation it performs is one we chose to allow.
 *
 * Layout, first-sequence object:
 *   0..1  object id
 *   2     version
 *   3     sequence descriptor  (bit 7 set = first/base sequence)
 *   4..6  object data length, 24-bit  (present only on a first sequence)
 *   7..8  width, 16-bit
 *   9..10 height, 16-bit
 *
 * Continuation segments carry no dimensions and are passed through: the base
 * sequence they belong to was already judged.
 */
private fun isPgsObjectWithinBudget(payload: ByteArray): Boolean {
    if (payload.size < 4) return false
    val isFirstSequence = (payload[3].toInt() and 0x80) != 0
    if (!isFirstSequence) return true
    if (payload.size < 11) return false

    fun u8(i: Int) = payload[i].toInt() and 0xFF
    val objectDataLength = (u8(4) shl 16) or (u8(5) shl 8) or u8(6)
    val width = (u8(7) shl 8) or u8(8)
    val height = (u8(9) shl 8) or u8(10)

    // object_data_length counts the four width/height bytes, so anything below
    // them is malformed rather than merely small.
    if (objectDataLength < 4) return false
    if (width <= 0 || height <= 0) return false
    if (width > MAX_PGS_DIMENSION || height > MAX_PGS_DIMENSION) return false
    if (width.toLong() * height.toLong() > MAX_PGS_BITMAP_PIXELS) return false
    if (objectDataLength.toLong() - 4L > MAX_PGS_OBJECT_DATA_BYTES) return false
    return true
}

/** A full-frame 1080p caption is allowed; a 4K one is not, on TV memory. */
private const val MAX_PGS_BITMAP_PIXELS = 1920L * 1080L
private const val MAX_PGS_OBJECT_DATA_BYTES = 8L * 1024L * 1024L
private const val MAX_PGS_DIMENSION = 4096
