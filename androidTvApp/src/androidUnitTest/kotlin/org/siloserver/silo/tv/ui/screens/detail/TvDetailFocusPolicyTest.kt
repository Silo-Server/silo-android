package org.siloserver.silo.tv.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertTrue(episodeSource.contains("text = \"NOW VIEWING\""))
        assertTrue(episodeSource.contains("fontSize = 14.sp"))
    }

    @Test
    fun playbackSelectionReadoutSitsUnderFactsAndUsesNoPillChrome() {
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
        val playback = editorial.indexOf("playbackSummary?.invoke()")
        val synopsis = editorial.indexOf("TvExpandableSynopsis(")

        assertTrue(facts >= 0)
        assertTrue(facts < playback)
        assertTrue(playback < synopsis)
        assertTrue(detailSource.contains("label = \"VERSION\""))
        assertTrue(detailSource.contains("label = \"AUDIO\""))
        assertTrue(detailSource.contains("label = \"SUBTITLES\""))
        assertTrue(detailSource.contains("includePlaybackFormats = false"))
        assertFalse(playbackReadout.contains("Modifier.weight("))
        assertTrue(playbackReadout.contains("Arrangement.spacedBy(12.dp)"))
        assertTrue(playbackReadout.contains("Modifier.height(TV_PLAYBACK_SUMMARY_HEIGHT)"))
        assertTrue(playbackReadout.contains("TvPlaybackSummarySkeleton(valueWidth)"))
        assertTrue(playbackReadout.contains("modifier = Modifier.width(valueWidth)"))
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
        assertTrue(heroSource.contains("previewText = if (isCombinedSeriesEpisode)"))
        assertFalse(heroSource.contains("Spacer(modifier = Modifier.weight(1f))"))
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
