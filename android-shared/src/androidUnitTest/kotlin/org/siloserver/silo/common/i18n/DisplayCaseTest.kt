package org.siloserver.silo.common.i18n

import android.os.LocaleList
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayCaseTest {
    private val defaultBefore = LocaleList.getDefault()

    @After fun restore() {
        LocaleList.setDefault(defaultBefore)
        LocaleFormat.resetForTests()
    }

    private fun inInterface(tag: String, block: () -> Unit) {
        LocaleFormat.resetForTests(shipped = listOf("en", "tr"))
        LocaleList.setDefault(LocaleList.forLanguageTags(tag))
        block()
    }

    @Test fun turkishCapitalsKeepTheDot() = inInterface("tr-TR") {
        assertEquals("DİZİ", "dizi".displayUppercase())
        assertEquals("ısıtma", "Isıtma".displayLowercaseFirst())
    }

    @Test fun englishCapitalsAreUnchanged() = inInterface("en-US") {
        assertEquals("SERIES", "series".displayUppercase())
        assertEquals("season 1", "Season 1".displayLowercaseFirst())
    }

    @Test fun anUnshippedDeviceLanguageCapitalizesAsTheEnglishItShows() = inInterface("az") {
        assertEquals(Locale.ENGLISH, LocaleFormat.interfaceLocale())
        assertEquals("DIZI", "dizi".displayUppercase())
    }
}
