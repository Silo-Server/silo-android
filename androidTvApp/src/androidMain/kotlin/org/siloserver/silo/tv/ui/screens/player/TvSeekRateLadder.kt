package org.siloserver.silo.tv.ui.screens.player

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Speeds for hold-to-seek, and how a sustained press climbs through them.
 *
 * The rate a viewer sees on the chip is a multiple of real time, and this is
 * the only place that decides what that multiple means. It previously did not
 * mean anything: the scrubber advanced `2.0 * rate` seconds on a 100ms tick, so
 * a chip reading "8×" moved at 160× real time. Every number on screen was a
 * twentieth of the truth, which is why the control felt ungovernable — you
 * aimed with the number and the number was wrong.
 *
 * [SECONDS_PER_TICK] is what makes the label honest: rate × tick seconds per
 * tick is exactly rate × real time.
 *
 * A single fixed ceiling cannot serve both ends of this control. Nudging past
 * an intro wants single digits; reaching the end of a three-hour film at 32×
 * takes five and a half minutes of holding, which is not a seek, it is a
 * hostage situation. So the top of the ladder is derived from the runtime
 * instead of being a constant: [maxRateFor] targets [TRAVERSE_TARGET_SECONDS]
 * to cross the whole item, so "hold until it gets there" costs about the same
 * however long the thing is.
 */
internal object TvSeekRateLadder {

    /** Auto-seek tick cadence. Content advanced per tick is rate × this. */
    const val TICK_MILLIS = 100L
    private const val SECONDS_PER_TICK = TICK_MILLIS / 1000.0

    /**
     * Slowest speed. A press has to visibly move, so 1× — which scans at
     * exactly playback speed — is not a useful first step.
     */
    const val BASE_RATE = 2

    /**
     * Steady-state crossing target: the ceiling is derived so that holding at
     * it crosses the item in about this long.
     *
     * NOT the end-to-end figure. A hold ramps up to the ceiling rather than
     * starting there, and the early rungs cover almost nothing, so the real
     * cost runs ~10.5s for a 22-minute episode to ~17.8s for a three-hour
     * film. [traverseSeconds] computes the honest number; that spread is the
     * thing being kept small, not the absolute value.
     */
    const val TRAVERSE_TARGET_SECONDS = 10.0

    /**
     * Floor for the derived ceiling, so short content still gets a fast top
     * gear, and cap, so a very long item does not produce a rate whose single
     * tick skips minutes.
     */
    const val MIN_TOP_RATE = 32
    const val MAX_TOP_RATE = 1024

    /**
     * The fastest aimable speed. Above this a hold is travelling rather than
     * aiming, which is fine — but it should be reached deliberately, by
     * continuing to hold, not stumbled into in the first second.
     */
    const val AIMABLE_MAX_RATE = 16

    /** Doubling ladder; the reachable top is bounded by [maxRateFor]. */
    val rates: List<Int> = listOf(2, 4, 8, 16, 32, 64, 128, 256, 512, 1024)

    /**
     * Cadence of the sustained ramp. The ramp keeps doubling on this beat until
     * it reaches the item's ceiling, rather than walking a fixed number of
     * steps — so a three-hour film goes on accelerating past the point where a
     * twenty-minute episode has already topped out, which is the whole reason
     * the ceiling is derived from runtime.
     */
    const val RAMP_STEP_MILLIS = 900L

    /** Number of doublings needed to reach [durationSeconds]'s ceiling. */
    fun rampSteps(durationSeconds: Double): Int {
        val ceiling = maxRateFor(durationSeconds)
        var rate = BASE_RATE
        var steps = 0
        while (rate < ceiling) {
            rate *= 2
            steps++
        }
        return steps
    }

    /**
     * Wall-clock seconds a sustained hold needs to cross an item of
     * [durationSeconds] — INCLUDING the ramp, which is the part
     * [TRAVERSE_TARGET_SECONDS] does not describe.
     *
     * The ceiling is derived so the STEADY-STATE crossing is about
     * [TRAVERSE_TARGET_SECONDS], but a hold does not start at the ceiling: it
     * doubles every [RAMP_STEP_MILLIS] to get there, and those early rungs
     * cover very little. A three-hour item spends 8.1s ramping and covers only
     * ~920s of it, so the honest end-to-end figure is ~17.8s, not ~10.5s.
     *
     * Exposed so the tests can assert what a viewer actually experiences
     * rather than re-deriving `duration / topRate`, which is the arithmetic the
     * implementation does NOT perform.
     */
    fun traverseSeconds(durationSeconds: Double): Double {
        if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return 0.0
        val ceiling = maxRateFor(durationSeconds)
        val rampStepSeconds = RAMP_STEP_MILLIS / 1000.0
        var covered = 0.0
        var elapsed = 0.0
        var rate = BASE_RATE
        while (rate < ceiling && covered < durationSeconds) {
            covered += rate * rampStepSeconds
            elapsed += rampStepSeconds
            rate *= 2
        }
        if (covered >= durationSeconds) return elapsed
        return elapsed + (durationSeconds - covered) / ceiling
    }

    /** Content seconds to advance for one tick at [rate]. */
    fun tickSeconds(rate: Int): Double = rate * SECONDS_PER_TICK

    /**
     * Fastest rate offered for an item of [durationSeconds].
     *
     * Derived so a sustained hold crosses the item in about
     * [TRAVERSE_TARGET_SECONDS], then rounded up to the next ladder rung so the
     * chip still shows a familiar number. Unknown or nonsensical durations fall
     * back to [MIN_TOP_RATE] rather than guessing.
     */
    fun maxRateFor(durationSeconds: Double): Int {
        if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return MIN_TOP_RATE
        val needed = ceil(durationSeconds / TRAVERSE_TARGET_SECONDS).toInt()
        val bounded = min(max(needed, MIN_TOP_RATE), MAX_TOP_RATE)
        return rates.firstOrNull { it >= bounded } ?: MAX_TOP_RATE
    }

    /**
     * The rate a sustained hold reaches at ramp [step] (0-based) in
     * [direction], for an item of [durationSeconds].
     *
     * Doubles from [BASE_RATE] and stops at whatever that item's ceiling is, so
     * a long film keeps accelerating past the point where a short episode has
     * already topped out. Step 0 is the first change, [RAMP_STEP_MILLIS] in.
     */
    fun sustainedRate(step: Int, direction: Int, durationSeconds: Double): Int {
        val sign = if (direction < 0) -1 else 1
        val ceiling = maxRateFor(durationSeconds)
        var rate = BASE_RATE
        repeat(step + 1) { rate = min(rate * 2, ceiling) }
        return rate * sign
    }

    /**
     * Neighbouring rate after a repeat-press bump, clamped to this item's range.
     *
     * [delta] is a direction along the signed ladder as the key handlers see it,
     * not "faster": +1 is rightwards (faster forwards, or slower backwards) and
     * -1 is leftwards. A bump therefore never flips direction — it stops at the
     * base rate on the way in.
     */
    fun bumped(current: Int, delta: Int, durationSeconds: Double): Int {
        val ceiling = maxRateFor(durationSeconds)
        val usable = rates.filter { it <= ceiling }
        val magnitude = if (current < 0) -current else current
        val sign = if (current < 0) -1 else 1
        val index = usable.indexOf(magnitude)
        if (index < 0) return current
        val next = usable[(index + delta * sign).coerceIn(0, usable.lastIndex)]
        return next * sign
    }
}
