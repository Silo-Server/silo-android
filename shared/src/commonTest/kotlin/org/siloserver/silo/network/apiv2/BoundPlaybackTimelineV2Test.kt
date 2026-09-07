package org.siloserver.silo.network.apiv2

import kotlin.test.*

class BoundPlaybackTimelineV2Test {
    private fun manifest() = PlaybackManifestV2("installation", "a".repeat(64), "book", "edition", 1000.0,
        listOf(PlaybackManifestPartV2("42", 0.0, 600.0), PlaybackManifestPartV2("43", 600.0, 400.0)))

    @Test fun authoritativePartsChooseInitialResumeBoundaryAndCrossPartSeek() {
        val timeline = manifest().audiobookTimeline(emptyList())
        assertEquals(0, timeline.trackIndexAt(599.0))
        assertEquals(1, timeline.trackIndexAt(600.0))
        val part = timeline.tracks[1]
        assertEquals(30.0, timeline.localTimeFor(630.0, part))
        assertEquals(630.0, timeline.globalTimeFor(30.0, part))
        assertEquals(1000.0, timeline.totalSeconds)
    }
    @Test fun malformedManifestCannotBecomeMappingAuthority() {
        val good = manifest()
        listOf(
            good.copy(timelineId = "A".repeat(64)),
            good.copy(parts = emptyList()),
            good.copy(parts = good.parts + good.parts[0]),
            good.copy(parts = listOf(good.parts[0], good.parts[1].copy(offsetSeconds = 610.0))),
            good.copy(parts = listOf(good.parts[0].copy(durationSeconds = Double.NaN))),
            good.copy(parts = listOf(good.parts[0].copy(fileId = "042"))),
            good.copy(durationSeconds = 1100.0),
        ).forEach { assertFailsWith<IllegalArgumentException> { it.validate() } }
    }
    @Test fun receiptMustBindBothClocksAndTimelineIncludingZero() {
        val first = manifest().select(42)
        assertTrue(first.accepts(PlaybackSampleV2(1, 0.0, true, first.timelineId, 0.0)))
        val second = manifest().select(43)
        assertTrue(second.accepts(PlaybackSampleV2(1, 30.0, false, second.timelineId, 630.0)))
        assertFalse(second.accepts(PlaybackSampleV2(1, 30.0, false, second.timelineId, 30.0)))
        assertFalse(second.accepts(PlaybackSampleV2(1, 30.0, false, "b".repeat(64), 630.0)))
        assertFalse(second.accepts(PlaybackSampleV2(1, 401.0, false, second.timelineId, 1001.0)))
        assertFalse(second.accepts(PlaybackSampleV2(1, 0.0, true, second.timelineId, null)))
    }
}
