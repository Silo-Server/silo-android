package org.siloserver.silo.common.calendar

import org.siloserver.silo.model.calendar.CalendarItem
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Calendar air time in the viewer's local timezone, always using an AM/PM clock.
 * Absolute `air_at` values are authoritative; `air_time` is only a legacy
 * wall-clock fallback and cannot be timezone-converted safely.
 */
fun CalendarItem.localDisplayAirTime(
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? {
    val formatter = DateTimeFormatter.ofPattern("h:mm a", locale)

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
