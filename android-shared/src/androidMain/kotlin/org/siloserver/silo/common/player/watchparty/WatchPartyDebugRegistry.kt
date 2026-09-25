package org.siloserver.silo.common.player.watchparty

import org.siloserver.silo.watchtogether.RoomPlaybackBinding

/**
 * The binding of the player screen currently in a Watch Party, for the
 * debug-only harness receiver. Holds no credentials.
 */
object WatchPartyDebugRegistry {
    @Volatile
    var binding: RoomPlaybackBinding? = null
        private set

    fun register(binding: RoomPlaybackBinding) {
        this.binding = binding
    }

    fun unregister(binding: RoomPlaybackBinding) {
        if (this.binding === binding) this.binding = null
    }
}
