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
        assertTrue(screen.contains("val heroCreditText = if (isSeriesDetail)"))
        assertTrue(screen.contains("movieDirectorCredit(detail).takeIf"))
        assertTrue(screen.contains("directorText = heroCreditText"))
    }

    @Test
    fun approvedMetadataPrecedesSynopsisAndPlaybackFollowsTvCredit() {
        val metadata = hero.indexOf("MetadataRow(")
        val synopsis = hero.indexOf("overview?.takeIf")
        val translation = hero.indexOf("translation?.invoke()")
        val director = hero.indexOf("directorText?.takeIf")
        val playback = hero.lastIndexOf("playbackSummary?.invoke()")
        assertTrue(
            metadata >= 0 &&
                metadata < synopsis &&
                synopsis < translation &&
                translation < director &&
                director < playback,
        )
    }
}
