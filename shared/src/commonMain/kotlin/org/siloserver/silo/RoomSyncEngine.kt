package org.siloserver.silo

import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.TransportAction
import org.siloserver.silo.model.watchtogether.TransportCommand
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The decision produced for an accepted [TransportCommand].
 *
 * @property localExecuteDelayMs how long (ms, >=0) to wait on the LOCAL clock
 *   before applying — `serverExecuteAt - serverTimeOffset - now`, clamped to 0
 *   (and 0 as a safe fallback when no offset sample exists yet).
 * @property seekToMs absolute position to seek to in ms, or null to leave the
 *   position alone. Set only when the action is `seek` OR the drift between the
 *   command position and the current local position exceeds [DRIFT_THRESHOLD_MS].
 * @property setPlaying desired playing state after applying.
 * @property shouldEmitReady whether to auto-send `ready` (command's
 *   `playback_state == waiting`).
 */
data class SyncDecision(
    val localExecuteDelayMs: Long,
    val seekToMs: Long?,
    val setPlaying: Boolean,
    val shouldEmitReady: Boolean,
)

/**
 * Pure timing brain for synchronized playback. No coroutines, no player —
 * data in, decision out — so it is fully unit-testable.
 *
 *  - Maintains [serverTimeOffsetMs] from ping/pong samples via the NTP midpoint
 *    formula `((server_received − client_sent) + (server_sent − client_received))/2`.
 *  - [decide] turns a [TransportCommand] + current local state + now into a
 *    [SyncDecision], or null when the command is a duplicate or fails gating.
 *
 * Gating (returns null, command ignored):
 *  - duplicate `command_id` (last applied id is held);
 *  - `selection_revision` != current room revision;
 *  - non-blank `session_id` != attached session id (blank session_id, used by
 *    solo/late-join targeted seeks, is always accepted).
 */
class RoomSyncEngine {

    /** Server-minus-local clock offset in ms, or null before the first pong. */
    var serverTimeOffsetMs: Long? = null
        private set

    private var lastCommandId: String? = null

    /** Adopt the room owner's server clock estimate (see `WatchTogetherRepository.clock`). */
    fun setServerTimeOffset(offsetMs: Long?) {
        serverTimeOffsetMs = offsetMs
    }

    /** Record a pong round-trip sample; updates [serverTimeOffsetMs]. */
    fun recordPongSample(
        clientSentMs: Long,
        serverReceivedMs: Long,
        serverSentMs: Long,
        clientReceivedMs: Long,
    ) {
        val offset = ((serverReceivedMs - clientSentMs) + (serverSentMs - clientReceivedMs)).toDouble() / 2.0
        serverTimeOffsetMs = offset.roundToLong()
    }

    /**
     * Decide how/when to apply [command], or null to ignore it.
     *
     * @param serverExecuteAtMs the command's `execute_at` already parsed to a
     *   server-epoch millisecond value (RFC3339Nano parsing lives in the
     *   controller/repository, keeping this pure and platform-free).
     * @param nowLocalMs the current local clock in ms (same basis the controller
     *   schedules against).
     */
    fun decide(
        command: TransportCommand,
        serverExecuteAtMs: Long,
        currentLocalPositionMs: Long,
        currentIsPlaying: Boolean,
        nowLocalMs: Long,
        currentRevision: Long,
        attachedSessionId: String?,
    ): SyncDecision? {
        // Dedupe.
        if (command.commandId == lastCommandId) return null
        // Revision gate.
        if (command.selectionRevision != currentRevision) return null
        // Session gate (blank session_id is a broadcast/targeted command — accept).
        if (command.sessionId.isNotBlank() && command.sessionId != attachedSessionId) return null

        lastCommandId = command.commandId

        val offset = serverTimeOffsetMs
        val localExecuteDelayMs = if (offset == null) {
            0L // safe fallback: apply immediately until clock sync establishes
        } else {
            (serverExecuteAtMs - offset - nowLocalMs).coerceAtLeast(0L)
        }

        val commandPosMs = (command.positionSeconds * 1000.0).roundToLong()
        val driftMs = abs(currentLocalPositionMs - commandPosMs)
        val seekToMs = when {
            command.action == TransportAction.Seek -> commandPosMs
            driftMs > DRIFT_THRESHOLD_MS -> commandPosMs
            else -> null
        }

        val setPlaying = when (command.action) {
            TransportAction.Play -> true
            TransportAction.Pause -> false
            // seek/unknown: honor the broadcast playback_state.
            else -> command.playbackState == RoomPlaybackState.Playing
        }

        val shouldEmitReady = command.playbackState == RoomPlaybackState.Waiting

        return SyncDecision(
            localExecuteDelayMs = localExecuteDelayMs,
            seekToMs = seekToMs,
            setPlaying = setPlaying,
            shouldEmitReady = shouldEmitReady,
        )
    }

    /** Reset on leaving a room / switching selection so dedupe doesn't leak. */
    fun reset() {
        lastCommandId = null
        serverTimeOffsetMs = null
    }

    companion object {
        /** Corrective-seek threshold: only seek for a play/pause if |drift| exceeds this. */
        const val DRIFT_THRESHOLD_MS = 350L
    }
}
