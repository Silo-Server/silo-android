package org.siloserver.silo.model.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.SharedPlural

class PersonPresentationTest {
    @Test
    fun metadataBadgesFormatDatesAgeAndBirthplace() {
        val person = Person(
            id = 1,
            name = "Andy Weir",
            birthDate = "1972-06-16",
            birthplace = "Davis, California",
        )

        assertEquals(
            listOf(
                UiText.Key(SharedString.PersonBorn, listOf(UiText.CalendarDate(1972, 6, 16))),
                UiText.Plural(SharedPlural.PersonYearsOld, 54),
                UiText.Raw("Davis, California"),
            ),
            personMetadataBadges(person, todayIso = "2026-06-19"),
        )
    }

    @Test
    fun metadataBadgesFormatDeathAge() {
        val person = Person(
            id = 2,
            name = "Example Person",
            birthDate = "1950-01-10",
            deathDate = "2020-01-09",
        )

        assertEquals(
            listOf(
                UiText.Key(SharedString.PersonBorn, listOf(UiText.CalendarDate(1950, 1, 10))),
                UiText.Key(SharedString.PersonDiedAge, listOf(UiText.CalendarDate(2020, 1, 9), UiText.Number(69))),
            ),
            personMetadataBadges(person, todayIso = "2026-06-19"),
        )
    }

    @Test
    fun aDateThatIsNotOnTheCalendarShowsAsWritten() {
        assertEquals(UiText.Raw("1980-02-30"), formattedPersonDate("1980-02-30"))
        assertEquals(UiText.Raw("1980"), formattedPersonDate("1980"))
        assertEquals(UiText.CalendarDate(1980, 2, 29), formattedPersonDate("1980-02-29"))
    }

    @Test
    fun initialsUseFirstTwoNameParts() {
        assertEquals("AW", personInitials("Andy Weir"))
        assertEquals("P", personInitials("Prince"))
        assertEquals("?", personInitials("   "))
    }

    @Test
    fun worksCountLabelDistinguishesLoadedTotalAndMore() {
        assertEquals(UiText.Plural(SharedPlural.PersonTitles, 12), personWorksCountLabel(total = 12, loaded = 12, hasMore = false))
        assertEquals(
            UiText.Plural(SharedPlural.PersonTitlesOf, 120, listOf(UiText.Number(120), UiText.Number(60))),
            personWorksCountLabel(total = 120, loaded = 60, hasMore = true),
        )
        assertEquals(UiText.Plural(SharedPlural.PersonTitlesMore, 60), personWorksCountLabel(total = 0, loaded = 60, hasMore = true))
        assertEquals(null, personWorksCountLabel(total = 0, loaded = 0, hasMore = false))
    }

    @Test
    fun tvFiltersExcludeReading() {
        assertTrue(personWorksFiltersForMobile().any { it.key == "reading" })
        assertFalse(personWorksFiltersForTv().any { it.key == "reading" })
        assertTrue(isReadingMediaType("ebook"))
        assertTrue(isReadingMediaType("comic"))
        assertTrue(isReadingMediaType("manga"))
        assertFalse(isReadingMediaType("audiobook"))
    }
}
