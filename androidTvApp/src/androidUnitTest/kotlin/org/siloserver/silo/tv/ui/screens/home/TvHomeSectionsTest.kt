package org.siloserver.silo.tv.ui.screens.home

import java.io.File
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.tv.data.preferences.TvHomeSectionLayout
import org.siloserver.silo.tv.data.preferences.TvHomeSectionPreferences
import org.siloserver.silo.tv.ui.components.isTvContinueWatchingRow
import org.siloserver.silo.tv.ui.components.TvRowStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvHomeSectionsTest {
    @Test
    fun continueRowsAreRecognizedForDetailHandoff() {
        assertTrue(homeSection("continue_watching", "Continue Watching").isTvContinueWatchingRow())
        assertTrue(homeSection("in_progress", "In Progress").isTvContinueWatchingRow())
        assertTrue(homeSection("continue_listening", "Continue Listening").isTvContinueWatchingRow())
        assertFalse(homeSection("next_up", "Next Up").isTvContinueWatchingRow())
        assertFalse(homeSection("recently_added", "Recently Added").isTvContinueWatchingRow())
    }

    @Test
    fun continueWatchingEpisodeTargetsItsSeriesSeasonAndEpisode() {
        val target = SectionItem(
            contentId = "episode-7",
            type = "episode",
            title = "The Dive",
            seriesId = "series-1",
            seasonNumber = 3,
            episodeNumber = 7,
        ).tvContinueWatchingDetailTarget()

        assertEquals(
            TvContinueWatchingDetailTarget(
                contentId = "series-1",
                seasonNumber = 3,
                episodeContentId = "episode-7",
            ),
            target,
        )
    }

    @Test
    fun continueWatchingMovieTargetsItsMovieDetail() {
        val target = SectionItem(
            contentId = "movie-1",
            type = "movie",
            title = "Obsession",
            positionSeconds = 121.0,
        ).tvContinueWatchingDetailTarget()

        assertEquals(TvContinueWatchingDetailTarget(contentId = "movie-1"), target)
    }

    @Test
    fun continueWatchingPrefetchWarmsTheScreenSelectWillOpen() {
        val episode = SectionItem(
            contentId = "episode-7",
            type = "episode",
            title = "The Dive",
            seriesId = "series-1",
            seasonNumber = 3,
            episodeNumber = 7,
        ).tvContinueWatchingPrefetchTarget()
        val movie = SectionItem(
            contentId = "movie-1",
            type = "movie",
            title = "Obsession",
        ).tvContinueWatchingPrefetchTarget()

        assertEquals("series-1", episode.detailContentId)
        assertEquals("series-1", episode.seriesId)
        assertEquals(3, episode.seasonNumber)
        assertEquals("episode-7", episode.episodeContentId)
        assertEquals("movie-1", movie.detailContentId)
        assertEquals(null, movie.seriesId)
        assertEquals(null, movie.episodeContentId)
    }

    @Test
    fun mixedContinueRowsSplitAudiobooksIntoContinueListening() {
        val sections = listOf(
            ResolvedSection(
                id = "continue",
                sectionType = "continue_watching",
                title = "Continue Watching",
                items = listOf(
                    SectionItem(contentId = "m1", type = "movie", title = "Movie"),
                    SectionItem(contentId = "a1", type = "audiobook", title = "Audio"),
                    SectionItem(contentId = "e1", type = "ebook", title = "Book"),
                ),
            ),
        )

        val normalized = sections.normalizeTvHomeSections()

        assertEquals(listOf("Continue Watching", "Continue Listening"), normalized.map { it.title })
        assertEquals(listOf("m1"), normalized[0].items.map { it.contentId })
        assertEquals(listOf("a1"), normalized[1].items.map { it.contentId })
        assertTrue(normalized[1].isTvAudioProgressSection())
        assertEquals(TvRowStyle.Poster, normalized[1].tvHomeRowStyle())
    }

    @Test
    fun audiobookOnlyContinueRowIsRetitledAndKeptAsProgress() {
        val sections = listOf(
            ResolvedSection(
                id = "continue",
                sectionType = "continue_watching",
                title = "Continue Watching",
                items = listOf(
                    SectionItem(contentId = "a1", type = "audiobook", title = "Audio"),
                ),
            ),
        )

        val normalized = sections.normalizeTvHomeSections()

        assertEquals(listOf("Continue Listening"), normalized.map { it.title })
        assertEquals("continue_listening", normalized.single().sectionType)
        assertTrue(normalized.single().isTvAudioProgressSection())
        assertEquals(TvRowStyle.Poster, normalized.single().tvHomeRowStyle())
    }

    @Test
    fun savedOrderAndVisibilityProjectPopulatedRowsWithoutAGap() {
        val sections = listOf(
            homeSection("continue", "Continue Watching"),
            homeSection("recent", "Recently Added"),
            homeSection("because", "Because You Watched"),
        )
        val layout = TvHomeSectionLayout(
            orderedSectionIds = listOf("recent", "continue"),
            hiddenSectionIds = setOf("recent"),
        )

        val visible = TvHomeSectionPreferences.arrange(sections, layout)
        val editor = TvHomeSectionPreferences.arrange(
            sections,
            layout,
            includingHidden = true,
        )

        assertEquals(listOf("continue", "because"), visible.map { it.id })
        assertEquals(listOf("recent", "continue", "because"), editor.map { it.id })
    }

    @Test
    fun reorderedKnownRowsRetainTemporarilyAbsentRememberedRows() {
        assertEquals(
            listOf("recent", "continue", "temporarily-empty"),
            TvHomeSectionPreferences.retainedOrder(
                sectionIds = listOf("recent", "continue", "recent"),
                rememberedSectionIds = listOf("continue", "temporarily-empty"),
            ),
        )
    }

    @Test
    fun editorButtonsCenterTheirContentWithoutExpandingOverTheSectionRows() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings/TvHomeSectionsEditor.kt",
        ).readText()
        val control = source.substringAfter("private fun HomeSectionsControlButton(")
        val labelControl = control.substringAfter("} else {")

        assertTrue(control.contains(".fillMaxHeight()"))
        assertTrue(control.contains(".widthIn(min = 68.dp)"))
        assertTrue(control.contains("modifier = Modifier.fillMaxSize()"))
        assertTrue(control.contains("contentAlignment = Alignment.Center"))
        assertTrue(labelControl.contains(".fillMaxHeight()\n                    // Mirror the Surface's minimum width inside its content."))
        assertTrue(labelControl.contains(".widthIn(min = 68.dp)\n                    .padding(horizontal = 12.dp)"))
        assertTrue(labelControl.contains("horizontalArrangement = Arrangement.Center"))
    }

    private fun homeSection(id: String, title: String) = ResolvedSection(
        id = id,
        sectionType = id,
        title = title,
        items = listOf(SectionItem(contentId = "$id-item", type = "movie", title = title)),
    )
}
