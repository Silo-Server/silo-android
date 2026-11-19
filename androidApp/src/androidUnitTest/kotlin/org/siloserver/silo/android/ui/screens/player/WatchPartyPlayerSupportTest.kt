package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.playback.PlaybackAvailableQualityV3
import org.siloserver.silo.model.playback.PlaybackTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartyPlayerSupportTest {
    // A transcode whose player timeline starts at source 600 s.
    private val offsetTimeline = PlaybackTimeline(timelineOffsetSeconds = 600.0)

    @Test
    fun roomSkipsBuildOnTheOriginAndStayInsideTheMedia() {
        // Two quick skips: the second starts from the first one's pending target.
        val first = roomSkipTarget(originSeconds = 100.0, deltaSeconds = 10.0, durationSeconds = 3_000.0)
        assertEquals(110.0, first)
        assertEquals(120.0, roomSkipTarget(originSeconds = first, deltaSeconds = 10.0, durationSeconds = 3_000.0))
        // Never past the end or before the start; an unknown duration does not cap.
        assertEquals(3_000.0, roomSkipTarget(originSeconds = 2_995.0, deltaSeconds = 10.0, durationSeconds = 3_000.0))
        assertEquals(0.0, roomSkipTarget(originSeconds = 4.0, deltaSeconds = -10.0, durationSeconds = 3_000.0))
        assertEquals(4_010.0, roomSkipTarget(originSeconds = 4_000.0, deltaSeconds = 10.0, durationSeconds = 0.0))
    }

    @Test
    fun bufferedTargetMapsSourceTimeOntoThePlayerTimeline() {
        // Player at 10 s (source 610), buffered to player 40 s (source 640).
        assertTrue(roomTargetBufferedLocally(offsetTimeline, 630.0, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0))
        assertTrue(roomTargetBufferedLocally(offsetTimeline, 609.5, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0))
        // Past the buffer, or more than a second behind the playhead, needs new media.
        assertFalse(roomTargetBufferedLocally(offsetTimeline, 641.0, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0))
        assertFalse(roomTargetBufferedLocally(offsetTimeline, 608.0, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0))
        // Before this stream's origin: only a re-anchor reaches it.
        assertFalse(roomTargetBufferedLocally(offsetTimeline, 30.0, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0))
    }

    @Test
    fun aSeekableItemReachesAnyEarlierPointWithANativeSeek() {
        // Player at 10 s (source 610): a member 3 s ahead of a paused room seeks back.
        assertTrue(
            roomTargetBufferedLocally(
                offsetTimeline, 607.0, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0,
                seekableBehindPlayhead = true,
            ),
        )
        assertTrue(
            roomTargetBufferedLocally(
                offsetTimeline, 600.0, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0,
                seekableBehindPlayhead = true,
            ),
        )
        // Ahead of the buffer still needs new media, and before the origin a re-anchor.
        assertFalse(
            roomTargetBufferedLocally(
                offsetTimeline, 641.0, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0,
                seekableBehindPlayhead = true,
            ),
        )
        assertFalse(
            roomTargetBufferedLocally(
                offsetTimeline, 30.0, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0,
                seekableBehindPlayhead = true,
            ),
        )
    }

    @Test
    fun bufferedTargetIsFalseWhenAReanchorWouldBeNeededOrStateIsUnknown() {
        val bounded = PlaybackTimeline(seekWindowStartSeconds = 0.0, seekWindowEndSeconds = 50.0)
        assertFalse(roomTargetBufferedLocally(bounded, 60.0, playerPositionSeconds = 40.0, bufferedPlayerSeconds = 90.0))
        val unknownRestoration = PlaybackTimeline(seekRestoration = "")
        assertFalse(roomTargetBufferedLocally(unknownRestoration, 20.0, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0))
        assertFalse(roomTargetBufferedLocally(null, 20.0, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0))
        assertFalse(roomTargetBufferedLocally(PlaybackTimeline(), Double.NaN, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 40.0))
        // A buffered position behind the playhead is not evidence of anything.
        assertFalse(roomTargetBufferedLocally(PlaybackTimeline(), 10.0, playerPositionSeconds = 10.0, bufferedPlayerSeconds = 9.0))
    }

    @Test
    fun lowerQualityIsOneRungBelowTheCurrentOneOnTheSameLadder() {
        val ladder = listOf(
            PlaybackAvailableQualityV3(label = "original", height = 2160, preservesSource = true),
            PlaybackAvailableQualityV3(label = "1080p", height = 1080),
            PlaybackAvailableQualityV3(label = "720p", height = 720),
            PlaybackAvailableQualityV3(label = "480p", height = 480),
        )
        // The source entry an older server lists is never offered.
        assertEquals("1080p", lowerQualityRung(ladder, selectedLabel = null, sourceHeight = 2160)?.label)
        assertEquals("1080p", lowerQualityRung(ladder, selectedLabel = "auto", sourceHeight = 2160)?.label)
        assertEquals("720p", lowerQualityRung(ladder, selectedLabel = "1080p")?.label)
        assertNull(lowerQualityRung(ladder, selectedLabel = "480p"))
        assertNull(lowerQualityRung(emptyList(), selectedLabel = null))
    }

    @Test
    fun autoWithNoReportedSourceHeightStepsDownFromTheSourceEntry() {
        val ladder = listOf(
            PlaybackAvailableQualityV3(label = "original", height = 2160, preservesSource = true),
            PlaybackAvailableQualityV3(label = "1080p", height = 1080),
            PlaybackAvailableQualityV3(label = "720p", height = 720),
        )
        // The plan reports no source height, but the older server's source
        // entry says the source is 2160p, so the first step down is 1080p.
        assertEquals("1080p", lowerQualityRung(ladder, selectedLabel = "auto")?.label)
        // With one transcode rung under the source, it is still offered.
        assertEquals("720p", lowerQualityRung(ladder.filter { it.height != 1080 }, selectedLabel = "auto")?.label)
    }

    @Test
    fun autoStepsDownFromTheSourceWhenTheServerNoLongerListsIt() {
        val ladder = listOf(
            PlaybackAvailableQualityV3(label = "1080p-high", height = 1080),
            PlaybackAvailableQualityV3(label = "720p-medium", height = 720),
        )
        // A 4K source playing on Auto: the top rung is the first step down.
        assertEquals("1080p-high", lowerQualityRung(ladder, selectedLabel = "auto", sourceHeight = 2160)?.label)
        // A 1080p source: the 1080p rung is not lower, so step to 720p.
        assertEquals("720p-medium", lowerQualityRung(ladder, selectedLabel = "auto", sourceHeight = 1080)?.label)
    }
}
