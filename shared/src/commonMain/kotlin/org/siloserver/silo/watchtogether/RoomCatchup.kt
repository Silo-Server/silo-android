package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.TransportAction
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The room position a member follows: the latest applied command for the
 * current selection revision. Its [positionSeconds] is the room position at
 * [executeAtServerMs]; while [playing] the room advances from there at 1x.
 * Snapshots never replace it.
 */
data class RoomReference(
    val positionSeconds: Double,
    val executeAtServerMs: Long,
    val playing: Boolean,
) {
    /** The room position at server time [serverNowMs]. */
    fun positionAt(serverNowMs: Long): Double {
        val elapsed = if (playing) (serverNowMs - executeAtServerMs).coerceAtLeast(0L) / 1000.0 else 0.0
        return (positionSeconds + elapsed).coerceAtLeast(0.0)
    }
}

/**
 * How a Watch Party client keeps its playhead on the room, shared by every
 * client (web, Apple, Android phone and TV).
 *
 * Drift is `room position - local position`: positive means this member is
 * behind and speeds up. Drift a playing member can absorb is closed with a
 * small temporary rate instead of a seek, because on copy-remux deliveries a
 * seek outside the downloaded window rebuilds the whole stream. The rate is a
 * correction, never a user speed: it is not saved or shown, and it returns to
 * exactly 1x.
 */
object RoomCatchup {
    /** No correction starts below this drift. */
    const val DEADBAND_SECONDS = 0.4

    /** An active rate nudge ends within this drift. */
    const val SETTLE_SECONDS = 0.1

    /** Only above this drift does a playing member seek. */
    const val HARD_SEEK_SECONDS = 5.0

    /** Above this a non-playing member seeks, but only to a target reachable in place. */
    const val PAUSED_SEEK_SECONDS = 1.0

    const val RATE_MIN_DELTA = 0.01
    const val RATE_MAX_DELTA = 0.04

    /** The rate delta before clamping is `|drift| / RATE_GAIN_SECONDS`. */
    const val RATE_GAIN_SECONDS = 25.0

    /** Rates are quantized to this step so the player sees few distinct values. */
    const val RATE_STEP = 0.005

    /** The drift loop waits this long after a command finished. */
    const val LOOP_SETTLE_MS = 1_000L

    /** Drift loop cadence. */
    const val LOOP_INTERVAL_MS = 500L

    /** A rate-incapable mount aims its correction seeks at most this far ahead. */
    const val FALLBACK_MAX_LEAD_SECONDS = 3.0

    /** Correction seeks that keep missing back off up to this interval. */
    const val FALLBACK_MAX_INTERVAL_MS = 16_000L

    private const val STEPS_PER_UNIT = 200 // 1 / RATE_STEP

    /**
     * The nudge rate for [driftSeconds]:
     * `1 + sign(d) * clamp(|d| / 25, 0.01, 0.04)`, quantized to 0.005.
     * Zero or non-finite drift is exactly 1x.
     */
    fun rateFor(driftSeconds: Double): Double {
        if (!driftSeconds.isFinite() || driftSeconds == 0.0) return 1.0
        val delta = (abs(driftSeconds) / RATE_GAIN_SECONDS).coerceIn(RATE_MIN_DELTA, RATE_MAX_DELTA)
        val steps = (delta * STEPS_PER_UNIT).roundToInt()
        val signed = if (driftSeconds > 0.0) steps else -steps
        return (STEPS_PER_UNIT + signed).toDouble() / STEPS_PER_UNIT
    }

    sealed interface Decision {
        /** Seek to the target now, whatever it costs. */
        data object Seek : Decision

        /** Seek in place when the target is buffered; otherwise a budgeted reload. */
        data object SeekOrReload : Decision

        /** Close the drift with a temporary [rate]; no seek. */
        data class Nudge(val rate: Double) : Decision

        /** Leave the position alone. */
        data object None : Decision
    }

