package org.siloserver.silo.android.ui.util

import org.siloserver.silo.common.i18n.LocaleFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Shared user-facing formatters. Single source of truth so the
 * downloads, player, and detail surfaces can't drift apart.
 */

internal fun formatBytes(bytes: Long, locale: Locale = LocaleFormat.locale()): String {
    if (bytes <= 0) return "${LocaleFormat.integer(0, locale)} B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val index = digitGroups.coerceAtMost(units.size - 1)
    val value = bytes / Math.pow(1024.0, index.toDouble())
    return String.format(locale, "%.1f %s", value, units[index])
}

/**
 * Formats a duration in seconds as H:MM:SS, or M:SS under an hour.
 * Truncates sub-second values; NaN and negatives render as 0:00.
 */
internal fun formatClockTime(seconds: Double, locale: Locale = LocaleFormat.locale()): String {
    val total = if (seconds.isNaN()) 0L else seconds.toLong().coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return LocaleFormat.clock(h, m, s, locale = locale)
}

/** "Jul 8, 2026" caption for date-sorted grids, in [locale]'s order; null when the item has no date. */
internal fun formatCardDate(iso: String?, locale: Locale = LocaleFormat.locale()): String? {
    val datePart = iso?.take(10)?.takeIf { it.length == 10 } ?: return null
    return runCatching {
        LocalDate.parse(datePart).format(LocaleFormat.dateFormatter("yMMMd", "MMM d, yyyy", locale))
    }.getOrNull()
}
