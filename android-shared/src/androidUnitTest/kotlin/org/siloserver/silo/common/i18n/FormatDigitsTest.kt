package org.siloserver.silo.common.i18n

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.DecimalFormatSymbols
import android.icu.util.ULocale
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.common.R
import org.siloserver.silo.model.catalog.EpisodeCardCaption
import org.siloserver.silo.model.catalog.EpisodeListItem
import java.util.Locale
import kotlin.test.assertEquals

/**
 * D75: one digit system per screen. With the app language chosen in the app,
 * resource `%d` and plural counts use the digits [LocaleFormat] uses, which
 * follow the device's region (D26): Arabic-Indic on `ar-EG`, Latin on `ar-AE`.
 *
 * The device's `String.format` reads ICU, which on Android 16 gives bare `ar`
 * Latin digits; Robolectric's JVM formatter reads the JDK's data, which gives
 * it Arabic-Indic. So these tests read the digits a device would print for the
 * resources' locale from ICU ([deviceZero]) as well as formatting through them.
 */
@RunWith(RobolectricTestRunner::class)
class FormatDigitsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val defaultBefore = Locale.getDefault()

    @After fun restore() {
        Locale.setDefault(defaultBefore)
        AppLocales.setShippedForTests(null)
        LocaleFormat.resetForTests()
    }

    /** The resources an activity gets with [appLanguage] chosen in the app, on the qualifiers' device. */
    private fun activityResources(appLanguage: String): Resources {
        val shipped = listOf("en", "ar")
        AppLocales.setShippedForTests(shipped)
        LocaleFormat.resetForTests(shipped)
        val locale = Locale.forLanguageTag(appLanguage)
        Locale.setDefault(locale)
        LocaleFormat.refresh(app)
        val base = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(locale) })
        return AppLocales.wrap(base).resources
    }

    /** The zero digit a device's `String.format` writes for these resources' `%d`. */
    private fun Resources.deviceZero(): Char =
        DecimalFormatSymbols.getInstance(ULocale.forLocale(configuration.locales[0])).zeroDigit

    @Test @Config(sdk = [36], qualifiers = "ar-rEG")
    fun arabicOnAnEgyptianDeviceUsesArabicIndicDigitsEverywhere() {
        val res = activityResources("ar")
        assertEquals("ar", res.configuration.locales[0].language)
        assertEquals('٠', res.deviceZero())
        assertEquals("١٢", LocaleFormat.integer(12))
        assertEquals(true, res.getQuantityString(R.plurals.account_downloads_count, 12, 12).contains("١٢"))
        // D77: a release year is a number on screen too.
        assertEquals("٢٠٢١", LocaleFormat.integer(2021))
        // D78: an episode code stays ASCII; the spoken phrase doesn't.
        val code = EpisodeCardCaption.title(EpisodeListItem("e", seasonNumber = 1, episodeNumber = 5)).text(res)
        assertEquals(true, code.contains("01") && code.contains("05"), code)
        assertEquals("05", LocaleFormat.codeNumber(5, minDigits = 2))
        assertEquals(true, res.getString(R.string.detail_season, LocaleFormat.integer(2)).contains("٢"))
    }

    @Test @Config(sdk = [35], qualifiers = "ar-rAE")
    fun arabicOnALatinDigitRegionUsesLatinDigitsEverywhere() {
        val res = activityResources("ar")
        assertEquals("ar", res.configuration.locales[0].language)
        assertEquals('0', res.deviceZero())
        assertEquals("12", LocaleFormat.integer(12))
        assertEquals(true, res.getQuantityString(R.plurals.account_downloads_count, 12, 12).contains("12"))
        assertEquals("2021", LocaleFormat.integer(2021))
    }

    @Test @Config(sdk = [36], qualifiers = "en-rUS")
    fun englishIsLeftAsItIs() {
        val res = activityResources("en")
        assertEquals(Locale.ENGLISH, res.configuration.locales[0])
        assertEquals('0', res.deviceZero())
        assertEquals("12 downloads", res.getQuantityString(R.plurals.account_downloads_count, 12, 12))
        assertEquals("2021", LocaleFormat.integer(2021))
    }
}
