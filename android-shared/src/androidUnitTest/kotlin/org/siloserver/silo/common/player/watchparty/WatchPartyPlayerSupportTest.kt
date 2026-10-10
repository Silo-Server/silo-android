package org.siloserver.silo.common.player.watchparty

import org.siloserver.silo.watchtogether.RoomPlaybackNotice
import org.siloserver.silo.watchtogether.RoomTransportIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyPlayerSupportTest {
    @Test
    fun `seeks this screen issued stay ours until they expire`() {
        var now = 0L
        val tracker = IssuedSeekTracker { now }
        tracker.note(30_000L)
        assertFalse(tracker.isExternal(30_000L))
        assertFalse(tracker.isExternal(30_400L), "Media3 may land slightly off the target")
        // The session reports the controller's seek a second time; still ours.
        assertFalse(tracker.isExternal(30_400L))
        // A headset skip that lands nowhere near an issued target.
        assertTrue(tracker.isExternal(40_000L))
        // Issued targets expire.
        now = IssuedSeekTracker.EXPIRY_MS + 1
        assertTrue(tracker.isExternal(30_000L))
    }

    @Test
    fun `room notices use plain copy`() {
        assertEquals("Only the host can seek.", watchPartyNoticeText(RoomPlaybackNotice.Denied(RoomTransportIntent.Seek)))
        assertEquals(
            "Only the host can play or pause.",
            watchPartyNoticeText(RoomPlaybackNotice.Denied(RoomTransportIntent.PlayPause)),
        )
        assertEquals("Reconnecting to the party…", watchPartyNoticeText(RoomPlaybackNotice.Reconnecting))
        assertEquals("Couldn't reach the party. Try again.", watchPartyNoticeText(RoomPlaybackNotice.Undelivered))
    }
}
