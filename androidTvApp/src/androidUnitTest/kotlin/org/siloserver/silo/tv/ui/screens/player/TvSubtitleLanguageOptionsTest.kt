package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.ui.LanguageNames
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
