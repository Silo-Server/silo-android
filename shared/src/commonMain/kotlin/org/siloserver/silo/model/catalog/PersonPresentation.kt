package org.siloserver.silo.model.catalog

import org.siloserver.silo.common.i18n.SharedPlural
import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.util.IsoDate

data class PersonWorksFilter(
    val key: String,
    val title: String,
    val serverMediaType: String?,
    val clientTypes: Set<String> = emptySet(),
)

private val readingTypes = setOf(
    "book",
    "books",
    "ebook",
    "ebooks",
    "comic",
    "comics",
    "manga",
    "reading",
)

fun personWorksFiltersForMobile(): List<PersonWorksFilter> = listOf(
    PersonWorksFilter(key = "all", title = "All", serverMediaType = null),
    PersonWorksFilter(key = "movie", title = "Movies", serverMediaType = "movie"),
    PersonWorksFilter(key = "series", title = "TV", serverMediaType = "series"),
    PersonWorksFilter(key = "audiobook", title = "Audiobooks", serverMediaType = "audiobook"),
    PersonWorksFilter(key = "music", title = "Music", serverMediaType = "music"),
    PersonWorksFilter(key = "reading", title = "Reading", serverMediaType = null, clientTypes = readingTypes),
)

fun personWorksFiltersForTv(): List<PersonWorksFilter> = listOf(
    PersonWorksFilter(key = "all", title = "All", serverMediaType = null),
    PersonWorksFilter(key = "movie", title = "Movies", serverMediaType = "movie"),
    PersonWorksFilter(key = "series", title = "TV", serverMediaType = "series"),
    PersonWorksFilter(key = "audiobook", title = "Audiobooks", serverMediaType = "audiobook"),
    PersonWorksFilter(key = "music", title = "Music", serverMediaType = "music"),
)

fun isReadingMediaType(type: String?): Boolean =
    type?.trim()?.lowercase() in readingTypes

fun personInitials(name: String): String {
    val initials = name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
    return initials.ifBlank { "?" }
}

fun personMetadataBadges(person: Person, todayIso: String? = null): List<UiText> {
    val badges = mutableListOf<UiText>()
    formattedPersonDate(person.birthDate)?.let { badges += UiText.Key(SharedString.PersonBorn, listOf(it)) }
    if (person.deathDate.isNullOrBlank()) {
        personAge(person.birthDate, null, todayIso)?.let { badges += UiText.Plural(SharedPlural.PersonYearsOld, it) }
    } else {
        val deathDate = formattedPersonDate(person.deathDate)
        val age = personAge(person.birthDate, person.deathDate, todayIso)
        if (deathDate != null && age != null) {
            badges += UiText.Key(SharedString.PersonDiedAge, listOf(deathDate, UiText.Number(age)))
        } else if (deathDate != null) {
            badges += UiText.Key(SharedString.PersonDied, listOf(deathDate))
        }
    }
    person.birthplace?.trim()?.takeIf { it.isNotBlank() }?.let { badges += UiText.Raw(it) }
    return badges
}

/** An ISO `YYYY-MM-DD` date with the month as a word ("Jan 5, 1980"), or the server's text as it came. */
fun formattedPersonDate(raw: String?): UiText? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val parts = value.split("-")
    if (parts.size != 3) return UiText.Raw(value)
    val year = parts[0].toIntOrNull() ?: return UiText.Raw(value)
    val month = parts[1].toIntOrNull() ?: return UiText.Raw(value)
    val day = parts[2].toIntOrNull() ?: return UiText.Raw(value)
    if (month !in 1..12 || day !in 1..31) return UiText.Raw(value)
    // A day the month doesn't have (Feb 30) can't be formatted; keep the server's text.
    if (day > daysInMonth(year, month)) return UiText.Raw(value)
    return UiText.CalendarDate(year, month, day)
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

fun personAge(birthValue: String?, deathValue: String?, todayIso: String?): Int? {
    val birth = parseIsoDateParts(birthValue) ?: return null
    val end = parseIsoDateParts(deathValue) ?: parseIsoDateParts(todayIso) ?: return null
    var age = end.year - birth.year
    if (end.month < birth.month || (end.month == birth.month && end.day < birth.day)) age -= 1
    return age.takeIf { it >= 0 }
}

fun personWorksCountLabel(total: Int, loaded: Int, hasMore: Boolean): UiText? = when {
    total > 0 && hasMore ->
        UiText.Plural(SharedPlural.PersonTitlesOf, total, listOf(UiText.Number(total), UiText.Number(loaded)))
    total > 0 -> UiText.Plural(SharedPlural.PersonTitles, total)
    loaded > 0 && hasMore -> UiText.Plural(SharedPlural.PersonTitlesMore, loaded)
    loaded > 0 -> UiText.Plural(SharedPlural.PersonTitles, loaded)
    else -> null
}

private data class IsoDateParts(val year: Int, val month: Int, val day: Int)

private fun parseIsoDateParts(value: String?): IsoDateParts? {
    val raw = value?.trim()?.takeIf { it.length >= 10 }?.substring(0, 10) ?: return null
    return runCatching {
        IsoDate.toEpochDay(raw)
        val parts = raw.split("-")
        IsoDateParts(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }.getOrNull()
}
