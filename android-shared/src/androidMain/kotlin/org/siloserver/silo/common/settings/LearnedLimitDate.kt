package org.siloserver.silo.common.settings

import org.siloserver.silo.util.parseRfc3339ToEpochMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * When a learned bandwidth limit was observed, for the Settings row "Limited
 * to 44 Mbps after buffering on Oct 17": [observedAt] (RFC 3339) in
 * [timeZone] with [datePattern], by default the [locale]'s abbreviated month
 * and day. Null when the time is absent or unreadable. The row's rate is
 * [connectionRate]'s.
 */
fun learnedLimitDate(
    observedAt: String?,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
    datePattern: String = shortMonthDayPattern(locale),
): String? = observedAt?.let(::parseRfc3339ToEpochMillis)?.let { millis ->
    SimpleDateFormat(datePattern, locale).apply { this.timeZone = timeZone }.format(Date(millis))
}

/** The [locale]'s pattern for an abbreviated month and a day, "MMM d" in US English. */
fun shortMonthDayPattern(locale: Locale): String =
    android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
