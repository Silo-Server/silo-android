package org.siloserver.silo.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.siloserver.silo.model.playback.AudioPreferenceUpdate
import org.siloserver.silo.model.playback.SubtitlePreferenceUpdate
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.apiv2.TrackPreferencesV2Api

/**
 * Sends the viewer's track picks to the server's per-series preference, one at
 * a time in pick order, so a later pick can never be overtaken by an earlier
 * one still in flight. Each write carries the auth owner of the playback it
 * was picked in and is dropped once that owner is no longer active, so a pick
 * never lands on another profile or server. A failed write is not retried:
 * the next pick sends a fresh one, and playback never depends on it.
 *
 * Callers pass only deliberate picks. Restored and automatic selections are
 * the server's own answer and must not be written back as the viewer's.
 */
class TrackPreferenceWriter(
    private val api: TrackPreferencesV2Api,
    scope: CoroutineScope,
) {
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best effort: a lost write costs a remembered pick, never playback.
                }
            }
        }
    }

    fun saveAudio(owner: AuthScopeSnapshot, key: String, update: AudioPreferenceUpdate) {
        writes.trySend { api.putAudio(owner, key, update) }
    }

    fun saveSubtitle(owner: AuthScopeSnapshot, key: String, update: SubtitlePreferenceUpdate) {
        writes.trySend { api.putSubtitle(owner, key, update) }
    }
}
