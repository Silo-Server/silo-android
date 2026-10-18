package org.siloserver.silo.common.settings

import org.siloserver.silo.util.parseRfc3339ToEpochMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The parts of the Settings row for a learned bandwidth limit, "Limited to
 * 44 Mbps after buffering on Oct 17". Each app assembles the sentence from
 * its own string resources.
 */
data class LearnedLimitText(
    /** The limit in [unit]. */
    val rate: Int,
    val unit: RateUnit,
    /** When the limit was observed, as a short month and day; null when unknown. */
    val date: String?,
) {
    enum class RateUnit { MBPS, KBPS }
}

/**
 * [kbps] in whole megabits per second, rounded to the nearest, or in kbps
 * when that rounds to 0. [observedAt] (RFC 3339) is shown in [timeZone] with
 * [datePattern], by default the [locale]'s abbreviated month and day.
 */
fun learnedLimitText(
    kbps: Int,
    observedAt: String?,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
    datePattern: String = shortMonthDayPattern(locale),
): LearnedLimitText {
    val date = observedAt?.let(::parseRfc3339ToEpochMillis)?.let { millis ->
        SimpleDateFormat(datePattern, locale).apply { this.timeZone = timeZone }.format(Date(millis))
    }
    val mbps = (kbps / 1000.0).roundToInt()
    return if (mbps > 0) {
        LearnedLimitText(mbps, LearnedLimitText.RateUnit.MBPS, date)
    } else {
        LearnedLimitText(kbps.coerceAtLeast(0), LearnedLimitText.RateUnit.KBPS, date)
    }
}

/** The [locale]'s pattern for an abbreviated month and a day, "MMM d" in US English. */
fun shortMonthDayPattern(locale: Locale): String =
    android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
