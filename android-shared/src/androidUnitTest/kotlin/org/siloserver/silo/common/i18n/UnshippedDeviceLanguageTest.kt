package org.siloserver.silo.common.i18n

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.common.R
import java.util.Locale
import kotlin.test.assertEquals

/**
 * D64: on a device whose language has no Silo catalog (only English ships in
 * these tests), the app's resources and its formatting are English, so `%d`
 * placeholders and LocaleFormat agree instead of mixing digit systems.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar")
class UnshippedDeviceLanguageTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val defaultBefore = Locale.getDefault()

    @After fun restore() {
        Locale.setDefault(defaultBefore)
        LocaleFormat.resetForTests()
    }

    @Test
    fun anArabicDeviceWithoutAnArabicCatalogGetsLatinDigitsEverywhere() {
        val arabic = Locale.forLanguageTag("ar")
        Locale.setDefault(arabic)
        LocaleFormat.refresh(app)
        // What an activity's attachBaseContext receives on an Arabic phone following the device.
        val device = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(arabic) })

        val shown = AppLocales.wrap(device).resources
        assertEquals("12 downloads", shown.getQuantityString(R.plurals.account_downloads_count, 12, 12))
        assertEquals(Configuration().apply { setLocale(Locale.ENGLISH) }.layoutDirection, shown.configuration.layoutDirection)

        assertEquals("12", LocaleFormat.integer(12))
        assertEquals("1.5", LocaleFormat.decimal(1.5, maxFractionDigits = 1))
        assertEquals(
            "Jul 30, 1947",
            java.time.LocalDate.of(1947, 7, 30).format(LocaleFormat.dateFormatter("yMMMd", "MMM d, y", padYear = false)),
        )
    }

    @Test
    fun theStandInIsTheDevicesOwnShippedLocaleWithItsRegion() {
        val shipped = listOf("en")
        assertEquals(Locale.forLanguageTag("en-AU"), AppLocales.standIn(android.os.LocaleList.forLanguageTags("ar,en-AU"), shipped))
        assertEquals(Locale.ENGLISH, AppLocales.standIn(android.os.LocaleList.forLanguageTags("ar,de"), shipped))
        assertEquals(null, AppLocales.standIn(android.os.LocaleList.forLanguageTags("en-GB,ar"), shipped))
    }

    @Test
    fun aShippedOrPseudoLanguageIsLeftAsItIs() {
        for (tag in listOf("en-GB", "en-XA")) {
            val locale = Locale.forLanguageTag(tag)
            val base = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(locale) })
            assertEquals(locale, AppLocales.wrap(base).resources.configuration.locales[0])
        }
    }
}
