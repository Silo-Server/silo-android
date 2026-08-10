package org.siloserver.silo.tv.ui.screens.player

/**
 * Speeds for hold-to-seek, and how a sustained press climbs through them.
 *
 * The rate a viewer sees on the chip is a multiple of real time, and this is
 * the only place that decides what that multiple means. It previously did not
 * mean anything: the scrubber advanced `2.0 * rate` seconds on a 100ms tick, so
 * a chip reading "8×" moved at 160× and a 45-minute episode went past in
 * seventeen seconds. Every number on screen was a twentieth of the truth, which
 * is why the control felt ungovernable — you asked for 8 and got 160.
 *
 * [SECONDS_PER_TICK] is what makes the label honest: rate × tick seconds per
 * tick is exactly rate × real time.
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
     * Ceiling for a sustained hold. Beyond this a single tick covers more than
     * a scene, so holding can no longer be aimed; getting further is a
     * deliberate repeat-press rather than something a long hold falls into.
     */
    const val SUSTAINED_MAX_RATE = 16

    /** Ceiling for repeat-press bumps. 32× crosses a 45-minute episode in ~84s. */
    const val MANUAL_MAX_RATE = 32

    /** Selectable speeds, signed for direction. */
    val rates: List<Int> = listOf(-32, -16, -8, -4, -2, 2, 4, 8, 16, 32)

    /**
     * Elapsed hold before each step up. Deliberately slower than the old
     * 1s/2s/3s: three seconds of holding used to land on the top speed, so a
     * press meant to nudge forward a few seconds overshot the scene.
     */
    val holdRampMillis: List<Long> = listOf(1_500L, 3_000L, 5_000L)

    /** Content seconds to advance for one tick at [rate]. */
    fun tickSeconds(rate: Int): Double = rate * SECONDS_PER_TICK

    /**
     * The rate a sustained hold reaches at ramp [step] (0-based), in
     * [direction]. Doubles from [BASE_RATE], capped at [SUSTAINED_MAX_RATE].
     */
    fun sustainedRate(step: Int, direction: Int): Int {
        val sign = if (direction < 0) -1 else 1
        var rate = BASE_RATE
        repeat(step + 1) { rate = (rate * 2).coerceAtMost(SUSTAINED_MAX_RATE) }
        return rate * sign
    }

    /** Neighbouring rate after a repeat-press bump, clamped to the ladder. */
    fun bumped(current: Int, delta: Int): Int {
        val index = rates.indexOf(current)
        if (index < 0) return current
        return rates[(index + delta).coerceIn(0, rates.lastIndex)]
    }
}
