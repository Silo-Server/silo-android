package org.siloserver.silo.audiobook

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.VersionChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudiobookTimelineTest {

    private fun part(
        fileId: Int,
        duration: Double,
        partIndex: Int? = null,
        kind: String? = "audiobook_part",
        codecAudio: String? = "aac",
        chapters: List<VersionChapter>? = null,
    ) = FileVersion(
        fileId = fileId,
        codecAudio = codecAudio,
        duration = duration,
        presentationKind = kind,
        presentationPartIndex = partIndex,
        chapters = chapters,
    )

    private fun chapter(index: Int, start: Double, end: Double, title: String = "Chapter ${index + 1}") =
        VersionChapter(index = index, title = title, startSeconds = start, endSeconds = end)

    // Three parts: 100s, 200s, 150s → offsets 0, 100, 300; cumulative 450.
    private val threePartVersions = listOf(
        part(fileId = 10, duration = 100.0, partIndex = 0),
        part(fileId = 11, duration = 200.0, partIndex = 1),
        part(fileId = 12, duration = 150.0, partIndex = 2),
    )

    @Test
    fun `cumulative offsets across three parts`() {
        val timeline = buildAudiobookTimeline(threePartVersions, serverTotalSeconds = null)
        assertNotNull(timeline)
        assertEquals(3, timeline.tracks.size)
        assertEquals(listOf(0.0, 100.0, 300.0), timeline.tracks.map { it.startOffsetSeconds })
        assertEquals(listOf(100.0, 200.0, 150.0), timeline.tracks.map { it.durationSeconds })
        assertEquals(listOf(10, 11, 12), timeline.tracks.map { it.fileId })
        assertFalse(timeline.isSingle)
    }

    @Test
    fun `track index at boundaries mid-part and past end`() {
        val timeline = buildAudiobookTimeline(threePartVersions, serverTotalSeconds = null)!!
        // Exactly at a part start belongs to that part (start-inclusive).
        assertEquals(0, timeline.trackIndexAt(0.0))
        assertEquals(1, timeline.trackIndexAt(100.0))
        assertEquals(2, timeline.trackIndexAt(300.0))
        // Mid-part.
        assertEquals(0, timeline.trackIndexAt(50.0))
        assertEquals(1, timeline.trackIndexAt(250.0))
        assertEquals(2, timeline.trackIndexAt(400.0))
        // Just before a boundary stays in the earlier part (end-exclusive).
        assertEquals(0, timeline.trackIndexAt(99.999))
        // Past the end → last track.
        assertEquals(2, timeline.trackIndexAt(9999.0))
        // Negative clamps to the first track.
        assertEquals(0, timeline.trackIndexAt(-5.0))
    }

    @Test
    fun `local and global round-trip within a track`() {
        val timeline = buildAudiobookTimeline(threePartVersions, serverTotalSeconds = null)!!
        val track = timeline.tracks[1] // offset 100, duration 200
        // global 250 → local 150 → global 250.
        val local = timeline.localTimeFor(250.0, track)
        assertEquals(150.0, local)
        assertEquals(250.0, timeline.globalTimeFor(local, track))
        // Clamping: global before the track floors local to 0.
        assertEquals(0.0, timeline.localTimeFor(50.0, track))
        // Clamping: local past the duration caps at the track end.
        assertEquals(300.0, timeline.globalTimeFor(999.0, track))
    }

    @Test
    fun `chapters stitch onto the whole-book timeline and sort globally`() {
        val versions = listOf(
            part(
                fileId = 20, duration = 100.0, partIndex = 0,
                chapters = listOf(chapter(0, 0.0, 60.0), chapter(1, 60.0, 100.0)),
            ),
            part(
                fileId = 21, duration = 200.0, partIndex = 1,
                chapters = listOf(chapter(0, 0.0, 90.0), chapter(1, 90.0, 200.0)),
            ),
        )
        val timeline = buildAudiobookTimeline(versions, serverTotalSeconds = null)!!
        assertEquals(4, timeline.chapters.size)

        // Part-2's first chapter (local start 0) sits at part2 offset (100).
        val part2First = timeline.chapters.first { it.trackIndex == 1 && it.startSeconds == 100.0 }
        assertEquals(100.0, part2First.startSeconds)   // part2Offset(100) + localStart(0)
        assertEquals(190.0, part2First.endSeconds)     // part2Offset(100) + localEnd(90)

        // Part-2's second chapter: 100 + 90 = 190 start.
        val part2Second = timeline.chapters.first { it.trackIndex == 1 && it.startSeconds == 190.0 }
        assertEquals(190.0, part2Second.startSeconds)

        // Global sort order (ascending by startSeconds).
        assertEquals(listOf(0.0, 60.0, 100.0, 190.0), timeline.chapters.map { it.startSeconds })
        assertEquals(listOf(0, 1, 2, 3), timeline.chapters.map { it.index })
    }

    @Test
    fun oneVersionPerOrderedPartIsStitchedWhenPartsHaveAlternateFiles() {
        val versions = listOf(
            part(
                fileId = 201,
                duration = 100.0,
                partIndex = 1,
                chapters = listOf(chapter(0, 0.0, 60.0), chapter(1, 60.0, 100.0)),
            ).copy(presentationGroupKey = "lossless"),
            part(
                fileId = 202,
                duration = 100.0,
                partIndex = 1,
                chapters = listOf(chapter(0, 0.0, 55.0), chapter(1, 55.0, 100.0)),
            ).copy(presentationGroupKey = "compressed"),
            part(
                fileId = 203,
                duration = 200.0,
                partIndex = 2,
                chapters = listOf(chapter(0, 0.0, 90.0), chapter(1, 90.0, 200.0)),
            ).copy(presentationGroupKey = "lossless"),
            part(
                fileId = 204,
                duration = 200.0,
                partIndex = 2,
                chapters = listOf(chapter(0, 0.0, 80.0), chapter(1, 80.0, 200.0)),
            ).copy(presentationGroupKey = "compressed"),
        )

        val timeline = buildAudiobookTimeline(
            versions = versions,
            serverTotalSeconds = null,
            preferredFileId = 201,
        )!!

        assertEquals(listOf(201, 203), timeline.tracks.map { it.fileId })
        assertEquals(listOf(0.0, 100.0), timeline.tracks.map { it.startOffsetSeconds })
        assertEquals(listOf(0.0, 60.0, 100.0, 190.0), timeline.chapters.map { it.startSeconds })
        assertEquals(listOf(0, 1, 2, 3), timeline.chapters.map { it.index })
    }

    @Test
    fun fallbackKeepsOnePresentationVariantAcrossAllParts() {
        val versions = listOf(
            part(fileId = 301, duration = 100.0, partIndex = 0)
                .copy(presentationGroupKey = "lossless"),
            part(fileId = 302, duration = 100.0, partIndex = 0)
                .copy(presentationGroupKey = "compressed"),
            // Reverse the alternate order for part two. Selecting firstOrNull
            // independently would combine lossless part one with compressed part two.
            part(fileId = 304, duration = 200.0, partIndex = 1)
                .copy(presentationGroupKey = "compressed"),
            part(fileId = 303, duration = 200.0, partIndex = 1)
                .copy(presentationGroupKey = "lossless"),
        )

        val timeline = buildAudiobookTimeline(versions, serverTotalSeconds = null)!!

        assertEquals(listOf(301, 303), timeline.tracks.map { it.fileId })
    }

    @Test
    fun incompletePreferredVariantFallsBackToACompleteVariant() {
        val versions = listOf(
            part(fileId = 401, duration = 100.0, partIndex = 0)
                .copy(presentationGroupKey = "lossless"),
            part(fileId = 402, duration = 100.0, partIndex = 0)
                .copy(presentationGroupKey = "compressed"),
            part(fileId = 403, duration = 200.0, partIndex = 1)
                .copy(presentationGroupKey = "compressed"),
        )

        val timeline = buildAudiobookTimeline(
            versions = versions,
            serverTotalSeconds = null,
            preferredFileId = 401,
        )!!

        assertEquals(listOf(402, 403), timeline.tracks.map { it.fileId })
    }

    @Test
    fun chapterIndexesFollowGloballySortedChapterOrder() {
        val versions = listOf(
            part(
                fileId = 310,
                duration = 100.0,
                partIndex = 0,
                chapters = listOf(
                    chapter(1, 60.0, 100.0),
                    chapter(0, 0.0, 60.0),
                ),
            ),
        )

        val timeline = buildAudiobookTimeline(versions, serverTotalSeconds = null)!!

        assertEquals(listOf(0.0, 60.0), timeline.chapters.map { it.startSeconds })
        assertEquals(listOf(0, 1), timeline.chapters.map { it.index })
    }

    @Test
    fun `single part book behaves like today`() {
        val versions = listOf(
            part(
                fileId = 30, duration = 300.0, partIndex = 0,
                chapters = listOf(chapter(0, 0.0, 120.0), chapter(1, 120.0, 300.0)),
            ),
        )
        val timeline = buildAudiobookTimeline(versions, serverTotalSeconds = null)!!
        assertTrue(timeline.isSingle)
        assertEquals(1, timeline.tracks.size)
        assertEquals(0.0, timeline.tracks[0].startOffsetSeconds)
        // Offsets are trivial: global chapter starts equal file-local starts.
        assertEquals(listOf(0.0, 120.0), timeline.chapters.map { it.startSeconds })
        assertEquals(300.0, timeline.totalSeconds)
        assertEquals(0, timeline.trackIndexAt(150.0))
    }

    @Test
    fun `zero audio parts returns null`() {
        val versions = listOf(
            // Not an audiobook part, no audio codec, no duration → not a part.
            FileVersion(fileId = 40, codecAudio = null, duration = 0.0),
        )
        assertNull(buildAudiobookTimeline(versions, serverTotalSeconds = null))
        assertNull(buildAudiobookTimeline(emptyList(), serverTotalSeconds = null))
    }

    @Test
    fun `fallback treats non-tagged audio versions as parts`() {
        // No presentation_kind, but an audio codec → counts as a part.
        val versions = listOf(
            part(fileId = 50, duration = 0.0, partIndex = null, kind = null, codecAudio = "mp3",
                chapters = listOf(chapter(0, 0.0, 80.0))),
        )
        val timeline = buildAudiobookTimeline(versions, serverTotalSeconds = null)
        assertNotNull(timeline)
        assertEquals(1, timeline.tracks.size)
    }

    @Test
    fun `part duration falls back to furthest chapter edge when duration is zero`() {
        val versions = listOf(
            part(fileId = 60, duration = 0.0, partIndex = 0,
                chapters = listOf(chapter(0, 0.0, 40.0), chapter(1, 40.0, 130.0))),
            part(fileId = 61, duration = 50.0, partIndex = 1,
                chapters = listOf(chapter(0, 0.0, 50.0))),
        )
        val timeline = buildAudiobookTimeline(versions, serverTotalSeconds = null)!!
        // Part 0 has no container duration → furthest chapter edge (130).
        assertEquals(130.0, timeline.tracks[0].durationSeconds)
        assertEquals(130.0, timeline.tracks[1].startOffsetSeconds)
        assertEquals(180.0, timeline.totalSeconds) // 130 + 50
    }

    @Test
    fun `total is the max of server total and cumulative offset`() {
        // Server reports a larger whole-book total than the stitched parts → use server's.
        val larger = buildAudiobookTimeline(threePartVersions, serverTotalSeconds = 500.0)!!
        assertEquals(500.0, larger.totalSeconds)
        // Server reports a smaller total than the stitched parts → use the cumulative offset.
        val smaller = buildAudiobookTimeline(threePartVersions, serverTotalSeconds = 100.0)!!
        assertEquals(450.0, smaller.totalSeconds)
        // No server total → cumulative offset.
        val none = buildAudiobookTimeline(threePartVersions, serverTotalSeconds = null)!!
        assertEquals(450.0, none.totalSeconds)
    }

    @Test
    fun `zero-duration last part with no chapters is excluded from tracks`() {
        val versions = listOf(
            part(fileId = 80, duration = 100.0, partIndex = 0),
            part(fileId = 81, duration = 200.0, partIndex = 1),
            part(fileId = 82, duration = 0.0, partIndex = 2),
        )
        // Server total larger than the stitched parts → still wins.
        val timeline = buildAudiobookTimeline(versions, serverTotalSeconds = 320.0)!!
        assertEquals(listOf(80, 81), timeline.tracks.map { it.fileId })
        assertEquals(320.0, timeline.totalSeconds)
        // Past-the-end maps to the last REAL part, not the dropped zero part.
        assertEquals(1, timeline.trackIndexAt(9999.0))
        // No server total → cumulative offset of the kept parts.
        val none = buildAudiobookTimeline(versions, serverTotalSeconds = null)!!
        assertEquals(300.0, none.totalSeconds)
    }

    @Test
    fun `zero-duration middle part with no chapters leaves following offsets unaffected`() {
        val versions = listOf(
            part(fileId = 90, duration = 100.0, partIndex = 0),
            part(fileId = 91, duration = 0.0, partIndex = 1),
            part(fileId = 92, duration = 150.0, partIndex = 2),
        )
        val timeline = buildAudiobookTimeline(versions, serverTotalSeconds = null)!!
        assertEquals(listOf(90, 92), timeline.tracks.map { it.fileId })
        // The dropped part contributes nothing, so the third part's offset is
        // exactly the first part's length.
        assertEquals(listOf(0.0, 100.0), timeline.tracks.map { it.startOffsetSeconds })
        assertEquals(250.0, timeline.totalSeconds)
        // Indices stay contiguous so trackIndexAt/lookup math holds.
        assertEquals(listOf(0, 1), timeline.tracks.map { it.index })
        assertEquals(1, timeline.trackIndexAt(120.0))
    }

    @Test
    fun `zero-duration part with chapters keeps its chapter-edge fallback`() {
        val versions = listOf(
            part(
                fileId = 95, duration = 0.0, partIndex = 0,
                chapters = listOf(chapter(0, 0.0, 75.0)),
            ),
            part(fileId = 96, duration = 25.0, partIndex = 1),
        )
        val timeline = buildAudiobookTimeline(versions, serverTotalSeconds = null)!!
        // The chapter-edge fallback gives the part real length → NOT dropped.
        assertEquals(listOf(95, 96), timeline.tracks.map { it.fileId })
        assertEquals(75.0, timeline.tracks[0].durationSeconds)
        assertEquals(75.0, timeline.tracks[1].startOffsetSeconds)
        assertEquals(100.0, timeline.totalSeconds)
    }

    @Test
    fun indexedPartsExcludeUnindexedFallbackCandidates() {
        // Once indexed logical parts exist, unindexed candidates are excluded;
        // indexed parts remain ordered by presentation index then file ID.
        val versions = listOf(
            part(fileId = 73, duration = 10.0, partIndex = null),
            part(fileId = 71, duration = 10.0, partIndex = 1),
            part(fileId = 72, duration = 10.0, partIndex = null),
            part(fileId = 70, duration = 10.0, partIndex = 0),
        )
        val timeline = buildAudiobookTimeline(versions, serverTotalSeconds = null)!!
        assertEquals(listOf(70, 71), timeline.tracks.map { it.fileId })
    }
    @Test
    fun `only a file that is the whole book counts as the whole book`() {
        // A single file, with or without the server's total.
        val single = listOf(part(fileId = 20, duration = 1800.0, partIndex = 0))
        assertTrue(isWholeBookFile(single, 20, serverTotalSeconds = 1800.0))
        assertTrue(isWholeBookFile(single, 20, serverTotalSeconds = null))
        // One part of a book split into parts, even when the other parts have no
        // duration and the timeline would drop them.
        val split = listOf(part(fileId = 10, duration = 100.0, partIndex = 0), part(fileId = 11, duration = 0.0, partIndex = 1))
        assertFalse(isWholeBookFile(split, 10, serverTotalSeconds = null))
        // Untagged parts: the file is far shorter than the book.
        val untagged = listOf(part(fileId = 30, duration = 600.0, kind = null), part(fileId = 31, duration = 600.0, kind = null))
        assertFalse(isWholeBookFile(untagged, 30, serverTotalSeconds = 1200.0))
        // A file the detail doesn't list.
        assertFalse(isWholeBookFile(single, 99, serverTotalSeconds = 1800.0))
    }
}
