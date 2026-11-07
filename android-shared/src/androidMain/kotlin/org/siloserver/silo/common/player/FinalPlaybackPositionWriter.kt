package org.siloserver.silo.common.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.port.PlaybackWriteScope

data class FinalPlaybackPosition(
    val scope: PlaybackWriteScope,
    val contentId: String,
    val fileId: Int,
    val positionSeconds: Double,
    val durationSeconds: Double?,
    /**
     * Set when the playback was a sequenced session, which the server tracks:
     * when it exited. Local samples saved for the title before then are stale
     * and are cleared instead of recording this one.
     */
    val serverTrackedAtMs: Long? = null,
    /** When the downloaded playback run this position ends started; see `UserItemStatePort.recordPosition`. */
    val runStartedAtMs: Long? = null,
    /** When the position was taken, so a write that runs late is not sent as newer. */
    val eventTimeMs: Long = System.currentTimeMillis(),
)

/**
 * Application-owned final-position queue. Submission never waits for Room;
 * pending updates for the same identity/content/file collapse to the newest snapshot.
 */
class FinalPlaybackPositionWriter(
    scope: CoroutineScope,
    private val scopeProvider: suspend () -> AuthScopeSnapshot?,
    private val write: suspend (FinalPlaybackPosition) -> Unit,
) {
    private data class Key(
        val scope: PlaybackWriteScope,
        val contentId: String,
        val fileId: Int,
    )

    private val lock = Any()
    private val pending = linkedMapOf<Key, FinalPlaybackPosition>()
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in wakeUp) {
                while (true) {
                    val batch = synchronized(lock) {
                        if (pending.isEmpty()) {
                            emptyList()
                        } else {
                            pending.values.toList().also { pending.clear() }
                        }
                    }
                    if (batch.isEmpty()) break
                    var requeued = false
                    batch.forEach { snapshot ->
                        try {
                            write(snapshot)
                        } catch (cancellation: CancellationException) {
                            // Put it back before unwinding: a cancelled drain must
                            // not be the reason a position disappears.
                            requeue(snapshot)
                            throw cancellation
                        } catch (_: Throwable) {
                            // The batch was removed from `pending` before the
                            // write, so swallowing a failure here dropped the
                            // user's final position outright — no retry, no
                            // re-queue, and this is the durable record that
                            // survives a server-side session reset.
                            requeue(snapshot)
                            requeued = true
                        }
                    }
                    if (requeued) {
                        // Back off rather than spinning the drain loop against a
                        // failing write. A later submit() also wakes us earlier.
                        delay(RETRY_DELAY_MS)
                        wakeUp.trySend(Unit)
                        break
                    }
                }
            }
        }
    }

    suspend fun captureScope(): PlaybackWriteScope? {
        val snapshot = scopeProvider() ?: return null
        val profileId = snapshot.profileId ?: return null
        return PlaybackWriteScope(
            serverId = snapshot.serverId,
            profileId = profileId,
            credentialGenerationId = snapshot.credentialGenerationId,
            identityGeneration = snapshot.identityGeneration,
        )
    }

    /**
     * Returns a failed write to the queue without displacing a newer intent.
     *
     * `putIfAbsent`, not `put`: while the write was in flight the user may have
     * submitted a later position for the same content, and that one is the
     * truth. Losing the older snapshot in that case is correct — losing it
     * because the write failed is not.
     */
    private fun requeue(snapshot: FinalPlaybackPosition) {
        synchronized(lock) {
            pending.putIfAbsent(
                Key(snapshot.scope, snapshot.contentId, snapshot.fileId),
                snapshot,
            )
        }
    }

    fun submit(snapshot: FinalPlaybackPosition): Boolean {
        if (!snapshot.isValid()) return false
        synchronized(lock) {
            pending[Key(snapshot.scope, snapshot.contentId, snapshot.fileId)] = snapshot
        }
        wakeUp.trySend(Unit)
        return true
    }
}

private const val RETRY_DELAY_MS = 5_000L

private fun FinalPlaybackPosition.isValid(): Boolean =
    contentId.isNotBlank() &&
        fileId > 0 &&
        positionSeconds.isFinite() &&
        positionSeconds >= 0.0 &&
        (durationSeconds == null || durationSeconds.isFinite() && durationSeconds > 0.0)
