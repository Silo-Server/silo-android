package org.siloserver.silo.tv.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvDirectorCreditSourceTest {
    private val hero = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt",
    ).readText()
    private val screen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
    ).readText()

    @Test
    fun tvMovieHeroUsesSharedDirectorCredit() {
        assertTrue(screen.contains("directorText = movieDirectorCredit(detail)"))
    }

    @Test
    fun tvCreditStaysBetweenTranslationAndFacts() {
        val translation = hero.indexOf("translation?.invoke()")
        val director = hero.indexOf("directorText?.takeIf")
        val facts = hero.indexOf("if (factsLine.isNotEmpty())", startIndex = director)
        assertTrue(translation >= 0 && translation < director && director < facts)
    }
}
