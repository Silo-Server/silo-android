package org.siloserver.silo.tv.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvStarringOverlaySourceTest {
    private val hero = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt",
    ).readText()
    private val screen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
    ).readText()
    private val metadata = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailMetadata.kt",
    ).readText()
    @Test
    fun tvDetailRendersOneStableSeriesStarringCreditAbovePlaybackSummary() {
        val lockedSeriesFooter = hero
            .substringAfter("translation?.invoke()")
            .substringBefore("@Composable\nprivate fun HeroCreditLine")

        assertTrue(screen.contains("internal fun seriesStarringCredit(detail: ItemDetail)"))
        assertTrue(screen.contains("prefix = \"Starring \""))
        assertTrue(screen.contains("val heroCreditText = if (isSeriesDetail)"))
        assertTrue(hero.contains("SERIES_CREDIT_SLOT_HEIGHT"))
        assertTrue(lockedSeriesFooter.indexOf("SERIES_CREDIT_SLOT_HEIGHT") >= 0)
        assertTrue(
            lockedSeriesFooter.indexOf("SERIES_CREDIT_SLOT_HEIGHT") <
                lockedSeriesFooter.indexOf("playbackSummary?.invoke()"),
        )
        // The general metadata helper must not independently derive a second
        // Starring overlay.
        assertTrue(!metadata.contains("starring", ignoreCase = true))
    }

    @Test
    fun tvDetailStillRendersTheFullCastSection() {
        assertTrue(screen.contains("TvCastCrewSection("))
    }
}
