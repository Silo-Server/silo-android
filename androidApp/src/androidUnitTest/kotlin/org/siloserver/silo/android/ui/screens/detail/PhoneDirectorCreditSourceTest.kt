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
    fun phoneCreditStaysBetweenTranslationAndFacts() {
        val translation = hero.indexOf("translation?.invoke()")
        val director = hero.indexOf("directorText?.takeIf")
        val facts = hero.indexOf("if (factsLine.isNotEmpty())", startIndex = director)
        assertTrue(translation >= 0 && translation < director && director < facts)
    }
}
