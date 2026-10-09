package org.siloserver.silo.util

/**
 * Minimal RFC3339 / RFC3339Nano timestamp parsing for the Watch Together sync
 * layer. The shared module has no kotlinx-datetime dependency (verified: not on
 * the shared classpath), so this is a hand-rolled, dependency-free, fully
 * commonTest-able parser.
 *
 * Handles:
 *  - a trailing `Z` (UTC) or a numeric offset like `+02:00` / `-05:00`;
 *  - fractional seconds of 0..9 digits (truncated/padded to milliseconds);
 *  - returns null on any malformed input rather than throwing.
 *
 * Civil-date arithmetic reuses the proleptic-Gregorian epoch-day algorithm
 * (same family as [IsoDate]).
 */
fun parseRfc3339ToEpochMillis(s: String): Long? {
    if (s.isEmpty()) return null

    // Split date and time on the 'T' (RFC3339 allows 't'/' ' but server emits 'T').
    val tIndex = s.indexOf('T').let { if (it >= 0) it else s.indexOf('t') }
    if (tIndex <= 0 || tIndex == s.length - 1) return null

    val datePart = s.substring(0, tIndex)
    var timePart = s.substring(tIndex + 1)

    // ---- Offset / zone -------------------------------------------------------
    var offsetMinutes = 0
    when {
        timePart.endsWith("Z") || timePart.endsWith("z") -> {
            timePart = timePart.dropLast(1)
        }
        else -> {
            // Find the offset sign that follows the time (not part of fractional).
            val signIndex = timePart.indexOfLast { it == '+' || it == '-' }
            if (signIndex < 0) return null // no zone designator → malformed for our use
            val sign = if (timePart[signIndex] == '-') -1 else 1
            val off = timePart.substring(signIndex + 1) // expect HH:MM
            timePart = timePart.substring(0, signIndex)
            val offParts = off.split(":")
            if (offParts.size != 2 || offParts[0].length != 2 || offParts[1].length != 2) return null
            val offH = offParts[0].toIntOrNull() ?: return null
            val offM = offParts[1].toIntOrNull() ?: return null
            if (offH !in 0..23 || offM !in 0..59) return null
            offsetMinutes = sign * (offH * 60 + offM)
        }
    }

    // ---- Date ---------------------------------------------------------------
    val dateParts = datePart.split("-")
    if (dateParts.size != 3) return null
    val year = dateParts[0].toIntOrNull() ?: return null
    val month = dateParts[1].toIntOrNull() ?: return null
    val day = dateParts[2].toIntOrNull() ?: return null
    if (dateParts[0].length != 4 || dateParts[1].length != 2 || dateParts[2].length != 2) return null
    if (month !in 1..12 || day !in 1..31) return null

    // ---- Time (HH:MM:SS[.fraction]) -----------------------------------------
    val dotIndex = timePart.indexOf('.')
    val hms: String
    var millisOfSecond = 0
    if (dotIndex >= 0) {
        hms = timePart.substring(0, dotIndex)
        val frac = timePart.substring(dotIndex + 1)
        if (frac.isEmpty() || frac.length > 9 || frac.any { !it.isDigit() }) return null
        // Truncate/pad to exactly 3 digits (milliseconds).
        val millisStr = (frac + "000").substring(0, 3)
        millisOfSecond = millisStr.toIntOrNull() ?: return null
    } else {
        hms = timePart
    }

    val timeParts = hms.split(":")
    if (timeParts.size != 3) return null
    val hour = timeParts[0].toIntOrNull() ?: return null
    val minute = timeParts[1].toIntOrNull() ?: return null
    val second = timeParts[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null // 60 = leap second

    val epochDay = daysFromCivil(year, month, day)
    var totalSeconds = epochDay * 86_400L + hour * 3_600L + minute * 60L + second
    totalSeconds -= offsetMinutes * 60L // convert local-with-offset to UTC
    return totalSeconds * 1_000L + millisOfSecond
}

/** Days since 1970-01-01 for a proleptic-Gregorian civil date (Hinnant). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = (y - era * 400).toLong()
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468L
}

/**
 * Formats epoch milliseconds as a UTC RFC3339 timestamp with millisecond
 * precision (`2026-06-12T09:30:00.123Z`), the inverse of
 * [parseRfc3339ToEpochMillis] for the Watch Party clock-sync pings.
 */
fun formatEpochMillisRfc3339(epochMillis: Long): String {
    val epochSecond = epochMillis.floorDiv(1_000L)
    val millis = epochMillis.mod(1_000L).toInt()
    val epochDay = epochSecond.floorDiv(86_400L)
    val secondOfDay = epochSecond.mod(86_400L).toInt()
    val (year, month, day) = civilFromDays(epochDay)
    fun pad(value: Int, width: Int) = value.toString().padStart(width, '0')
    return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}T" +
        "${pad(secondOfDay / 3_600, 2)}:${pad(secondOfDay / 60 % 60, 2)}:${pad(secondOfDay % 60, 2)}." +
        "${pad(millis, 3)}Z"
}

/** Proleptic-Gregorian civil date for days since 1970-01-01 (Hinnant). */
private fun civilFromDays(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719468L
    val era = z.floorDiv(146097L)
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    return Triple(year, month, day)
}
