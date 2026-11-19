package org.siloserver.silo.common.settings

import java.text.NumberFormat
import java.util.Locale

/**
 * The parts of the Settings → Connection speed line, "48 Mbps · measured
 * 3 minutes ago on Wi-Fi", besides its rate ([connectionRate]).
 * [connectionSpeedLabel] assembles the sentence.
 */
data class ConnectionSpeedText(
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
 * [kbps] as Settings shows a rate, rounded down so a lower bound or a budget
 * never overstates the link: kbps below 1 Mbps, megabits per second to one
 * decimal below 10 Mbps, and whole from there ("850 kbps", "1.4 Mbps",
 * "44 Mbps"). The learned-limit row, the speed line and server figures such as
 * `auto_budget_kbps` all read this way.
 */
fun connectionRate(kbps: Int): Pair<Double, RateUnit> = when {
    kbps < 1_000 -> kbps.coerceAtLeast(0).toDouble() to RateUnit.KBPS
    kbps < 10_000 -> (kbps / 100) / 10.0 to RateUnit.MBPS
    else -> (kbps / 1_000).toDouble() to RateUnit.MBPS
}

/** The unit [connectionRate] gives a rate in. */
enum class RateUnit { MBPS, KBPS }

/** [rate] as the [locale] writes it, with at most one decimal and no digit grouping: "1.4", "44", "1000". */
fun formatRate(rate: Double, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 1
        isGroupingUsed = false
    }.format(rate)

/**
 * The speed line's parts for a measurement taken at [measuredAtEpochMs] on
 * [transport], as of [nowEpochMs]. Under a minute is "just now"; then whole
 * minutes, hours and days, rounded down. A clock that reads before the
 * measurement counts as just now.
 */
fun connectionSpeedText(
    measuredAtEpochMs: Long?,
    nowEpochMs: Long,
    transport: String?,
): ConnectionSpeedText {
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
    return ConnectionSpeedText(age, network)
}
