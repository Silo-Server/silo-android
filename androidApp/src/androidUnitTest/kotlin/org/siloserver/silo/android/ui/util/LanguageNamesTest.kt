package org.siloserver.silo.android.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageNamesTest {

    @Test
    fun knownTwoLetterCodesResolve() {
        assertEquals("English", LanguageNames.displayName("en"))
        assertEquals("German", LanguageNames.displayName("de"))
        assertEquals("Japanese", LanguageNames.displayName("ja"))
    }

    @Test
    fun knownThreeLetterCodesResolve() {
        // Embedded track metadata commonly carries ISO 639-2 codes.
        assertEquals("German", LanguageNames.displayName("ger"))
        assertEquals("German", LanguageNames.displayName("deu"))
        assertEquals("English", LanguageNames.displayName("eng"))
    }

    @Test
    fun unknownCodesFallBackToUppercasedCode() {
        assertEquals("XX", LanguageNames.displayName("xx"))
        assertEquals("ZZZ", LanguageNames.displayName("zzz"))
    }

    @Test
    fun blankAndNullAreUnknown() {
        assertEquals("Unknown", LanguageNames.displayName(null))
        assertEquals("Unknown", LanguageNames.displayName("  "))
    }

    @Test
    fun caseAndWhitespaceAreNormalized() {
        assertEquals("French", LanguageNames.displayName(" FR "))
    }

    @Test
    fun dropdownOptionsCoverCommonLanguagesSortedByLabel() {
        assertTrue(LanguageNames.dropdownOptions.size >= 30)
        val labels = LanguageNames.dropdownOptions.map { it.second }
        assertEquals(labels.sorted(), labels)
        assertTrue(LanguageNames.dropdownOptions.any { it.first == "en" && it.second == "English" })
    }

    @Test
    fun searchCodeNormalizesToTwoLetterApiCodes() {
        assertEquals("en", LanguageNames.searchCode("en"))
        assertEquals("de", LanguageNames.searchCode("ger"))
        assertEquals("en", LanguageNames.searchCode("eng"))
        assertEquals("ar", LanguageNames.searchCode("Arabic"))
        assertEquals("pt-BR", LanguageNames.searchCode("pt_BR"))
        assertEquals("zh-Hant", LanguageNames.searchCode("zh-Hant"))
    }

    @Test
    fun searchCodeDoesNotTurnUnknownValuesIntoEnglish() {
        assertEquals("", LanguageNames.searchCode(null))
        assertEquals("", LanguageNames.searchCode(""))
        assertEquals("zz", LanguageNames.searchCode("zz"))
    }
}
