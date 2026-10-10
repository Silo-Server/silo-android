package org.siloserver.silo.tv.ui.components

import java.io.File
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.model.catalog.DisplayRating
import org.siloserver.silo.model.catalog.OverlaySummary
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvFocusMarqueeModelTest {
    @Test
    fun rowUpdatesRefreshWatchStateWithoutChangingFocusOrEnrichment() {
        val state = TvFocusMarqueeState()
        state.spoilerPrefs = EpisodeSpoilerPrefs(true, true)
        val episode = SectionItem(
            contentId = "episode", type = "episode", title = "Episode",
            overview = "Spoiler", backdropUrl = "https://example.test/still.jpg",
            userState = MediaItemUserState(played = true),
        )
        val row = ResolvedSection("row", "next_up", "Next Up", items = listOf(episode))
        state.preview(episode, row.title, row.id)
        state.commit(state.candidate)
        val enrichment = TvMarqueeEnrichment(null, "https://example.test/series.jpg", null, backdropIsEpisodeStill = false)
        state.applyEnrichment(episode.contentId, enrichment)

        val unwatched = episode.copy(userState = MediaItemUserState(played = false))
        state.refreshSources(listOf(row.copy(items = listOf(unwatched))))

        assertEquals(null, state.content?.synopsis)
        assertEquals(null, state.content?.heroBackdropUrl)
        assertEquals("row#episode", state.focusedMarqueeId)
        assertTrue(state.hasSettledRealFocus)
        assertEquals(enrichment, state.enrichment)
        assertEquals(enrichment.backdropUrl, state.backdropContent?.heroBackdropUrl)
        // A detail backdrop that is, or may be, the episode's still stays hidden.
        state.applyEnrichment(episode.contentId, enrichment.copy(backdropIsEpisodeStill = null))
        assertEquals(null, state.backdropContent?.heroBackdropUrl)

        state.refreshSources(listOf(row))
        assertEquals("Spoiler", state.content?.synopsis)
        assertEquals(episode.backdropUrl, state.content?.heroBackdropUrl)
    }

    @Test
    fun aDepartingLayerFollowsAProtectionChange() {
        val episode = SectionItem(
            contentId = "episode", type = "episode", title = "Episode",
            overview = "Spoiler", backdropUrl = "https://example.test/still.jpg",
        )
        val shown = TvMarqueeContent.from(episode, "Next Up", "row")

        val protected = shown.underPrefs(EpisodeSpoilerPrefs(true, true))
        assertEquals(null, protected.heroBackdropUrl)
        assertEquals(null, protected.synopsis)
        assertEquals(shown.id, protected.id)
        assertTrue(shown.underPrefs(EpisodeSpoilerPrefs.NONE) === shown)
        val watched = TvMarqueeContent.from(episode.copy(userState = MediaItemUserState(played = true)), "Next Up", "row")
        assertTrue(watched.underPrefs(EpisodeSpoilerPrefs(true, true)) === watched)
        // An unchanged layer without a description stays the same instance.
        val noOverview = TvMarqueeContent.from(episode.copy(overview = null), "Next Up", "row", EpisodeSpoilerPrefs(true, true))
        assertTrue(noOverview.underPrefs(EpisodeSpoilerPrefs(true, true)) === noOverview)
    }

    @Test
    fun hidingKeepsAnExplicitSeriesBackdropFromEnrichment() {
        val episode = SectionItem(
            contentId = "episode", type = "episode", title = "Episode",
            overview = "Spoiler", backdropUrl = "https://example.test/still.jpg",
        )
        val shown = TvMarqueeContent.from(episode, "Next Up", "row")
        val series = TvMarqueeEnrichment(null, "https://example.test/series.jpg", null, backdropIsEpisodeStill = false)

        val keptSeries = shown.withEnrichment(series).underPrefs(EpisodeSpoilerPrefs(true, true))
        assertEquals("https://example.test/series.jpg", keptSeries.heroBackdropUrl)
        assertEquals(null, keptSeries.synopsis)
        val unknown = shown.withEnrichment(series.copy(backdropIsEpisodeStill = null)).underPrefs(EpisodeSpoilerPrefs(true, true))
        assertEquals(null, unknown.heroBackdropUrl)
    }

    @Test
    fun rowUpdatesKeepDisplayedAndPendingCopiesBoundToTheirOwnRows() {
        val state = TvFocusMarqueeState()
        state.spoilerPrefs = EpisodeSpoilerPrefs(true, true)
        val watched = SectionItem(
            contentId = "episode", type = "episode", title = "Episode",
            overview = "Displayed spoiler", userState = MediaItemUserState(played = true),
        )
        val displayedRow = ResolvedSection("displayed", "next_up", "Next Up", items = listOf(watched))
        val pendingItem = watched.copy(overview = "Pending spoiler")
        val pendingRow = displayedRow.copy(id = "pending", items = listOf(pendingItem))
        state.preview(watched, displayedRow.title, displayedRow.id)
        state.commit(state.candidate)
        state.preview(pendingItem, pendingRow.title, pendingRow.id)

        val unwatched = watched.copy(userState = MediaItemUserState(played = false))
        state.refreshSources(listOf(pendingRow, displayedRow.copy(items = listOf(unwatched))))

        assertEquals(unwatched, state.content?.source)
        assertEquals(null, state.content?.synopsis)
        assertEquals(pendingItem, state.candidate?.source)
        assertEquals("Pending spoiler", state.candidate?.synopsis)
        assertEquals("pending#episode", state.focusedMarqueeId)

        val displayed = state.content
        val pending = state.candidate
        state.refreshSources(emptyList())
        assertEquals(displayed, state.content)
        assertEquals(pending, state.candidate)
    }

    @Test
    fun preferencesReprojectTheDisplayedAndPendingEpisodes() {
        val state = TvFocusMarqueeState()
        val episode = SectionItem(
            contentId = "unstarted", type = "episode", title = "Episode",
            overview = "Spoiler", backdropUrl = "https://example.test/still.jpg",
        )
        state.preview(episode, "Next Up")
        state.commit(state.candidate)
        state.preview(episode.copy(contentId = "pending"), "Next Up")
        val pendingBeforeHydration = state.candidate

        state.spoilerPrefs = EpisodeSpoilerPrefs(true, true)

        assertEquals(null, state.content?.synopsis)
        assertEquals(null, state.backdropContent?.heroBackdropUrl)
        assertEquals(null, state.candidate?.synopsis)
        state.commit(pendingBeforeHydration)
        assertEquals(null, state.content?.synopsis)
        assertEquals(null, state.backdropContent?.heroBackdropUrl)

        state.spoilerPrefs = EpisodeSpoilerPrefs.NONE
        assertEquals("Spoiler", state.content?.synopsis)
        assertEquals(episode.backdropUrl, state.backdropContent?.heroBackdropUrl)
    }

    @Test
    fun movieHeroSeparatesEditorialMetadataFromFormatBadges() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "movie-1",
                type = "movie",
                title = "Arrival",
                year = 2016,
                genres = listOf("Science Fiction"),
                ratingImdb = 7.9,
                contentRating = "PG-13",
                durationSeconds = 6_960.0,
                overlaySummary = OverlaySummary(
                    resolution = "2160p",
                    hdr = "Dolby Vision",
                    audio = "TrueHD Atmos",
                ),
            ),
            rowTitle = "Popular",
        )

        assertEquals(listOf("PG-13"), content.badges)
        assertEquals(
            listOf("2016", "1h 56m", "IMDb 7.9", "Science Fiction"),
            content.metaText(),
        )
        assertEquals(
            TvHeroFactToken.ExternalRating(DisplayRating("imdb", "IMDb", 79.0, "7.9")),
            content.metaParts[2],
        )
        assertEquals("4K · Dolby Vision · Atmos", content.specLine)
    }

    @Test
    fun episodeHeroUsesSeriesTitleAndEditorialEpisodeMetadata() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "episode-1",
                type = "episode",
                title = "Long, Long Time",
                seriesTitle = "The Last of Us",
                seasonNumber = 1,
                episodeNumber = 3,
                ratingImdb = 8.6,
                contentRating = "TV-MA",
                durationSeconds = 4_560.0,
                overlaySummary = OverlaySummary(
                    resolution = "1080p",
                    audio = "EAC3",
                ),
            ),
            rowTitle = "Continue Watching",
        )

        assertEquals("The Last of Us", content.title)
        assertEquals(listOf("TV-MA"), content.badges)
        assertEquals(
            listOf(TvHeroFactToken.TextToken("S01E03"), TvHeroFactToken.TextToken("Long, Long Time", truncates = true)),
            content.metaParts,
        )
        assertEquals("1080P · EAC3", content.specLine)
    }

    @Test
    fun homeHeroOmitsEpisodeRuntimeAndRemainingTime() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "episode-progress",
                type = "episode",
                title = "Persuader",
                seriesTitle = "Reacher",
                seasonNumber = 3,
                episodeNumber = 1,
                runtime = 53,
                positionSeconds = 240.0,
                durationSeconds = 3_180.0,
            ),
            rowTitle = "Continue Watching",
        )

        assertEquals(listOf("S03E01", "Persuader"), content.metaText())
        assertFalse(content.metaText().any { it.contains("left", ignoreCase = true) })
        assertFalse(content.metaText().any { it.contains("min", ignoreCase = true) })
    }

    @Test
    fun homeHeroUsesOneOutlinedBadgeStyleForRatingAndFormats() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvFocusMarquee.kt",
        ).readText()

        assertTrue(source.contains("content.specLine"))
        assertTrue(source.contains("?.split(\" · \")"))
        assertTrue(source.contains("badges.forEach { badge -> MarqueeBadge(badge.uppercase()) }"))
        assertTrue(source.contains("Color.White.copy(alpha = 0.08f)"))
        assertTrue(source.contains("Color.White.copy(alpha = 0.24f)"))
        assertTrue(source.contains("private val MarqueeBadgeSize = 10.5.sp"))
        assertFalse(source.contains("FontFamily.Monospace"))
    }

    @Test
    fun missingEditorialMetadataProducesNoEmptyTokensOrBadges() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "movie-2",
                type = "movie",
                title = "Untitled",
                overlaySummary = OverlaySummary(
                    resolution = "2160p",
                    hdr = "HDR10",
                    audio = "Atmos",
                ),
            ),
            rowTitle = "Recently Added",
        )

        assertEquals(emptyList(), content.badges)
        assertEquals(emptyList(), content.metaParts)
    }

    @Test
    fun invalidRatingsAndDurationsAreOmittedFromTvMetadata() {
        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            -1.0,
            11.0,
        ).forEachIndexed { index, invalid ->
            val content = TvMarqueeContent.from(
                item = SectionItem(
                    contentId = "invalid-$index",
                    type = "movie",
                    title = "Invalid",
                    ratingImdb = invalid,
                    durationSeconds = invalid,
                ),
                rowTitle = "Invalid",
            )

            assertEquals(emptyList(), content.metaParts)
            }
    }

    @Test
    fun invalidRatingDoesNotHideValidTvRuntime() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "runtime-with-invalid-rating",
                type = "movie",
                title = "Movie",
                ratingImdb = Double.NaN,
                durationSeconds = 7_200.0,
            ),
            rowTitle = "Row",
        )

        assertEquals(listOf("2h"), content.metaText())
    }

    @Test
    fun validRatingDoesNotHideInvalidTvRuntime() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "rating-with-invalid-runtime",
                type = "movie",
                title = "Movie",
                ratingImdb = 8.4,
                durationSeconds = Double.NaN,
            ),
            rowTitle = "Row",
        )

        assertEquals(listOf("IMDb 8.4"), content.metaText())
    }

    @Test
    fun catalogRuntimeWinsOverPlaybackDurationOnTv() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "movie-runtime",
                type = "movie",
                title = "Movie",
                runtime = 125,
                durationSeconds = 60.0,
            ),
            rowTitle = "Row",
        )

        assertEquals(listOf("2h 5m"), content.metaText())
    }

    @Test
    fun invalidCatalogRuntimeFallsBackToPlaybackDurationOnTv() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "movie-runtime-fallback",
                type = "movie",
                title = "Movie",
                runtime = 0,
                durationSeconds = 6_960.0,
            ),
            rowTitle = "Row",
        )

        assertEquals(listOf("1h 56m"), content.metaText())
    }

    private fun TvMarqueeContent.metaText(): List<String> = metaParts.map { token ->
        when (token) {
            is TvHeroFactToken.TextToken -> token.value
            is TvHeroFactToken.ExternalRating -> "${token.rating.name} ${token.rating.display}"
            is TvHeroFactToken.Rating -> token.value
            is TvHeroFactToken.Chip -> token.value
        }
    }
}
