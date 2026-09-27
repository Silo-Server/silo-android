package org.siloserver.silo.watchtogether

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The room's estimate of server time. [offsetMs] is server wall clock minus
 * local wall clock, or null before a usable sample. [generation] changes
 * whenever the estimate is reset, so scheduled work computed from an older
 * estimate can tell it is stale.
 */
data class RoomClockEstimate(
    val offsetMs: Long?,
    val rttMs: Long?,
    val generation: Long,
)

/**
 * NTP-style offset estimation from room ping/pong samples.
 *
 * The estimate is the offset of the lowest round-trip sample among the most
 * recent [maxSamples] within [sampleLifetimeMs], because queueing delay only
 * ever lengthens a round trip. Samples with a negative or implausibly long
 * round trip are rejected. A jump in the local wall clock relative to the
 * monotonic clock invalidates every sample, since offsets measured before the
 * jump no longer describe this device.
 */
class RoomClockEstimator(
    private val maxSamples: Int = 8,
    private val maxRttMs: Long = 5_000L,
    private val sampleLifetimeMs: Long = 10 * 60_000L,
    private val discontinuityToleranceMs: Long = 1_000L,
) {
    private data class Sample(val offsetMs: Long, val rttMs: Long, val monotonicMs: Long)

    private val samples = ArrayDeque<Sample>()
    private var wallMinusMonotonicMs: Long? = null
    private var generation = 0L

    var estimate: RoomClockEstimate = RoomClockEstimate(offsetMs = null, rttMs = null, generation = 0L)
        private set

    /**
     * Record one sample. [clientReceivedMs] must be stamped when the pong left
     * the socket, and [monotonicNowMs] read at the same moment. Returns true
     * when the sample was accepted.
     */
    fun record(
        clientSentMs: Long,
        serverReceivedMs: Long,
        serverSentMs: Long,
        clientReceivedMs: Long,
        monotonicNowMs: Long,
    ): Boolean {
        checkContinuity(clientReceivedMs, monotonicNowMs)
        val serverHoldMs = serverSentMs - serverReceivedMs
        val rttMs = (clientReceivedMs - clientSentMs) - serverHoldMs
        if (serverHoldMs < 0 || rttMs < 0 || rttMs > maxRttMs) return false
        val offsetMs = (((serverReceivedMs - clientSentMs) + (serverSentMs - clientReceivedMs)) / 2.0).roundToLong()
        samples.addLast(Sample(offsetMs, rttMs, monotonicNowMs))
        while (samples.size > maxSamples) samples.removeFirst()
        samples.removeAll { monotonicNowMs - it.monotonicMs > sampleLifetimeMs }
        publish()
        return true
    }

    /**
     * Detect a local wall-clock jump. Returns true (and resets the estimate)
     * when the wall clock moved relative to the monotonic clock by more than
     * the tolerance since the last check.
     */
    fun checkContinuity(wallNowMs: Long, monotonicNowMs: Long): Boolean {
        val skew = wallNowMs - monotonicNowMs
        val previous = wallMinusMonotonicMs
        wallMinusMonotonicMs = skew
        if (previous == null || abs(skew - previous) <= discontinuityToleranceMs) return false
        reset()
        wallMinusMonotonicMs = skew
        return true
    }

    fun reset() {
        samples.clear()
        wallMinusMonotonicMs = null
        generation++
        publish()
    }

    private fun publish() {
        val best = samples.minByOrNull { it.rttMs }
        estimate = RoomClockEstimate(offsetMs = best?.offsetMs, rttMs = best?.rttMs, generation = generation)
    }
}
