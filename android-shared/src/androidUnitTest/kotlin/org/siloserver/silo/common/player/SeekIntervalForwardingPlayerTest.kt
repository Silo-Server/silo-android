package org.siloserver.silo.common.player

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.test.utils.StubPlayer
import org.siloserver.silo.model.settings.SeekDirection
import org.siloserver.silo.model.settings.SeekIntervalPair
import org.siloserver.silo.model.settings.SeekIntervalState
import org.siloserver.silo.model.settings.SeekIntervalSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@UnstableApi
class SeekIntervalForwardingPlayerTest {

    private val supported = SeekIntervalState(
        support = SeekIntervalSupport.Supported,
        videoIntervals = SeekIntervalPair(5, 45),
        audiobookIntervals = SeekIntervalPair(15, 60),
    )

    @Test
    fun resolvePicksTheIntervalsForTheCurrentItemsMediaType() {
        assertEquals(
            SeekIntervalPair(15, 60),
            SeekIntervalForwardingPlayer.resolve(supported, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK),
        )
        assertEquals(SeekIntervalPair(5, 45), SeekIntervalForwardingPlayer.resolve(supported, MediaMetadata.MEDIA_TYPE_MOVIE))
        assertEquals(SeekIntervalPair(5, 45), SeekIntervalForwardingPlayer.resolve(supported, null))
    }

    @Test
    fun resolveDefersToThePlayerOnAnOlderOrUnansweredServer() {
        assertNull(SeekIntervalForwardingPlayer.resolve(supported.copy(support = SeekIntervalSupport.Unsupported), null))
        assertNull(SeekIntervalForwardingPlayer.resolve(supported.copy(support = SeekIntervalSupport.Unknown), null))
    }

    @Test
    fun clampedTargetStaysInsideTheItem() {
        assertEquals(0L, SeekIntervalForwardingPlayer.clampedTarget(4_000L, -10_000L, 60_000L))
        assertEquals(60_000L, SeekIntervalForwardingPlayer.clampedTarget(55_000L, 30_000L, 60_000L))
        assertEquals(85_000L, SeekIntervalForwardingPlayer.clampedTarget(55_000L, 30_000L, C.TIME_UNSET))
    }

    @Test
    fun audiobookSessionSeeksGoToTheRouterOnASupportingServer() {
        val inner = RecordingPlayer(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
        val routed = mutableListOf<SeekDirection>()
        val player = SeekIntervalForwardingPlayer(inner, { routed += it; true }) { supported }

        player.seekBack()
        player.seekForward()

        assertEquals(listOf(SeekDirection.Back, SeekDirection.Forward), routed)
        assertTrue(inner.seeks.isEmpty())
    }

    @Test
    fun anUnhandledAudiobookSeekFallsBackToTheFileLocalSeek() {
        val inner = RecordingPlayer(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
        val player = SeekIntervalForwardingPlayer(inner, { false }) { supported }

        player.seekForward()

        assertEquals(listOf("seekTo:80000"), inner.seeks)
    }

    @Test
    fun videoAndOlderServersNeverReachTheAudiobookRouter() {
        val routed = mutableListOf<SeekDirection>()
        val video = RecordingPlayer(MediaMetadata.MEDIA_TYPE_MOVIE)
        SeekIntervalForwardingPlayer(video, { routed += it; true }) { supported }.seekBack()
        assertEquals(listOf("seekTo:15000"), video.seeks)

        val olderServer = RecordingPlayer(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
        SeekIntervalForwardingPlayer(olderServer, { routed += it; true }) {
            supported.copy(support = SeekIntervalSupport.Unsupported)
        }.seekForward()
        assertEquals(listOf("seekForward"), olderServer.seeks)

        assertTrue(routed.isEmpty())
    }

    /** Minimal player at 20 s of a 5 min item that records the seeks it receives. */
    private class RecordingPlayer(mediaType: Int) : ForwardingPlayer(StubPlayer()) {
        private val item = MediaItem.Builder()
            .setMediaMetadata(MediaMetadata.Builder().setMediaType(mediaType).build())
            .build()
        val seeks = mutableListOf<String>()

        override fun getCurrentMediaItem(): MediaItem = item
        override fun isCommandAvailable(command: Int): Boolean = true
        override fun getCurrentPosition(): Long = 20_000L
        override fun getDuration(): Long = 300_000L
        override fun seekTo(positionMs: Long) {
            seeks += "seekTo:$positionMs"
        }
        override fun seekBack() {
            seeks += "seekBack"
        }
        override fun seekForward() {
            seeks += "seekForward"
        }
    }
}
