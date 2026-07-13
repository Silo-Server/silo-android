package org.siloserver.silo.tv.ui.screens.player

/**
 * A server operation used to recover or re-anchor playback after a source-time seek.
 *
 * The queue intentionally stores the operation rather than a coroutine [kotlinx.coroutines.Job]:
 * once an HTTP request has been committed it is allowed to finish, while a newer seek replaces
 * only the single pending operation. This keeps server-side playback-attempt state coherent and
 * gives the UI latest-target-wins behavior.
 */
internal sealed interface TvSeekRecoveryOperation {
    val targetSourceSeconds: Double

    data class Reanchor(
        override val targetSourceSeconds: Double,
        val reason: String,
        val rollbackAllowed: Boolean = true,
        val diagnostics: Map<String, String> = emptyMap(),
    ) : TvSeekRecoveryOperation

    data class Failure(
        override val targetSourceSeconds: Double,
        val classification: String,
        val notice: String,
        val diagnostics: Map<String, String> = emptyMap(),
    ) : TvSeekRecoveryOperation
}

internal data class TvSeekRecoveryRequest(
    val generation: Long,
    val seekId: Long,
    val operation: TvSeekRecoveryOperation,
)

internal sealed interface TvSeekRecoverySubmission {
    data class Start(val request: TvSeekRecoveryRequest) : TvSeekRecoverySubmission
    data object Queued : TvSeekRecoverySubmission
}

/**
 * Single-flight, latest-pending-wins coordinator for TV seek recovery.
 *
 * All calls are made from the ViewModel's main-thread scope. [reset] invalidates an in-flight
 * response without cancelling its committed HTTP request; when that response eventually returns,
 * [isCurrent] and [complete] make it inert.
 */
internal class TvSeekRecoveryQueue {
    private var generation = 0L
    private var inFlight: TvSeekRecoveryRequest? = null
    private var queued: TvSeekRecoveryRequest? = null

    val hasInFlight: Boolean
        get() = inFlight != null

    fun submit(
        seekId: Long,
        operation: TvSeekRecoveryOperation,
    ): TvSeekRecoverySubmission {
        val request = TvSeekRecoveryRequest(
            generation = generation,
            seekId = seekId,
            operation = operation,
        )
        return if (inFlight == null) {
            inFlight = request
            TvSeekRecoverySubmission.Start(request)
        } else {
            // There is deliberately only one waiting slot: rapid scrubs should re-anchor at the
            // final thumb position, not replay every intermediate target against the server.
            queued = request
            TvSeekRecoverySubmission.Queued
        }
    }

    /**
     * A response may adopt only when it is still the active request, belongs to the current
     * content generation, matches the latest user seek, and has not been superseded in the queue.
     */
    fun isCurrent(request: TvSeekRecoveryRequest, activeSeekId: Long?): Boolean =
        request == inFlight &&
            request.generation == generation &&
            request.seekId == activeSeekId &&
            queued == null

    /** Completes [request] and promotes the newest queued request, if any. */
    fun complete(request: TvSeekRecoveryRequest): TvSeekRecoveryRequest? {
        if (request != inFlight) return null
        val next = queued
        queued = null
        inFlight = next
        return next
    }

    /** Invalidates both active and queued work without cancelling a committed transport request. */
    fun reset() {
        generation = if (generation == Long.MAX_VALUE) 0L else generation + 1L
        inFlight = null
        queued = null
    }
}

/**
 * Qualifies player-mount acknowledgements so a stale Compose effect cannot unblock position
 * mapping for a newer playback timeline.
 */
internal class TvTransportMountGate {
    private var expectedNonce: Long? = null
    private var blockedForPendingLoad = false

    val suppressPositionReports: Boolean
        get() = blockedForPendingLoad || expectedNonce != null

    /** Blocks reports while a load is replacing the currently mounted media. */
    fun beginLoad() {
        blockedForPendingLoad = true
        expectedNonce = null
    }

    /** Blocks reports until the screen has synchronously applied the matching mount. */
    fun expect(nonce: Long) {
        blockedForPendingLoad = false
        expectedNonce = nonce
    }

    /** Returns true only when [nonce] acknowledges the newest expected mount. */
    fun applied(nonce: Long): Boolean {
        if (nonce != expectedNonce) return false
        expectedNonce = null
        return true
    }

    fun reset() {
        blockedForPendingLoad = false
        expectedNonce = null
    }
}
