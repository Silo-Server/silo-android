package org.siloserver.silo.network

import io.ktor.util.date.GMTDate
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
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
    // A foreground start can miss WILL_CHANGE while the mutation is already in progress.
    // Acquiring the existing barrier waits for that mutation before the initial collection.
    transitions.withCurrentGeneration(transitions.generation.value) { Unit }
    transitions.transitions.filter { it.affectsCurrentIdentity }
        .onStart { emit(IdentityTransition(IdentityTransitionPhase.DID_CHANGE, IdentityTransitionKind.SIGN_IN, transitions.generation.value)) }
        .collectLatest { if (it.phase == IdentityTransitionPhase.DID_CHANGE) collect() }
}
