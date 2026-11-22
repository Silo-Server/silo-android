package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.common.player.FollowedSession
import org.siloserver.silo.common.player.PlaybackQualityPair
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkQualityReplanTest {
    private val main = PlaybackQualityPair("auto", null)

    @Test
    fun `a session follows the network unless it has no server session, is a download or a room, or a rung was picked`() {
        assertEquals(FollowedSession("s1", main), followed())
        assertNull(followed(sessionId = null))
        assertNull(followed(playsDownload = true))
        assertNull(followed(inRoom = true))
        assertNull(followed(pickedQuality = "1080p-medium"))
        assertNull(followed(pickedQuality = "1080p"))
        // Auto picked in the player means "follow my settings for this connection".
        assertEquals(FollowedSession("s1", main, autoPicked = true), followed(pickedQuality = "auto"))
    }

    @Test
    fun `a replacement session on the same pair is not the session a move was worked out for`() {
        // The player starts a network move only while it still plays the
        // followed session it was handed.
        assertNotEquals(followed(sessionId = "s1"), followed(sessionId = "s2"))
    }

    @Test
    fun `picks made during a network move are applied after it, in order`() = runTest {
        val picks = PicksDuringNetworkMove()
        val applied = mutableListOf<String>()
        val finish = CompletableDeferred<Unit>()
        val move = launch { finish.await() }
        picks.holdUntilDone(move)

        picks.applyAfterMove { applied += "720p" }
        picks.applyAfterMove { applied += "subtitles" }
        runCurrent()
        assertEquals(emptyList(), applied, "held while the move runs")

        finish.complete(Unit)
        runCurrent()
        assertEquals(listOf("720p", "subtitles"), applied)

        picks.applyAfterMove { applied += "audio" }
        assertEquals(listOf("720p", "subtitles", "audio"), applied, "no move in flight: applied at once")
    }

    @Test
    fun `picks held for a move that is cancelled or cleared are dropped`() = runTest {
        val picks = PicksDuringNetworkMove()
        val applied = mutableListOf<String>()
        val cancelled = launch { CompletableDeferred<Unit>().await() }
        picks.holdUntilDone(cancelled)
        picks.applyAfterMove { applied += "720p" }
        cancelled.cancel()
        runCurrent()

        val cleared = launch { CompletableDeferred<Unit>().await() }
        picks.holdUntilDone(cleared)
        picks.applyAfterMove { applied += "480p" }
        picks.clear()
        cleared.cancel()
        runCurrent()

        assertEquals(emptyList(), applied)
    }

    private fun followed(
        sessionId: String? = "s1",
        playsDownload: Boolean = false,
        inRoom: Boolean = false,
        pickedQuality: String? = null,
    ): FollowedSession? = settingsFollowedQuality(
        sessionId = sessionId,
        playsDownload = playsDownload,
        inRoom = inRoom,
        pickedQuality = pickedQuality,
        active = main,
    )
}
