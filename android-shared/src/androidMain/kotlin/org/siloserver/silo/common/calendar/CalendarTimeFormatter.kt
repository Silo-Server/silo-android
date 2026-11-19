package org.siloserver.silo.common.calendar

import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.model.calendar.CalendarItem
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Calendar air time in the viewer's local timezone, with [locale]'s clock
 * ("8:00 PM" in US English, "20:00" in German).
 * Absolute `air_at` values are authoritative; `air_time` is only a legacy
 * wall-clock fallback and cannot be timezone-converted safely.
 */
fun CalendarItem.localDisplayAirTime(
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = LocaleFormat.locale(),
): String? {
    val formatter = LocaleFormat.dateFormatter("jmm", "h:mm a", locale)

    airAt?.takeIf(String::isNotBlank)?.let { timestamp ->
        runCatching {
            Instant.parse(timestamp).atZone(zoneId).format(formatter)
        }.getOrNull()?.let { return it }
    }

    val wallClock = airTime?.takeIf { it.isNotBlank() && it != "00:00" } ?: return null
    return runCatching {
        LocalTime.parse(wallClock).format(formatter)
    }.getOrElse { wallClock }
}
