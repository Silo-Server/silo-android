package org.siloserver.silo.tv.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.model.catalog.Season

class TvDetailFocusPolicyTest {
    @Test
    fun castRailRestoresLastCard() {
        assertEquals(5, restoredRailIndex(5, 8))
        assertEquals(2, restoredRailIndex(5, 3))
        assertNull(restoredRailIndex(0, 0))
    }

    @Test
    fun episodeRailInitialCenterDoesNotRestartForEachFocusedEpisode() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailEpisodeRail.kt",
        ).readText()

        assertTrue(source.contains("LaunchedEffect(episodeSetKey)"))
        assertFalse(source.contains("LaunchedEffect(currentContentId, episodes.size)"))
    }

    @Test
    fun seriesPrimaryControlsKeepActionsAndOrderSeasonsBeforeEpisodes() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
        ).readText()
        val episodesSection = source
            .substringAfter("private fun EpisodesSection(")
            .substringBefore("private fun currentEpisodeRailContentId")

        val seasons = episodesSection.indexOf("if (showsSeasonChips)")
        val episodes = episodesSection.indexOf("TvDetailEpisodeRail(")

        assertFalse(source.contains("seriesPlaybackSelector"))
        assertTrue(seasons >= 0)
        assertTrue(episodes >= 0)
        assertTrue(seasons < episodes)
        assertTrue(episodesSection.contains("padding(top = SeriesSeasonPickerTopPadding)"))
        assertFalse(source.contains("if (!isSeriesDetail || isShowingSeriesOverview)"))
        assertFalse(source.contains("onItemDetail(episode.contentId)"))
    }

    @Test
    fun videoDetailsUseCircularPlaybackControlsAndSharedOverflow() {
        val detailSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
        ).readText()
        val selectorSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvPlaybackSelectorRow.kt",
        ).readText()
        val menuSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvAnchoredSelectorMenu.kt",
        ).readText()

        assertTrue(detailSource.contains("TvPlaybackActionSelectors("))
        assertFalse(detailSource.contains("if (showsSelectorRow) down = selectorFocus"))
        assertTrue(detailSource.contains("contentDescription = if (state.inWatchlist)"))
        assertTrue(detailSource.contains("icon = Icons.Filled.BookmarkBorder"))
        assertTrue(detailSource.contains("key = \"favorite\""))
        assertTrue(detailSource.contains("key = \"watched\""))
        assertTrue(selectorSource.contains("icon = Icons.Filled.Movie"))
        assertTrue(selectorSource.contains("icon = Icons.AutoMirrored.Filled.VolumeUp"))
        assertTrue(selectorSource.contains("icon = Icons.AutoMirrored.Filled.Chat"))
        assertTrue(selectorSource.contains("triggerStyle = TvSelectorTriggerStyle.CircularAction"))
        assertTrue(menuSource.contains("TvSelectorTriggerStyle.CircularAction -> TvSquareToggleButton("))
    }

    @Test
    fun seriesActionChromeAndCurrentEpisodeLabelsRemainCompactAndReadable() {
        val detailSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
        ).readText()
        val buttonSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSquaredButtons.kt",
        ).readText()
        val episodeSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailEpisodeRail.kt",
        ).readText()

        assertTrue(buttonSource.contains("Color.White.copy(alpha = 0.76f)"))
        assertFalse(detailSource.contains("private fun CircleAction("))
        assertTrue(detailSource.contains("iconActive = Icons.Filled.SkipPrevious"))
        assertTrue(detailSource.contains("Modifier.width(170.dp)"))
        assertTrue(episodeSource.contains("text = \"NOW VIEWING\""))
        assertTrue(episodeSource.contains("val eyebrowFontSize = if (usesSeriesGeometry) 11.sp else 14.sp"))
        assertTrue(episodeSource.contains("fontSize = 11.5.sp"))
        assertTrue(episodeSource.contains("tvEpisodeCardWidth()"))
        assertTrue(episodeSource.contains("cardWidth * (9f / 16f)"))
        assertTrue(episodeSource.contains("Modifier else Modifier.scale(scale)"))
    }

    @Test
    fun seriesCreditAndPlaybackReadoutStayLockedBelowSynopsisWithoutPillChrome() {
        val heroSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt",
        ).readText()
        val detailSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
        ).readText()
        val editorial = heroSource
            .substringAfter("private fun EditorialColumn(")
            .substringBefore("private fun TitleBlock(")
        val playbackReadout = detailSource
            .substringAfter("private fun TvDetailPlaybackSelectionSummary(")
            .substringBefore("private fun HeroActionRow(")

        val facts = editorial.indexOf("MetadataRow(")
        val synopsis = editorial.indexOf("TvExpandableSynopsis(")
        val credit = editorial.indexOf("SERIES_CREDIT_SLOT_HEIGHT")
        val playback = editorial.lastIndexOf("playbackSummary?.invoke()")

        assertTrue(facts >= 0)
        assertTrue(facts < synopsis)
        assertTrue(synopsis < credit)
        assertTrue(credit < playback)
        assertTrue(heroSource.contains("if (!compactSeries && sourceTokens.isNotEmpty())"))
        assertTrue(heroSource.contains("sourceTokens = sourceTokens"))
        assertTrue(heroSource.contains("compactRating = true"))
        assertFalse(editorial.contains("if (!compactSeries) playbackSummary?.invoke()"))
        assertTrue(editorial.lastIndexOf("HeroCreditLine(line)") < playback)
        assertTrue(heroSource.contains("fontSize = if (compact) 10.5.sp else 14.sp"))
        assertTrue(heroSource.contains("SERIES_HERO_HEIGHT_FRACTION = 610f / 1080f"))
        assertTrue(detailSource.contains("label = \"VERSION\""))
        assertTrue(detailSource.contains("label = \"AUDIO\""))
        assertTrue(detailSource.contains("label = \"SUBTITLES\""))
        assertTrue(detailSource.contains("includePlaybackFormats = false"))
        assertTrue(playbackReadout.contains("Modifier.weight(1f)"))
        assertTrue(playbackReadout.contains("Arrangement.spacedBy(6.dp)"))
        assertTrue(playbackReadout.contains("Modifier.height(TV_PLAYBACK_SUMMARY_HEIGHT)"))
        assertTrue(playbackReadout.contains("TvPlaybackSummarySkeleton(modifier = Modifier.weight(1f))"))
        // Loaded values size to their text so long audio / subtitle labels never
        // truncate; only the skeleton keeps the fixed per-item width.
        assertTrue(playbackReadout.contains("modifier = if (value == null) Modifier.width(itemWidth) else Modifier"))
        assertFalse(playbackReadout.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(playbackReadout.contains("TV_PLAYBACK_VERSION_ITEM_WIDTH = 120.dp"))
        assertTrue(playbackReadout.contains("TV_PLAYBACK_AUDIO_ITEM_WIDTH = 160.dp"))
        assertTrue(playbackReadout.contains("TV_PLAYBACK_SUBTITLE_ITEM_WIDTH = 130.dp"))
        assertFalse(playbackReadout.contains("Spacer(modifier = Modifier.size"))
    }

    @Test
    fun episodeMetadataKeepsTheShowOverviewActionBaseline() {
        val heroSource = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt",
        ).readText()

        assertTrue(heroSource.contains("seriesOverviewEditorialHeightPx"))
        assertTrue(heroSource.contains("seriesTitle == null"))
        assertTrue(heroSource.contains("Modifier.height(with(density)"))
        assertTrue(heroSource.contains("isCombinedSeriesEpisode"))
        assertTrue(heroSource.contains("SERIES_METADATA_SLOT_HEIGHT"))
        assertTrue(heroSource.contains("SERIES_EPISODE_SYNOPSIS_HEIGHT"))
        assertTrue(heroSource.contains("SERIES_CREDIT_SLOT_HEIGHT"))
        assertTrue(heroSource.contains("previewText = if (isCombinedSeriesEpisode)"))
        assertFalse(heroSource.contains("Spacer(modifier = Modifier.weight(1f))"))
    }

    @Test
    fun seriesSeasonSelectionKeepsAppleStyleCenteredPan() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvSeasonPicker.kt",
        ).readText()
        val seriesPicker = source
            .substringAfter("fun TvSeriesModePicker(")
            .substringBefore("private fun TvSeriesModeTab(")
        val modeTab = source
            .substringAfter("private fun TvSeriesModeTab(")
            .substringBefore("private fun Modifier.seriesModeFocusRing")

        assertTrue(seriesPicker.contains("LaunchedEffect(selectedIndex, seasons.size)"))
        assertTrue(seriesPicker.contains("listState.scrollToItem(selectedIndex)"))
        assertTrue(seriesPicker.contains("val viewportCenter"))
        assertTrue(seriesPicker.contains("val itemCenter"))
        assertTrue(seriesPicker.contains("listState.animateScrollBy(itemCenter - viewportCenter)"))
        assertTrue(modeTab.contains("if (focusState.isFocused && !isSelected) onActivated()"))
        assertTrue(modeTab.contains("onClick = onActivated"))
    }

    @Test
    fun seriesEpisodesSlideAsOneDirectionalSeasonCarousel() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
        ).readText()
        val episodesSection = source
            .substringAfter("private fun EpisodesSection(")
            .substringBefore("private fun currentEpisodeRailContentId")
        val seasons = listOf(
            Season(contentId = "season-1", seasonNumber = 1),
            Season(contentId = "season-2", seasonNumber = 2),
            Season(contentId = "specials", seasonNumber = 0, isSpecials = true),
        )

        assertEquals(1, seriesEpisodeCarouselDirection(1, 2, seasons))
        assertEquals(-1, seriesEpisodeCarouselDirection(2, 1, seasons))
        assertEquals(1, seriesEpisodeCarouselDirection(2, 0, seasons))
        assertTrue(episodesSection.contains("AnimatedContent("))
        assertTrue(episodesSection.contains("contentKey = { it.seasonNumber }"))
        assertTrue(episodesSection.contains("slideInHorizontally("))
        assertTrue(episodesSection.contains("slideOutHorizontally("))
        assertTrue(episodesSection.contains("SizeTransform(clip = false)"))
        assertTrue(source.contains("SERIES_EPISODE_CAROUSEL_DURATION_MS = 360"))
        assertFalse(episodesSection.contains("durationMillis = 280"))
        assertTrue(
            episodesSection.contains(
                "state.episodesLoading && (!isSeries || state.episodes.isEmpty())",
            ),
        )
    }

    @Test
    fun entryRouteSeasonIsConsumedBeforeFocusDrivenSelection() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
        ).readText()
        val routeSeasonState = source
            .substringAfter("var pendingEntrySeasonNumber")
            .substringBefore("BackHandler(enabled = true)")
        val hydration = source
            .substringAfter(
                "LaunchedEffect(state.detail?.contentId, pendingEntrySeasonNumber, state.seasons) {",
            )
            .substringBefore("\n    LaunchedEffect(\n        state.detail?.contentId,\n        seasonNumber,")

        assertTrue(routeSeasonState.contains("rememberSaveable(contentId, seasonNumber)"))
        assertTrue(
            source.contains(
                "LaunchedEffect(state.detail?.contentId, pendingEntrySeasonNumber, state.seasons) {",
            ),
        )
        assertTrue(hydration.contains("pendingEntrySeasonNumber = null"))
        assertTrue(
            hydration.indexOf("pendingEntrySeasonNumber = null") <
                hydration.lastIndexOf("viewModel.onSeasonSelected(entrySeason)"),
        )
    }

    @Test
    fun synopsisOpensScrollablePopupWithoutExpandingThePage() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvExpandableSynopsis.kt",
        ).readText()

        assertTrue(source.contains("showFullSynopsis = true"))
        assertTrue(source.contains("private fun TvSynopsisDialog("))
        assertTrue(source.contains("PopupProperties(focusable = true, dismissOnBackPress = true)"))
        assertTrue(source.contains(".verticalScroll(scrollState)"))
        assertFalse(source.contains("expanded = !expanded"))
    }

}
