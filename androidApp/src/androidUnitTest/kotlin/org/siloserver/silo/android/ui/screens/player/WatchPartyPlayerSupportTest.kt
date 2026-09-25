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
    fun seeksThisScreenIssuedStayOursUntilTheyExpire() {
        var now = 0L
        val tracker = IssuedSeekTracker { now }
        tracker.note(30_000L)
        assertFalse(tracker.isExternal(30_200L))
        // The session reports the controller's seek a second time; still ours.
        assertFalse(tracker.isExternal(30_200L))
        // A headset skip that lands nowhere near an issued target.
        tracker.note(30_000L)
        assertTrue(tracker.isExternal(40_000L))
        // Issued targets expire.
        now = IssuedSeekTracker.EXPIRY_MS + 1
        assertTrue(tracker.isExternal(30_000L))
    }

    @Test
    fun lowerQualityIsOneRungBelowTheCurrentOneOnTheSameLadder() {
        val ladder = listOf(
            PlaybackAvailableQualityV3(label = "original", height = 2160, preservesSource = true),
            PlaybackAvailableQualityV3(label = "1080p", height = 1080),
            PlaybackAvailableQualityV3(label = "720p", height = 720),
            PlaybackAvailableQualityV3(label = "480p", height = 480),
        )
        assertEquals("1080p", lowerQualityRung(ladder, selectedLabel = null)?.label)
        assertEquals("1080p", lowerQualityRung(ladder, selectedLabel = "auto")?.label)
        assertEquals("720p", lowerQualityRung(ladder, selectedLabel = "1080p")?.label)
        assertNull(lowerQualityRung(ladder, selectedLabel = "480p"))
        assertNull(lowerQualityRung(emptyList(), selectedLabel = null))
    }
}
