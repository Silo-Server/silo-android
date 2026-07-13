package org.siloserver.silo.common.player.seek

/** Optimistic seek state retained while the engine drains pre-seek reports. */
data class PendingSeekPresentation(
    val generation: Long,
    val originPositionMs: Long,
    val targetPositionMs: Long,
    val startedAtElapsedRealtimeMs: Long,
    val expiresAtElapsedRealtimeMs: Long,
)

enum class SeekPresentationCompletion {
    TargetTolerance,
    MidpointCrossed,
    TimedOut,
}

/** Decision for one engine position report. */
sealed interface SeekPositionDecision {
    /** Keep rendering [optimisticPositionMs] instead of a stale engine report. */
    data class Suppress(
        val generation: Long,
        val optimisticPositionMs: Long,
    ) : SeekPositionDecision

    /** Publish [positionMs]. Completion is non-null when this report released a pending guard. */
    data class Publish(
        val positionMs: Long,
        val completedGeneration: Long? = null,
        val completion: SeekPresentationCompletion? = null,
    ) : SeekPositionDecision
}

data class ExpiredSeekPresentation(
    val generation: Long,
    val optimisticPositionMs: Long,
)

/**
 * Prevents stale, pre-seek engine positions from snapping an optimistic
 * playhead back after a seek command.
 *
 * Reports closer to the old origin than the target are suppressed. Once a
 * report reaches the target tolerance or crosses the origin/target midpoint,
 * it is published and the guard clears. A caller-owned timeout can invoke
 * [expire]; [onPositionReport] also performs the same timeout check so a
 * missed timer can never pin presentation indefinitely.
 *
 * Starting a new seek replaces the old generation. Generation-qualified
 * expiry prevents a delayed timer for an older seek from clearing the newest
 * one. Like [QuickSkipAccumulator], this class owns no clock or coroutine.
 */
class PendingSeekPresentationGuard(
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val targetToleranceMs: Long = DEFAULT_TARGET_TOLERANCE_MS,
) {
    init {
        require(timeoutMs >= 0L) { "timeoutMs must be non-negative" }
        require(targetToleranceMs >= 0L) { "targetToleranceMs must be non-negative" }
    }

    var pending: PendingSeekPresentation? = null
        private set

    private var lastGeneration = 0L

    fun begin(
        originPositionMs: Long,
        targetPositionMs: Long,
        nowElapsedRealtimeMs: Long,
    ): PendingSeekPresentation {
        require(originPositionMs >= 0L) { "originPositionMs must be non-negative" }
        require(targetPositionMs >= 0L) { "targetPositionMs must be non-negative" }
        require(nowElapsedRealtimeMs >= 0L) { "nowElapsedRealtimeMs must be non-negative" }

        val next = PendingSeekPresentation(
            generation = nextGeneration(),
            originPositionMs = originPositionMs,
            targetPositionMs = targetPositionMs,
            startedAtElapsedRealtimeMs = nowElapsedRealtimeMs,
            expiresAtElapsedRealtimeMs = saturatedDeadline(nowElapsedRealtimeMs, timeoutMs),
        )
        pending = next
        return next
    }

    fun onPositionReport(
        positionMs: Long,
        nowElapsedRealtimeMs: Long,
    ): SeekPositionDecision {
        require(positionMs >= 0L) { "positionMs must be non-negative" }
        require(nowElapsedRealtimeMs >= 0L) { "nowElapsedRealtimeMs must be non-negative" }

        val active = pending
            ?: return SeekPositionDecision.Publish(positionMs = positionMs)

        if (nowElapsedRealtimeMs >= active.expiresAtElapsedRealtimeMs) {
            pending = null
            return SeekPositionDecision.Publish(
                positionMs = positionMs,
                completedGeneration = active.generation,
                completion = SeekPresentationCompletion.TimedOut,
            )
        }

        val distanceFromOrigin = absoluteDifference(positionMs, active.originPositionMs)
        val distanceFromTarget = absoluteDifference(positionMs, active.targetPositionMs)
        val isOnTargetSideOfMidpoint = distanceFromTarget <= distanceFromOrigin

        if (isOnTargetSideOfMidpoint && distanceFromTarget <= targetToleranceMs) {
            pending = null
            return SeekPositionDecision.Publish(
                positionMs = positionMs,
                completedGeneration = active.generation,
                completion = SeekPresentationCompletion.TargetTolerance,
            )
        }

        if (!isOnTargetSideOfMidpoint) {
            return SeekPositionDecision.Suppress(
                generation = active.generation,
                optimisticPositionMs = active.targetPositionMs,
            )
        }

        pending = null
        return SeekPositionDecision.Publish(
            positionMs = positionMs,
            completedGeneration = active.generation,
            completion = SeekPresentationCompletion.MidpointCrossed,
        )
    }

    /**
     * Clears an expired guard only if [expectedGeneration] is still active.
     * The optimistic UI position is intentionally not changed by expiration;
     * the next engine report is simply allowed through.
     */
    fun expire(
        expectedGeneration: Long,
        nowElapsedRealtimeMs: Long,
    ): ExpiredSeekPresentation? {
        require(nowElapsedRealtimeMs >= 0L) { "nowElapsedRealtimeMs must be non-negative" }
        val active = pending ?: return null
        if (active.generation != expectedGeneration || nowElapsedRealtimeMs < active.expiresAtElapsedRealtimeMs) {
            return null
        }
        pending = null
        return ExpiredSeekPresentation(
            generation = active.generation,
            optimisticPositionMs = active.targetPositionMs,
        )
    }

    fun cancel(expectedGeneration: Long? = null): PendingSeekPresentation? {
        val active = pending ?: return null
        if (expectedGeneration != null && expectedGeneration != active.generation) return null
        pending = null
        return active
    }

    private fun nextGeneration(): Long {
        lastGeneration = if (lastGeneration == Long.MAX_VALUE) 1L else lastGeneration + 1L
        return lastGeneration
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val DEFAULT_TARGET_TOLERANCE_MS = 500L
    }
}

private fun absoluteDifference(first: Long, second: Long): Long =
    if (first >= second) first - second else second - first

private fun saturatedDeadline(nowMs: Long, timeoutMs: Long): Long =
    if (nowMs > Long.MAX_VALUE - timeoutMs) Long.MAX_VALUE else nowMs + timeoutMs
