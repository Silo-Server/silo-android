package org.siloserver.silo.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * never lands on another profile or server. Each server and profile has its
 * own queue: a request stalled for one never holds up another's picks. A
 * failed write is not retried: the next pick sends a fresh one, and playback
 * never depends on it.
 *
 * Callers pass only deliberate picks. Restored and automatic selections are
 * the server's own answer and must not be written back as the viewer's.
 */
class TrackPreferenceWriter(
    private val api: TrackPreferencesV2Api,
    private val scope: CoroutineScope,
) {
    private val writes = Channel<Pair<TrackPreferenceLane, suspend () -> Unit>>(Channel.UNLIMITED)

    init {
        // Only this coroutine touches the lanes, so they need no lock.
        scope.launch {
            val lanes = mutableMapOf<TrackPreferenceLane, Channel<suspend () -> Unit>>()
            for ((lane, write) in writes) lanes.getOrPut(lane, ::startLane).trySend(write)
        }
    }

    fun saveAudio(owner: AuthScopeSnapshot, key: String, update: AudioPreferenceUpdate) {
        writes.trySend(TrackPreferenceLane(owner) to { api.putAudio(owner, key, update) })
    }

    fun saveSubtitle(owner: AuthScopeSnapshot, key: String, update: SubtitlePreferenceUpdate) {
        writes.trySend(TrackPreferenceLane(owner) to { api.putSubtitle(owner, key, update) })
    }

    private fun startLane() = Channel<suspend () -> Unit>(Channel.UNLIMITED).also { lane ->
        scope.launch { for (write in lane) runContained(write) }
    }
}

/** One server and profile's queue of track preference work. */
internal data class TrackPreferenceLane(val serverId: String, val profileId: String?) {
    constructor(owner: AuthScopeSnapshot) : this(owner.serverId, owner.profileId)
}

/**
 * Runs one queued job so that its failure never stops the queue. A
 * cancellation only ends the queue when the queue itself was cancelled; one
 * raised inside the job (a timeout, a cancelled request) is that job's failure.
 */
internal suspend fun runContained(job: suspend () -> Unit) {
    try {
        job()
    } catch (_: CancellationException) {
        currentCoroutineContext().ensureActive()
    } catch (_: Exception) {
        // Best effort: a lost write costs a remembered pick, never playback.
    }
}
