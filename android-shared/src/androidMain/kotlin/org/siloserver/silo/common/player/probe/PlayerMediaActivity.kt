package org.siloserver.silo.common.player.probe

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.siloserver.silo.common.player.ActivePlayerHolder

/**
 * Whether local media is playing or loading, for the bandwidth probe's
 * "never while media plays or starts" rule.
 *
 * Read from the process's one Media3 player ([ActivePlayerHolder]), which
 * plays every local video and audiobook on phone and TV, so one signal covers
 * every player screen. Active while the player loads ([Player.isLoading]: a
 * start, a seek, or a paused player still filling its buffer) or intends to
 * play with media ready or buffering. A paused player with a full buffer
 * leaves the link alone and does not count.
 *
 * [scope] must run on the main thread, where the player lives.
 */
class PlayerMediaActivity(holder: ActivePlayerHolder, scope: CoroutineScope) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    init {
        scope.launch {
            holder.player.collectLatest { player ->
                if (player == null) {
                    _active.value = false
                    return@collectLatest
                }
                val listener = object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        _active.value = player.usesMediaLink()
                    }
                }
                player.addListener(listener)
                _active.value = player.usesMediaLink()
                try {
                    awaitCancellation()
                } finally {
                    player.removeListener(listener)
                }
            }
        }
    }
}

/** Whether this player is moving media over the network, or about to. */
internal fun Player.usesMediaLink(): Boolean =
    isLoading || (playWhenReady && (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY))
