package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.common.player.FollowedSession
import org.siloserver.silo.common.player.PlaybackQualityPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkQualityReplanTest {
    private val main = PlaybackQualityPair("auto", null)
    private val cellular = PlaybackQualityPair("720p", 2_000)

    @Test
    fun `a session follows the network unless it has no server session, is a download or a room, or a rung was picked`() {
        assertEquals(FollowedSession(main), followed())
        assertNull(followed(hasServerSession = false))
        assertNull(followed(playsDownload = true))
        assertNull(followed(inRoom = true))
        assertNull(followed(pickedQuality = "1080p-medium"))
        assertNull(followed(pickedQuality = "1080p"))
        // Auto picked in the player means "follow my settings for this connection".
        assertEquals(FollowedSession(main, autoPicked = true), followed(pickedQuality = "auto"))
    }

    @Test
    fun `a queued network move is dropped once the viewer picks a quality or the session is on its pair`() {
        val move = MobileRecoveryReplan("quality_changed", "Changing quality for this network.", qualityPair = cellular)

        assertTrue(move.stillWanted(FollowedSession(main)))
        assertFalse(move.stillWanted(null), "the viewer picked a rung while it waited")
        assertFalse(move.stillWanted(FollowedSession(cellular)), "already on that pair")
        // A settings pair with a fixed resolution would undo an Auto picked
        // while it waited; the restarted follower moves the cap instead.
        assertFalse(move.stillWanted(FollowedSession(main, autoPicked = true)))
        val autoMove = MobileRecoveryReplan("quality_changed", "n", qualityPair = PlaybackQualityPair("auto", 2_000))
        assertTrue(autoMove.stillWanted(FollowedSession(main, autoPicked = true)))
        // Queued replans the viewer asked for are re-driven as before.
        assertTrue(MobileRecoveryReplan("subtitle_track_changed", "Applying subtitles.").stillWanted(null))
    }

    private fun followed(
        hasServerSession: Boolean = true,
        playsDownload: Boolean = false,
        inRoom: Boolean = false,
        pickedQuality: String? = null,
    ): FollowedSession? = settingsFollowedQuality(
        hasServerSession = hasServerSession,
        playsDownload = playsDownload,
        inRoom = inRoom,
        pickedQuality = pickedQuality,
        active = main,
    )
}
