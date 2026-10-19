package org.siloserver.silo.network

import io.ktor.util.date.GMTDate
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Outcome of one generic-events attempt, measured from its first accepted subscription ACK. */
data class GenericEventsClose(
    val acceptedForMs: Long? = null,
    val retryAfterMs: Long? = null,
    val accessChanged: Boolean = false,
)

/** One ladder per foreground consumer/identity. Rooms and playback use their own recovery. */
class GenericEventsBackoff(private val random: () -> Double = { Random.nextDouble() }) {
    private var failures = 0

    fun disconnected(close: GenericEventsClose = GenericEventsClose()): Long {
        // Deliberate authority refresh does not advance or reset the failure ladder.
        if (close.accessChanged) return 0
        val base = if ((close.acceptedForMs ?: -1) >= 30_000) {
            failures = 0
            1_000L
        } else {
            val floor = FAILURE_FLOORS[failures]
            failures = (failures + 1).coerceAtMost(FAILURE_FLOORS.lastIndex)
            floor
        }
        val floor = maxOf(base, close.retryAfterMs ?: 0)
        val sample = random().takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val jitter = (floor.toDouble() * 0.5 * sample).toLong()
        return floor + jitter.coerceAtMost(Long.MAX_VALUE - floor)
    }

    private companion object {
        val FAILURE_FLOORS = listOf(5_000L, 10_000L, 20_000L, 30_000L)
    }
}

/** HTTP delta-seconds and HTTP-date are both readable by native clients. */
internal fun genericEventsRetryAfter(value: String?, nowMs: Long = GMTDate().timestamp): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.all { it in '0'..'9' }) {
        val seconds = text.toLongOrNull() ?: return null
        return seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
    }
    return runCatching { (text.fromHttpToGmtDate().timestamp - nowMs).coerceAtLeast(0) }.getOrNull()
}

/** Cancel the old collector and its single delay on replacement; resume after identity mutation. */
internal suspend fun genericEventsForIdentity(
    transitions: IdentityTransitionBarrier?,
    collect: suspend () -> Unit,
) {
    if (transitions == null) {
        collect()
        return
    }
    coroutineScope {
        var collector: Job? = null
        var startedGeneration: Long? = null

        suspend fun start() {
            while (true) {
                val generation = transitions.generation.value
                if (transitions.withCurrentGeneration(generation) {
                    // Launch startup under the barrier, never holding it for the collector's lifetime.
                    startedGeneration = generation
                    collector = launch(start = CoroutineStart.UNDISPATCHED) { collect() }
                } != null) return
                // A mutation won the lock. Recheck readiness for its resulting generation.
            }
        }

        try {
            // onSubscription registers observation before waiting for initial readiness.
            transitions.transitions.onSubscription { start() }
                .collect { transition ->
                    if (!transition.affectsCurrentIdentity) return@collect
                    // Startup may have waited through these already-completed transitions.
                    if (startedGeneration?.let { transition.generation <= it } == true) return@collect
                    collector?.cancelAndJoin()
                    collector = null
                    if (transition.phase == IdentityTransitionPhase.DID_CHANGE) start()
                }
        } finally {
            collector?.cancel()
        }
    }
}
