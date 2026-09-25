package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.TransportAction
import kotlin.math.abs

/**
 * How a Watch Party client applies a room command's position. Ported from the
 * web reference client (`web/src/player/utils/roomSyncCatchup.ts`).
 *
 * Small drift is absorbed with a temporary playback-rate nudge instead of a
 * seek, because on copy-remux deliveries a seek outside the downloaded window
 * rebuilds the whole stream. Every position the player can reach without new
 * media stays a seek. The rate is a correction, never a user speed: it is not
 * saved or shown, and it returns to exactly 1x.
 */
object RoomCatchup {
    /** Drift within this band converges by rate instead of rebuilding the stream. */
    const val BAND_SECONDS = 2.0

    /** Playback already this close to the room needs no correction. */
    const val DEADBAND_SECONDS = 0.35
    const val MIN_RATE = 0.9
    const val MAX_RATE = 1.25

    /** A 1 s deficit plays at 1.125x and converges in about 8 s; the band edge reaches the cap. */
    private const val DIVISOR_SECONDS = 8.0

    /** Rate for [driftSeconds] (room minus local), clamped to the correction bounds. */
    fun rateFor(driftSeconds: Double): Double =
        (1.0 + driftSeconds / DIVISOR_SECONDS).coerceIn(MIN_RATE, MAX_RATE)

    sealed interface Decision {
        data object Seek : Decision
        data class Rate(val rate: Double) : Decision
        data object None : Decision
    }

    /**
     * Decide how to reach [targetSeconds] from [localSeconds] for a command of
     * [action]. [targetReachableLocally] is true when the player can take the
     * target without loading new media.
     */
    fun decide(
        action: TransportAction,
        targetSeconds: Double,
        localSeconds: Double,
        targetReachableLocally: Boolean,
    ): Decision {
        if (action == TransportAction.Seek) return Decision.Seek
        val drift = targetSeconds - localSeconds
        if (abs(drift) <= DEADBAND_SECONDS) return Decision.None
        if (targetReachableLocally) return Decision.Seek
        // Pausing is cheap and the next Play realigns; never rebuild a stream
        // just to park a paused member at the anchor.
        if (action == TransportAction.Pause) return Decision.None
        if (abs(drift) > BAND_SECONDS) return Decision.Seek
        return Decision.Rate(rateFor(drift))
    }

    /** The room position at [nowMs] for a target that starts advancing at 1x from [executeAtMs]. */
    fun expectedPosition(targetSeconds: Double, executeAtMs: Long, nowMs: Long): Double =
        (targetSeconds + (nowMs - executeAtMs).coerceAtLeast(0L) / 1000.0).coerceAtLeast(0.0)

    fun converged(targetSeconds: Double, executeAtMs: Long, localSeconds: Double, nowMs: Long): Boolean =
        abs(expectedPosition(targetSeconds, executeAtMs, nowMs) - localSeconds) <= DEADBAND_SECONDS
}

/**
 * Correction-driven media reloads. A Play correction whose target is not
 * buffered loads new media, so the viewer lands late by the load time. Without
 * a budget a viewer on a slow link chases the advancing room forever. Only one
 * reload runs at a time; the next waits 10 s after the previous one started
 * playing, doubling to 60 s until the viewer converges, and each aims ahead by
 * the previous load time, at most 10 s and never past the end of the media.
 * Explicit room seeks never go through this budget.
 */
class RoomReloadBudget(
    private val minIntervalMs: Long = 10_000L,
    private val maxIntervalMs: Long = 60_000L,
    private val staleMs: Long = 30_000L,
    private val maxLeadSeconds: Double = 10.0,
) {
    var targetSeconds: Double? = null
        private set
    private var loadStarted = false
    private var startedAtMs = 0L
    private var nextAllowedAtMs = 0L
    private var attempts = 0
    private var leadSeconds = 0.0

    val inFlight: Boolean get() = targetSeconds != null

    fun allowed(nowMs: Long): Boolean =
        if (targetSeconds != null) nowMs - startedAtMs >= staleMs else nowMs >= nextAllowedAtMs

    /** Record a reload toward [roomSeconds]; returns where to aim it. */
    fun begin(roomSeconds: Double, nowMs: Long, durationSeconds: Double? = null): Double {
        var target = roomSeconds + leadSeconds
        if (durationSeconds != null && durationSeconds > 0.0) {
            target = minOf(target, maxOf(roomSeconds, durationSeconds))
        }
        targetSeconds = target
        loadStarted = false
        startedAtMs = nowMs
        attempts++
        nextAllowedAtMs = nowMs + staleMs
        return target
    }

    /** The reload's own seek or re-anchor was taken. */
    fun noteLoading() {
        if (targetSeconds != null) loadStarted = true
    }

    /** Whether playback at [localSeconds] is the in-flight reload playing. */
    fun landed(localSeconds: Double): Boolean {
        val target = targetSeconds ?: return false
        if (!loadStarted) return false
        val offset = localSeconds - target
        return offset >= -RoomCatchup.DEADBAND_SECONDS && offset <= RoomCatchup.BAND_SECONDS
    }

    /** The reload is playing: remember its load time and space the next one. */
    fun land(nowMs: Long) {
        if (targetSeconds == null) return
        leadSeconds = ((nowMs - startedAtMs) / 1000.0).coerceIn(0.0, maxLeadSeconds)
        targetSeconds = null
        nextAllowedAtMs = nowMs + backoffMs()
    }

    /** The reload was refused or superseded; space the next one. */
    fun abandon(nowMs: Long) {
        targetSeconds = null
        nextAllowedAtMs = nowMs + backoffMs()
    }

    /** The viewer reached the room: the next drift starts a fresh backoff. */
    fun settle() {
        attempts = 0
        nextAllowedAtMs = 0L
    }

    /** A quality change starts pacing over for the new stream. */
    fun reset() {
        targetSeconds = null
        loadStarted = false
        attempts = 0
        nextAllowedAtMs = 0L
        leadSeconds = 0.0
    }

    private fun backoffMs(): Long {
        var interval = minIntervalMs
        repeat((attempts - 1).coerceAtLeast(0)) { interval = minOf(maxIntervalMs, interval * 2) }
        return interval
    }
}
