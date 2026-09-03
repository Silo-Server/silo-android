package org.siloserver.silo.tv.ui.screens.player

/**
 * Speeds for hold-to-seek and how a sustained press climbs through them.
 *
 * Mirrors the Apple clients exactly (`PlayerViewModel.seekRates`,
 * `TVPlayerScrubber.timelineAutoSeekRates`): a signed ladder of 1×…32×, a
 * 100 ms tick, and [SECONDS_PER_TICK_AT_1X] seconds of content per tick per
 * rate unit. The label is therefore a ladder position, not a literal multiple
 * of real time — "8×" travels 160× real time — but it is the same number, at
 * the same speed, the viewer sees on Apple TV.
 *
 * A previous revision tried to make the label honest (rate × real time) and
 * derived the ceiling from the runtime. That made every rung 20× slower than
 * tvOS, which read as "the speeds are wrong" to anyone who uses both clients.
 */
internal object TvSeekRateLadder {

    /** Auto-seek tick cadence. */
    const val TICK_MILLIS = 100L

    /** Content seconds covered per tick at 1×. Apple's `holdSeekBaseStep`. */
    const val SECONDS_PER_TICK_AT_1X = 2.0

    /** Where a hold starts. */
    const val BASE_RATE = 1

    /** Rate magnitudes, slowest to fastest. */
    val rates: List<Int> = listOf(1, 2, 4, 8, 16, 32)

    /**
     * Rates a sustained hold climbs through after [BASE_RATE], one per
     * [RAMP_STEP_MILLIS]. Apple's `startHoldSeekAutoRamp`: 1 → 2 → 4 → 8, then
     * the viewer taps to go faster. Only the hidden-controls session ramps;
     * the focused scrubber's hold holds its rate (tvOS `beginTimelineAutoSeek`).
     */
    val rampRates: List<Int> = listOf(2, 4, 8)

    /** Cadence of the sustained ramp. */
    const val RAMP_STEP_MILLIS = 1_200L

    /** Content seconds to advance for one full tick at [rate]. */
    fun tickSeconds(rate: Int): Double = rate * SECONDS_PER_TICK_AT_1X

    /**
     * Content seconds covered by [rate] over [elapsedMillis] of wall-clock time.
     *
     * The loops advance by measured elapsed time rather than by counting
     * ticks: `delay` only promises to wait at least this long, and on a box
     * busy decoding 4K a 100 ms tick can land 50 ms late. Counting ticks would
     * make the same rate run slower on a busier box; measuring time keeps the
     * speed the ladder promises.
     */
    fun elapsedSeconds(rate: Int, elapsedMillis: Long): Double =
        tickSeconds(rate) * elapsedMillis.coerceAtLeast(0L) / TICK_MILLIS

    /**
     * Neighbouring rate after a tap while seeking, Apple `adjustHoldSeekRate`:
     * step [delta] along the signed ladder …-32, …, -1, 1, …, 32 and clamp at
     * the ends. Stepping past 1× in the opposite direction reverses, exactly
     * as it does on tvOS.
     */
    fun bumped(current: Int, delta: Int): Int {
        val signed = rates.asReversed().map { -it } + rates
        val index = signed.indexOf(current)
        if (index < 0) return current
        return signed[(index + delta).coerceIn(0, signed.lastIndex)]
    }
}
