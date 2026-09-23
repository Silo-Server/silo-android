package org.siloserver.silo.common.player

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.model.settings.SeekDirection
import org.siloserver.silo.model.settings.SeekIntervalPair
import org.siloserver.silo.model.settings.SeekIntervalState

/**
 * Relative-seek intervals of the shared ExoPlayer (SiloPlayerFactory's builder
 * increments) and of the PiP skip actions before the revision-9 profile
 * setting. Kept for older servers and until discovery answers.
 */
val LEGACY_PLAYER_SEEK_INTERVALS = SeekIntervalPair(backSeconds = 10, forwardSeconds = 30)

/**
 * Session-facing wrapper that makes relative seeks from the lock screen,
 * notification, headset and Bluetooth buttons (`seekBack`/`seekForward`)
 * follow the profile-wide seek intervals without rebuilding the player.
 *
 * ExoPlayer's own increments are fixed at build time, so they stay as the
 * pre-revision-9 behavior: when [intervals] answers null the call falls
 * through to the wrapped player unchanged.
 *
 * On an audiobook item with profile intervals, [audiobookSeek] gets the seek
 * first so it can cross part files (see [AudiobookSeekRouter]); the
 * file-local seek below is the fallback when nothing handles it.
 */
@UnstableApi
class SeekIntervalForwardingPlayer(
    player: Player,
    private val audiobookSeek: (SeekDirection) -> Boolean = { false },
    private val intervals: () -> SeekIntervalState,
) : ForwardingPlayer(player) {

    private fun mediaType(): Int? = currentMediaItem?.mediaMetadata?.mediaType

    private fun activePair(): SeekIntervalPair? = resolve(intervals(), mediaType())

    override fun getSeekBackIncrement(): Long = activePair()?.backMs ?: super.getSeekBackIncrement()

    override fun getSeekForwardIncrement(): Long =
        activePair()?.forwardMs ?: super.getSeekForwardIncrement()

    override fun seekBack() {
        val pair = activePair() ?: return super.seekBack()
        if (routesToAudiobook() && audiobookSeek(SeekDirection.Back)) return
        if (!isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        seekTo(clampedTarget(currentPosition, -pair.backMs, duration))
    }

    override fun seekForward() {
        val pair = activePair() ?: return super.seekForward()
        if (routesToAudiobook() && audiobookSeek(SeekDirection.Forward)) return
        if (!isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        seekTo(clampedTarget(currentPosition, pair.forwardMs, duration))
    }

    private fun routesToAudiobook(): Boolean = mediaType() == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK

    companion object {
        /**
         * The pair a session seek uses, or null to keep the wrapped player's
         * built-in increments (server without the revision-9 keys, or no
         * answer yet). Audiobook items are tagged
         * [MediaMetadata.MEDIA_TYPE_AUDIO_BOOK]; everything else is video.
         */
        fun resolve(state: SeekIntervalState, mediaType: Int?): SeekIntervalPair? {
            if (!state.isSupported) return null
            return if (mediaType == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK) {
                state.audiobookIntervals
            } else {
                state.videoIntervals
            }
        }

        /** [position] moved by [deltaMs], kept inside `0..duration` when known. */
        fun clampedTarget(position: Long, deltaMs: Long, duration: Long): Long {
            var target = position + deltaMs
            if (duration != C.TIME_UNSET && duration > 0) target = target.coerceAtMost(duration)
            return target.coerceAtLeast(0L)
        }
    }
}
