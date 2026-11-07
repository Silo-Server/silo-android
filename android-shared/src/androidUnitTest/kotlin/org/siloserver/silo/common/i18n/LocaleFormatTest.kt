package org.siloserver.silo.common.i18n

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocaleFormatTest {
    private val defaultBefore = Locale.getDefault()

    @After fun restoreDefault() = Locale.setDefault(defaultBefore)

    private val date = LocalDate.of(2026, 7, 8)
    private val time = LocalTime.of(20, 5)
    private val de = Locale.GERMAN
    private val ar = Locale.forLanguageTag("ar")
    private val ja = Locale.JAPANESE

    private fun date(skeleton: String, fallback: String, locale: Locale) =
        date.format(LocaleFormat.dateFormatter(skeleton, fallback, locale))

    /** Each skeleton prints exactly what the fixed pattern it replaces printed in US English. */
    @Test fun usEnglishMatchesTheOldPatterns() {
        val cases = listOf(
            "yMMMd" to "MMM d, yyyy",
            "MMMd" to "MMM d",
            "yMMMM" to "MMMM yyyy",
            "MMMMEEEEd" to "EEEE, MMMM d",
        )
        for (year in listOf(2026, 999, 9)) {
            val d = LocalDate.of(year, 7, 8)
            for ((skeleton, old) in cases) {
                assertEquals(
                    d.format(DateTimeFormatter.ofPattern(old, Locale.US)),
                    d.format(LocaleFormat.dateFormatter(skeleton, old, Locale.US)),
                    "$skeleton in $year",
                )
            }
        }
        for (t in listOf(time, LocalTime.of(0, 0), LocalTime.of(9, 30), LocalTime.of(12, 0))) {
            assertEquals(
                t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)),
                t.format(LocaleFormat.dateFormatter("jmm", "h:mm a", Locale.US)),
            )
        }
    }

    @Test fun unpaddedYearsStayUnpadded() {
        val d = LocalDate.of(9, 7, 8)
        assertEquals("Jul 8, 9", d.format(LocaleFormat.dateFormatter("yMMMd", "MMM d, y", Locale.US, padYear = false)))
    }

    @Test fun otherLanguagesUseTheirOwnFieldOrder() {
        assertEquals("8. Juli 2026", date("yMMMd", "MMM d, yyyy", de))
        assertEquals("Mittwoch, 8. Juli", date("MMMMEEEEd", "EEEE, MMMM d", de))
        assertEquals("Juli 2026", date("yMMMM", "MMMM yyyy", de))
        assertEquals("20:05", time.format(LocaleFormat.dateFormatter("jmm", "h:mm a", de)))

        assertEquals("2026年7月8日", date("yMMMd", "MMM d, yyyy", ja))
        assertEquals("7月8日水曜日", date("MMMMEEEEd", "EEEE, MMMM d", ja))
        assertEquals("2026年7月", date("yMMMM", "MMMM yyyy", ja))
        assertEquals("20:05", time.format(LocaleFormat.dateFormatter("jmm", "h:mm a", ja)))
    }

    @Test fun arabicDatesUseArabicDigits() {
        assertEquals("٨ يوليو ٢٠٢٦", date("yMMMd", "MMM d, yyyy", ar))
        assertEquals("٨:٠٥ م", time.format(LocaleFormat.dateFormatter("jmm", "h:mm a", ar)))
    }

    @Test fun integersAreUngroupedInTheLocalesDigits() {
        assertEquals("1234", LocaleFormat.integer(1234, Locale.US))
        assertEquals("1234", LocaleFormat.integer(1234, de))
        assertEquals("١٢٣٤", LocaleFormat.integer(1234, ar))
        assertEquals("05", LocaleFormat.integer(5, Locale.US, minDigits = 2))
        assertEquals("٠٥", LocaleFormat.integer(5, ar, minDigits = 2))
    }

    @Test fun decimalsDropTrailingZerosAndUseTheLocalesSeparator() {
        assertEquals("1.25", LocaleFormat.decimal(1.25, 2, Locale.US))
        assertEquals("1.5", LocaleFormat.decimal(1.5, 2, Locale.US))
        assertEquals("2", LocaleFormat.decimal(2.0, 2, Locale.US))
        assertEquals("1,25", LocaleFormat.decimal(1.25, 2, de))
        assertEquals("١٫٢٥", LocaleFormat.decimal(1.25, 2, ar))
        // Half up, as String.format rounded.
        assertEquals("1.13", LocaleFormat.decimal(1.125, 2, Locale.US))
    }

    @Test fun clocksUseTheLocalesDigits() {
        assertEquals("1:02:03", LocaleFormat.clock(1, 2, 3, locale = Locale.US))
        assertEquals("2:03", LocaleFormat.clock(0, 2, 3, locale = Locale.US))
        assertEquals("١:٠٢:٠٣", LocaleFormat.clock(1, 2, 3, locale = ar))
    }

    @Test fun patternsAreNormalized() {
        assertEquals("h:mm a", LocaleFormat.normalizePattern("h:mm a"))
        assertEquals("MMM d, yyyy", LocaleFormat.normalizePattern("MMM d, y"))
        assertEquals("MMM d, y", LocaleFormat.normalizePattern("MMM d, y", padYear = false))
        assertEquals("yyyy'y'", LocaleFormat.normalizePattern("y'y'"))
        assertEquals("a H:mm", LocaleFormat.withAmPm("B H:mm"))
        assertEquals("ah:mm", LocaleFormat.withAmPm("Bh:mm"))
        assertEquals("'B' h:mm a", LocaleFormat.withAmPm("'B' h:mm b"))
    }

    /** D55: an English interface on an Arabic device writes counts as `%d` does; dates still follow the device (D26). */
    @Test @Config(qualifiers = "ar")
    fun countsFollowTheInterfaceLanguage() {
        LocaleFormat.refresh(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        Locale.setDefault(ar)
        assertEquals("ar", LocaleFormat.locale().language)
        assertEquals("en", LocaleFormat.interfaceLocale().language)
        assertEquals("1234", LocaleFormat.integer(1234))
        assertEquals("١:٠٢:٠٣", LocaleFormat.clock(1, 2, 3))
    }

    /** D26: the device's own region when it speaks the chosen language, else the chosen language. */
    @Test @Config(qualifiers = "en-rGB")
    fun formattingFollowsTheDeviceWhenItSpeaksTheChosenLanguage() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals(Locale.UK, LocaleFormat.locale())
        Locale.setDefault(de)
        assertEquals(de, LocaleFormat.locale())
        Locale.setDefault(Locale.forLanguageTag("en-XA"))
        assertEquals(Locale.forLanguageTag("en-XA"), LocaleFormat.locale())
    }
}
