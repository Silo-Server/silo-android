package org.siloserver.silo.network.apiv2

/**
 * Parses an RFC 3339 / ISO 8601 instant as emitted by API v2 (`date-time`
 * members such as `updated_at`) into epoch milliseconds, or null when the
 * text is not a well-formed instant. Fractional seconds beyond milliseconds
 * are truncated. The shared module does not depend on kotlinx-datetime, so
 * this is the one parser v2 models share.
 */
fun parseApiV2Instant(text: String): Long? {
    // YYYY-MM-DDTHH:MM:SS[.fff...](Z|±HH:MM)
    if (text.length < 20) return null
    fun digits(from: Int, to: Int): Int? {
        if (to > text.length) return null
        var value = 0
        for (i in from until to) {
            val c = text[i]
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        return value
    }
    val year = digits(0, 4) ?: return null
    if (text[4] != '-') return null
    val month = digits(5, 7) ?: return null
    if (text[7] != '-') return null
    val day = digits(8, 10) ?: return null
    if (text[10] != 'T' && text[10] != 't') return null
    val hour = digits(11, 13) ?: return null
    if (text[13] != ':') return null
    val minute = digits(14, 16) ?: return null
    if (text[16] != ':') return null
    val second = digits(17, 19) ?: return null
    var index = 19
    var millis = 0
    if (index < text.length && text[index] == '.') {
        index++
        val start = index
        while (index < text.length && text[index] in '0'..'9') index++
        if (index == start) return null
        val fraction = text.substring(start, index).take(3).padEnd(3, '0')
        millis = fraction.toInt()
    }
    if (index >= text.length) return null
    val offsetMinutes: Int = when (text[index]) {
        'Z', 'z' -> {
            if (index + 1 != text.length) return null
            0
        }
        '+', '-' -> {
            val sign = if (text[index] == '+') 1 else -1
            if (index + 6 != text.length || text[index + 3] != ':') return null
            val oh = digits(index + 1, index + 3) ?: return null
            val om = digits(index + 4, index + 6) ?: return null
            sign * (oh * 60 + om)
        }
        else -> return null
    }
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
    val days = daysFromCivil(year, month, day)
    val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
    return seconds * 1_000L + millis
}

/** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}
