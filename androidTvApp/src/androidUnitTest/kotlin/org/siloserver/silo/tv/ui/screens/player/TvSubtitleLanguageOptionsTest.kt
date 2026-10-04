package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.ui.LanguageNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSubtitleLanguageOptionsTest {

    @Test
    fun tvOffersTheSharedVocabulary() {
        assertEquals(LanguageNames.dropdownOptions.map { it.first }, TvSubtitleLanguageOptions)
    }

    @Test
    fun everySupportedLanguageSelectsItself() {
        for ((code, _) in LanguageNames.dropdownOptions) {
            assertEquals(code, TvSubtitleLanguageOptions[tvSubtitleLanguageIndex(code)])
        }
    }

    @Test
    fun croatianPreferenceSelectsCroatian() {
        val index = tvSubtitleLanguageIndex("hr")
        assertEquals("hr", TvSubtitleLanguageOptions[index])
        assertEquals("Croatian", tvLanguageDisplayName(TvSubtitleLanguageOptions[index]))
        assertEquals("hr", TvSubtitleLanguageOptions[tvSubtitleLanguageIndex("hrv")])
    }

    @Test
    fun threeLetterCodesNormalize() {
        assertEquals("de", TvSubtitleLanguageOptions[tvSubtitleLanguageIndex("ger")])
    }

    @Test
    fun unsupportedOrMissingCodesFallBackToEnglish() {
        assertEquals("en", TvSubtitleLanguageOptions[tvSubtitleLanguageIndex("zz")])
        assertEquals("en", TvSubtitleLanguageOptions[tvSubtitleLanguageIndex(null)])
    }

    @Test
    fun sameLanguageMatchesAcrossCodeLengths() {
        assertTrue(tvIsSameLanguage("bul", "bg"))
        assertTrue(tvIsSameLanguage("hrv", "hr"))
        assertTrue(tvIsSameLanguage("eng", "en"))
        assertTrue(tvIsSameLanguage("DE", "de"))
        assertFalse(tvIsSameLanguage("ger", "fr"))
    }

    @Test
    fun unknownSourceIsNotEnglish() {
        assertFalse(tvIsSameLanguage("xyz", "en"))
        assertFalse(tvIsSameLanguage(null, "en"))
        assertFalse(tvIsSameLanguage(" ", "en"))
    }
}
