package org.siloserver.silo.android.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PhoneDirectorCreditSourceTest {
    private val hero = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt",
    ).readText()
    private val movie = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MovieDetailContent.kt",
    ).readText()

    @Test
    fun phoneMovieHeroUsesSharedDirectorCredit() {
        assertTrue(hero.contains("directorText: String? = null"))
        assertTrue(movie.contains("directorText = movieDirectorCredit(detail)"))
    }

    @Test
    fun phoneCreditStaysBelowFactsAndAboveTranslationAndSelectors() {
        val facts = hero.indexOf("val metadataTokens = (factsLine + sourceTokens).distinct()")
        val director = hero.indexOf("DetailCreditBlock(", startIndex = facts)
        val translation = hero.indexOf("translation?.invoke()", startIndex = director)
        val selectors = hero.indexOf("belowOverview?.invoke()", startIndex = translation)
        assertTrue(facts >= 0 && facts < director && director < translation && translation < selectors)
    }
}
