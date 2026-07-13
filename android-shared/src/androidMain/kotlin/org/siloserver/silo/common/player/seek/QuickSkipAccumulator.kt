package org.siloserver.silo.common.player.seek

/** Inclusive movie-timeline bounds for a user seek. */
data class SeekBoundsMs(
    val startPositionMs: Long = 0L,
    val endPositionMs: Long? = null,
) {
    init {
        require(startPositionMs >= 0L) { "startPositionMs must be non-negative" }
        require(endPositionMs == null || endPositionMs >= startPositionMs) {
            "endPositionMs must not precede startPositionMs"
        }
    }

    fun clamp(positionMs: Long): Long =
        positionMs
            .coerceAtLeast(startPositionMs)
            .let { position -> endPositionMs?.let(position::coerceAtMost) ?: position }
}

/**
 * The latest quick-skip preview and the monotonic deadline at which it may be
 * committed. Callers own the timer/coroutine and should pass [generation] back
 * to [QuickSkipAccumulator.commitIfDue], making stale scheduled callbacks safe.
 */
data class PendingQuickSkip(
    val generation: Long,
    val targetPositionMs: Long,
    val commitAtElapsedRealtimeMs: Long,
)

/** A quick-skip target that is ready to be sent to the playback engine. */
data class QuickSkipCommit(
    val generation: Long,
    val targetPositionMs: Long,
)

/**
 * Pure trailing-edge state for rapid quick-skip input.
 *
 * Every added delta is based on the pending preview when one exists, never on
 * a potentially stale engine position. The caller renders [pending] as the
 * optimistic position, schedules its own wake-up for
 * [PendingQuickSkip.commitAtElapsedRealtimeMs], and sends the single value
 * returned by [commitIfDue] or [flush] to the engine.
 *
 * This type deliberately owns no clock, coroutine, or platform object so the
 * same deterministic state machine can drive phone and TV input surfaces.
 */
class QuickSkipAccumulator(
    val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    init {
        require(debounceMs >= 0L) { "debounceMs must be non-negative" }
    }

    var pending: PendingQuickSkip? = null
        private set

    private var lastGeneration = 0L

    fun addSkip(
        deltaMs: Long,
        enginePositionMs: Long,
        bounds: SeekBoundsMs,
        nowElapsedRealtimeMs: Long,
    ): PendingQuickSkip {
        require(enginePositionMs >= 0L) { "enginePositionMs must be non-negative" }
        require(nowElapsedRealtimeMs >= 0L) { "nowElapsedRealtimeMs must be non-negative" }

        val basePositionMs = pending?.targetPositionMs ?: bounds.clamp(enginePositionMs)
        val targetPositionMs = bounds.clamp(saturatedAdd(basePositionMs, deltaMs))
        val next = PendingQuickSkip(
            generation = nextGeneration(),
            targetPositionMs = targetPositionMs,
            commitAtElapsedRealtimeMs = saturatedAdd(nowElapsedRealtimeMs, debounceMs),
        )
        pending = next
        return next
    }

    /**
     * Returns and clears the latest target only when both its generation and
     * trailing-edge deadline still match. An older scheduled callback cannot
     * commit or clear a newer burst.
     */
    fun commitIfDue(
        expectedGeneration: Long,
        nowElapsedRealtimeMs: Long,
    ): QuickSkipCommit? {
        require(nowElapsedRealtimeMs >= 0L) { "nowElapsedRealtimeMs must be non-negative" }
        val active = pending ?: return null
        if (active.generation != expectedGeneration || nowElapsedRealtimeMs < active.commitAtElapsedRealtimeMs) {
            return null
        }
        pending = null
        return active.toCommit()
    }

    /** Immediately returns and clears the latest pending target, if any. */
    fun flush(): QuickSkipCommit? {
        val active = pending ?: return null
        pending = null
        return active.toCommit()
    }

    fun cancel() {
        pending = null
    }

    private fun nextGeneration(): Long {
        lastGeneration = if (lastGeneration == Long.MAX_VALUE) 1L else lastGeneration + 1L
        return lastGeneration
    }

    private fun PendingQuickSkip.toCommit() = QuickSkipCommit(
        generation = generation,
        targetPositionMs = targetPositionMs,
    )

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 200L
    }
}

private fun saturatedAdd(value: Long, delta: Long): Long = when {
    delta > 0L && value > Long.MAX_VALUE - delta -> Long.MAX_VALUE
    delta < 0L && value < Long.MIN_VALUE - delta -> Long.MIN_VALUE
    else -> value + delta
}
