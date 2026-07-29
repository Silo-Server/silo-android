package org.siloserver.silo.tv.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
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
    private val presentationSources = listOf(hero, screen, metadata)

    @Test
    fun tvDetailDoesNotDeriveOrRenderDuplicatedStarringOverlay() {
        assertFalse(
            presentationSources.any { source ->
                source.contains("starring", ignoreCase = true)
            },
        )
    }

    @Test
    fun tvDetailStillRendersTheFullCastSection() {
        assertTrue(screen.contains("TvCastCrewSection("))
    }
}
