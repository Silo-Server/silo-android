package org.siloserver.silo.tv.ui.screens.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #715: Up Next mounts the next episode on the session's ExoPlayer, and
 * the MediaController hears about it one main-looper hop later. A position
 * tick in that gap reported the previous episode's position as the new one's,
 * and the replan that followed remounted the next episode at the old credits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TvMountedPositionReportTest {
    private val screenSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt",
    ).readText()

    @Test
    fun `right after Up Next mounts the next episode only the mounted player reports its start`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        val controllerFuture = MediaController.Builder(context, session.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        val controller = controllerFuture.get()
        try {
            // The outgoing episode, in its credits when Up Next is pressed.
            player.setMediaItem(episode("s2e05"), 1_335_000L)
            shadowOf(Looper.getMainLooper()).idle()

            // Up Next: the backend mounts the next episode at 0 on the session player.
            player.setMediaItem(episode("s2e06"), 0L)

            // The tick that a replan right after the handoff would read from.
            assertEquals(0L, player.currentPosition)
            assertEquals(1_335_000L, controller.currentPosition, "controller should still be stale")

            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0L, controller.currentPosition)
        } finally {
            controller.release()
            session.release()
            player.release()
        }
    }

    @Test
    fun `the position poll reads the player the backend mounts on`() {
        val poll = screenSource
            .substringAfter("private fun TvPlayerCommandEffects(")
            .substringAfter("// Position polling")
            .substringBefore("delay(500)")

        assertTrue(
            "videoBackend?.player" in poll,
            "the poll must read the mounted player so a tick after a mount reports the new item",
        )
        assertFalse(
            "mediaController ?: return@LaunchedEffect" in poll,
            "the MediaController lags a mount on the session player by a looper hop",
        )
    }

    private fun episode(id: String) = MediaItem.Builder()
        .setMediaId(id)
        .setUri("https://example.invalid/$id.mkv")
        .build()
}
