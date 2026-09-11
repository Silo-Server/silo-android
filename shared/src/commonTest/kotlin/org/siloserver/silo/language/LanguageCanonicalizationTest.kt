package org.siloserver.silo.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageCanonicalizationTest {
    @Test
    fun aliasesAndLegacyNamesCanonicalize() {
        assertEquals("ar", canonicalLanguageTag("ar"))
        assertEquals("ar", canonicalLanguageTag("ara"))
        assertEquals("ar", canonicalLanguageTag("Arabic"))
        assertEquals("pt-BR", canonicalLanguageTag("pt_br"))
        assertEquals("zh-Hant", canonicalLanguageTag("ZH-hant"))
    }

    @Test
    fun unknownAndEmptyDoNotBecomeEnglish() {
        assertNull(canonicalLanguageTag(null))
        assertNull(canonicalLanguageTag(""))
        assertEquals("zz", canonicalLanguageTag("zz"))
        assertNull(canonicalLanguageTag("Klingon"))
    }
}
