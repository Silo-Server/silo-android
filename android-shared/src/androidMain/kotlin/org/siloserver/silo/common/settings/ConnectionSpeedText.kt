package org.siloserver.silo.common.settings

import kotlin.math.roundToInt

/**
 * The parts of the Settings → Connection speed line, "48 Mbps · measured
 * 3 minutes ago on Wi-Fi". Each app assembles the sentence from its own
 * string resources, as with [LearnedLimitText].
 */
data class ConnectionSpeedText(
    /** The speed in [unit]; see [connectionRate]. */
    val rate: Double,
    val unit: LearnedLimitText.RateUnit,
    /** How long ago it was measured; null when the time is unknown. */
    val age: Age?,
    val transport: Transport,
) {
    data class Age(val unit: AgeUnit, val count: Int)

    enum class AgeUnit { JUST_NOW, MINUTES, HOURS, DAYS }

    /** The network a probe ran on, from the contract's `wifi`, `ethernet`, `cellular` or `other`. */
    enum class Transport { WIFI, ETHERNET, CELLULAR, OTHER }
}

/**
 * [kbps] as Settings shows a rate: megabits per second to one decimal below
 * 10 Mbps and whole from there ("1.4 Mbps", "44 Mbps"), or kbps below
 * 100 kbps. The learned-limit row, the speed line and server figures such as
 * `auto_budget_kbps` all read this way. Whole megabits alone would show a
 * 1400 kbps limit as 1 Mbps.
 */
fun connectionRate(kbps: Int): Pair<Double, LearnedLimitText.RateUnit> {
    val tenths = (kbps / 100.0).roundToInt()
    return when {
        tenths < 1 -> kbps.coerceAtLeast(0).toDouble() to LearnedLimitText.RateUnit.KBPS
        tenths < 100 -> tenths / 10.0 to LearnedLimitText.RateUnit.MBPS
        else -> (kbps / 1000.0).roundToInt().toDouble() to LearnedLimitText.RateUnit.MBPS
    }
}

/**
 * The speed line's parts for a measurement of [kbps] taken at
 * [measuredAtEpochMs] on [transport], as of [nowEpochMs]. Under a minute is
 * "just now"; then whole minutes, hours and days, rounded down. A clock that
 * reads before the measurement counts as just now.
 */
fun connectionSpeedText(
    kbps: Int,
    measuredAtEpochMs: Long?,
    nowEpochMs: Long,
    transport: String?,
): ConnectionSpeedText {
    val (rate, unit) = connectionRate(kbps)
    val age = measuredAtEpochMs?.let { measuredAt ->
        val minutes = ((nowEpochMs - measuredAt).coerceAtLeast(0) / 60_000).toInt()
        when {
            minutes < 1 -> ConnectionSpeedText.Age(ConnectionSpeedText.AgeUnit.JUST_NOW, 0)
            minutes < 60 -> ConnectionSpeedText.Age(ConnectionSpeedText.AgeUnit.MINUTES, minutes)
            minutes < 24 * 60 -> ConnectionSpeedText.Age(ConnectionSpeedText.AgeUnit.HOURS, minutes / 60)
            else -> ConnectionSpeedText.Age(ConnectionSpeedText.AgeUnit.DAYS, minutes / (24 * 60))
        }
    }
    val network = when (transport) {
        "wifi" -> ConnectionSpeedText.Transport.WIFI
        "ethernet" -> ConnectionSpeedText.Transport.ETHERNET
        "cellular" -> ConnectionSpeedText.Transport.CELLULAR
        else -> ConnectionSpeedText.Transport.OTHER
    }
    return ConnectionSpeedText(rate, unit, age, network)
}
