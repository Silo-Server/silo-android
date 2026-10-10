package org.siloserver.silo.model.settings

import kotlin.math.roundToLong

/**
 * The bounds of `player.playback_speed`: 0.25..3.0 in 0.05 steps.
 *
 * The generated [SettingKeys] carry no value bounds yet, so they are restated
 * here and pinned to the vendored manifest by `PlayerSettingsContractTest`.
 * The server enforces the step as well as the range, so a value off the grid is
 * refused like one out of range, and the write is dropped.
 */
object PlaybackSpeedRange {
    const val MIN = 0.25
    const val MAX = 3.0
    const val STEP = 0.05

    /** [value] clamped to the range and rounded to the nearest step. */
    fun normalize(value: Double): Double {
        if (value.isNaN()) return 1.0
        val clamped = value.coerceIn(MIN, MAX)
        // Steps per unit (20), so the result is a whole number of steps
        // divided once, which lands on the nearest double to the step value.
        val stepsPerUnit = (1 / STEP).roundToLong()
        return (clamped * stepsPerUnit).roundToLong().toDouble() / stepsPerUnit
    }
}
