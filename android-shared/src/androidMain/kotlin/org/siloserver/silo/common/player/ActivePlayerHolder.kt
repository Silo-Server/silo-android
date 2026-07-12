package org.siloserver.silo.common.player

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder for the active session [Player].
 *
 * Why this exists: the playback UI must bind its video `SurfaceView`'s holder
 * video-surface lifecycle — `setVideoSurfaceView` lets the player observe
 * `surfaceCreated` / `surfaceChanged` / `surfaceDestroyed` and re-assert itself
 * on seek, surface recreate, rotation, and background/resume. A `MediaController`
 * only forwards a one-shot raw `Surface` (a `SurfaceHolder` cannot cross the
 * video after a seek/recreate while audio kept playing. findroid and Wholphin
 * this holder is how Silo does the same while still exposing a `MediaSession` /
 * `MediaController` for system transport, lock-screen, and Cast.
 *
 * [SiloPlaybackService] (same process) publishes the active player here on
 * create / engine-swap / destroy; the UI observes [player] and binds its
 * (controller-less, surface-only) PlayerView to it, re-binding when it changes.
 */
class ActivePlayerHolder {
    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    fun set(player: Player?) {
        _player.value = player
    }
}