    /**
     * How to apply a command at its execution moment. [driftSeconds] is the
     * projected room position minus the local position, measured then.
     *
     * - [memberPlaying]: this player is actually advancing.
     * - [memberIntendsToPlay]: this player wants to play but may not be
     *   advancing yet (buffering, or a seek or mount in flight).
     * - [commandPlays]: the command leaves the room playing.
     * - [targetReachableInPlace]: the player can take the target without
     *   rebuilding its stream.
     * - [rateCapable]: this mount honours a playback rate. A tunneled or
     *   passthrough audio path plays on at 1x, so a nudge there becomes a
     *   seek beyond [PAUSED_SEEK_SECONDS]: in place when the target is
     *   reachable, otherwise through the reload budget.
     */
    fun decideCommand(
        action: TransportAction,
        driftSeconds: Double,
        memberPlaying: Boolean,
        commandPlays: Boolean,
        targetReachableInPlace: Boolean,
        memberIntendsToPlay: Boolean = memberPlaying,
        rateCapable: Boolean = true,
    ): Decision {
        // A seek is a room barrier: everyone lands on the same frame.
        if (action == TransportAction.Seek) return Decision.Seek
        val drift = abs(driftSeconds)
        val inPlaceSeek = drift > PAUSED_SEEK_SECONDS && targetReachableInPlace
        if (action == TransportAction.Play && commandPlays && memberPlaying) {
            return when {
                drift <= DEADBAND_SECONDS -> Decision.None
                drift > HARD_SEEK_SECONDS -> Decision.SeekOrReload
                rateCapable -> Decision.Nudge(rateFor(driftSeconds))
                inPlaceSeek -> Decision.Seek
                // A mount that cannot nudge or seek in place would never
                // converge; the reload budget paces the rebuild.
                drift > PAUSED_SEEK_SECONDS -> Decision.SeekOrReload
                else -> Decision.None
            }
        }
        // Wants to play but is not advancing yet: a large gap still goes
        // through the reload budget, never an unpaced stream rebuild.
        if (action == TransportAction.Play && commandPlays && memberIntendsToPlay) {
            return when {
                drift > HARD_SEEK_SECONDS -> Decision.SeekOrReload
                inPlaceSeek -> Decision.Seek
                else -> Decision.None
            }
        }
        // Pause, or a play this member starts from rest: the loop corrects
        // any residual once playing, so only a large or cheap gap is a seek.
        return if (drift > HARD_SEEK_SECONDS || inPlaceSeek) Decision.Seek else Decision.None
    }

    /** How the next correction seek on a rate-incapable mount is aimed and spaced. */
    data class FallbackStep(val leadSeconds: Double, val intervalMs: Long)

    /**
     * After a correction seek on a rate-incapable mount has landed: the room
     * kept playing while it travelled, so [driftSeconds] measured now is the
     * shortfall the seek left. The next seek aims ahead by the lead so far
     * plus that shortfall (never behind, at most [FALLBACK_MAX_LEAD_SECONDS]).
     * A seek that left more than [PAUSED_SEEK_SECONDS] doubles the interval
     * before the next one, up to [FALLBACK_MAX_INTERVAL_MS]; one that
     * converged resets it to [LOOP_SETTLE_MS].
     */
    fun fallbackAfterSeek(leadSeconds: Double, intervalMs: Long, driftSeconds: Double): FallbackStep {
        if (!driftSeconds.isFinite()) return FallbackStep(leadSeconds, intervalMs)
        val lead = (leadSeconds + driftSeconds).coerceIn(0.0, FALLBACK_MAX_LEAD_SECONDS)
        val interval = if (abs(driftSeconds) <= PAUSED_SEEK_SECONDS) {
            LOOP_SETTLE_MS
        } else {
            (intervalMs * 2).coerceAtMost(FALLBACK_MAX_INTERVAL_MS)
        }
        return FallbackStep(lead, interval)
    }

    /**
     * The continuous drift loop's step for [driftSeconds]: [Decision.Nudge]
     * at a rate, [Decision.None] for exactly 1x, or [Decision.Seek] on a
     * mount that cannot change rate. A running nudge ([nudging]) continues
     * until within [SETTLE_SECONDS]; a new one starts only beyond
     * [DEADBAND_SECONDS]. Without [rateCapable] the loop seeks in place
     * beyond [PAUSED_SEEK_SECONDS] and never rebuilds the stream.
     */
    fun loopDecision(
        driftSeconds: Double,
        nudging: Boolean,
        rateCapable: Boolean = true,
        targetReachableInPlace: Boolean = false,
    ): Decision {
        val drift = abs(driftSeconds)
        if (!rateCapable) {
            return if (drift > PAUSED_SEEK_SECONDS && targetReachableInPlace) Decision.Seek else Decision.None
        }
        val correct = if (nudging) drift > SETTLE_SECONDS else drift > DEADBAND_SECONDS
        return if (correct) Decision.Nudge(rateFor(driftSeconds)) else Decision.None
    }
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

    /** A reload that has not landed within the stale interval; treat it as finished. */
    fun stale(nowMs: Long): Boolean = targetSeconds != null && nowMs - startedAtMs >= staleMs

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
        return offset >= -RoomCatchup.DEADBAND_SECONDS && offset <= LANDED_AHEAD_SECONDS
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

    /**
     * The reload went stale without landing: it is over, and a replacement
     * may start at once. Unlike [abandon] this adds no backoff, so a viewer
     * left behind by a load that never arrived is not held back further.
     * The attempt count stands, so later backoffs keep growing.
     */
    fun expire(nowMs: Long) {
        targetSeconds = null
        loadStarted = false
        nextAllowedAtMs = nowMs
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

    private companion object {
        /** A reload counts as landed while playback is at most this far past its target. */
        const val LANDED_AHEAD_SECONDS = 2.0
    }

    private fun backoffMs(): Long {
        var interval = minIntervalMs
        repeat((attempts - 1).coerceAtLeast(0)) { interval = minOf(maxIntervalMs, interval * 2) }
        return interval
    }
}
