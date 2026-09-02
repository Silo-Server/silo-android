package org.siloserver.silo.android.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import org.siloserver.silo.common.downloads.DownloadEnqueuer
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.download.DownloadsListResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.DownloadsApi
import org.siloserver.silo.network.api.EbookReaderApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.RecommendationApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.DownloadsRepository
import org.siloserver.silo.repository.EbookReaderRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.RecommendationRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MobileDetailActionsSourceTest {
    private val movieDetail = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MovieDetailContent.kt",
    ).readText()
    private val seriesDetail = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/SeriesDetailContent.kt",
    ).readText()
    private val itemDetail = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailScreen.kt",
    ).readText()
    private val episodeList = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/EpisodeList.kt",
    ).readText()
    private val detailShared = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/DetailSharedComponents.kt",
    ).readText()
    private val mediaSelectors = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MediaSelectors.kt",
    ).readText()
    private val viewModel = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailViewModel.kt",
    ).readText()
    private val mainScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/MainScreen.kt",
    ).readText()
    private val homeScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/HomeScreen.kt",
    ).readText()
    private val bottomNav = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/navigation/BottomNavBar.kt",
    ).readText()
    private val mediaRow = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/components/MediaRow.kt",
    ).readText()
    private val backdropCard = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/components/BackdropCard.kt",
    ).readText()
    private val sharedElementTransition = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/navigation/SharedElementTransition.kt",
    ).readText()
    private val topBarActions = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/components/TopBarActions.kt",
    ).readText()
    private val swipeBack = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/components/SwipeBack.kt",
    ).readText()
    private val appNavigation = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/navigation/AppNavigation.kt",
    ).readText()

    @Test
    fun watchedHeroActionCallsRepositoryInsteadOfNoOp() {
        assertFalse(movieDetail.contains("onToggleWatched = { /* no-op"))
        assertFalse(seriesDetail.contains("onToggleWatched = { /* no-op"))
        assertTrue(itemDetail.contains("onToggleWatched = { viewModel.toggleWatched() }"))
        assertTrue(viewModel.contains("fun toggleWatched()"))
        assertTrue(viewModel.contains("personalDataRepository.setWatched(contentId, target)"))
        assertTrue(viewModel.contains("watchedMutationGeneration"))
        assertTrue(viewModel.contains("if (generation == watchedMutationGeneration)"))
    }

    @Test
    fun watchedTogglePersistsOptimisticStateOnSuccess() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf({ ApiResult.Success(Unit) }),
        )
        val viewModel = itemDetailViewModel(repository)
        viewModel.seedDetail(played = false)

        viewModel.toggleWatched()
        advanceUntilIdle()

        assertEquals(listOf(true), repository.watchedCalls)
        assertEquals(true, viewModel.uiState.value.detail?.userData?.played)
    }

    @Test
    fun watchedToggleRevertsOnLatestFailureOnly() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf(
                {
                    delay(100)
                    ApiResult.Error(500, "failed", "first failed")
                },
                { ApiResult.Error(500, "failed", "second failed") },
            ),
        )
        val viewModel = itemDetailViewModel(repository)
        viewModel.seedDetail(played = false)

        viewModel.toggleWatched()
        viewModel.toggleWatched()
        advanceUntilIdle()

        assertEquals(listOf(true, false), repository.watchedCalls)
        assertEquals(
            true,
            viewModel.uiState.value.detail?.userData?.played,
            "The second failed toggle should roll back to the state it observed; the older first failure must not overwrite it.",
        )
    }

    @Test
    fun cachedSeasonSwitchDoesNotReloadEpisodes() = runItemDetailTest {
        val catalogRequests = mutableListOf<String>()
        val catalogRepository = CatalogRepository(
            CatalogApi(
                HttpClient(
                    MockEngine { request ->
                        catalogRequests += request.url.encodedPath
                        respond("{}")
                    },
                ),
            ),
        )
        val viewModel = itemDetailViewModel(
            personalDataRepository = RecordingPersonalDataRepository(mutableListOf()),
            catalogRepository = catalogRepository,
        )
        viewModel.seedSeriesDetail()

        viewModel.selectSeason(2)
        viewModel.selectSeason(1)
        advanceUntilIdle()

        assertEquals(emptyList(), catalogRequests)
        assertEquals(1, viewModel.uiState.value.selectedSeasonNumber)
        assertEquals(listOf("season-1-episode-1"), viewModel.uiState.value.episodes.map { it.contentId })
    }

    @Test
    fun moviePlayPinsDisplayedVersionWhenTrackOverrideIsSelected() {
        assertTrue(itemDetail.contains("val playbackFileId = explicitFileId ?: detail.versions"))
        assertTrue(itemDetail.contains(".getOrNull(effectiveSelectedVersionIndex)"))
        assertTrue(itemDetail.contains("?.takeIf { state.hasExplicitAudioSelection || state.hasExplicitSubtitleSelection }"))
        assertTrue(itemDetail.contains("playbackFileId,"))
        assertTrue(itemDetail.contains("explicitAudioIndex,"))
        assertTrue(itemDetail.contains("explicitSubtitleIndex,"))
    }

    @Test
    fun settledEpisodeCarouselSelectionDrivesHeroPlaybackState() {
        assertTrue(seriesDetail.contains("selectsCenteredEpisode = !showsEpisodeDetails"))
        assertTrue(episodeList.contains("snapshotFlow { listState.isScrollInProgress }"))
        assertTrue(episodeList.contains("currentOnSelect(episode.contentId)"))
        assertTrue(itemDetail.contains("onEpisodeDetailClick = { viewModel.selectSeriesEpisode(it) }"))
        assertTrue(seriesDetail.contains("selectedEpisode?.let { \"Play \${episodeNumberText(it)}\" }"))
        assertTrue(seriesDetail.contains("loadedSelectedEpisodeDetail.versions.getOrNull(selectedVersionIndex)"))
        assertTrue(seriesDetail.contains("loadedSelectedEpisodeDetail?.let { episodeDetail ->"))
    }

    @Test
    fun selectedEpisodeResumeNeverFallsBackToAnotherEpisode() {
        assertTrue(itemDetail.contains("val activeSeriesResume = if (selectedEpisode != null)"))
        assertFalse(itemDetail.contains("val activeSeriesResume = selectedEpisodeResume ?: seriesResume"))
    }

    @Test
    fun episodeLongPressOffersAndPersistsWatchedState() = runItemDetailTest {
        assertTrue(episodeList.contains("Mark as Watched"))
        assertTrue(episodeList.contains("Mark as Unwatched"))
        assertTrue(episodeList.contains("onLongClick"))

        val repository = RecordingPersonalDataRepository(
            mutableListOf({ ApiResult.Success(Unit) }),
        )
        val viewModel = itemDetailViewModel(repository)
        viewModel.seedSeriesDetail()

        viewModel.setEpisodeWatched("season-1-episode-1", true)
        advanceUntilIdle()

        assertEquals(listOf(true), repository.watchedCalls)
        assertEquals(true, viewModel.uiState.value.episodes.single().userData?.played)
        assertEquals(
            true,
            viewModel.uiState.value.episodesBySeason.getValue(1).single().userData?.played,
        )
    }

    @Test
    fun episodePageHeroRepaintsAfterLongPressWatchedChange() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf({ ApiResult.Success(Unit) }),
        )
        val viewModel = itemDetailViewModel(repository)
        viewModel.seedEpisodeDetail()

        viewModel.setEpisodeWatched("season-1-episode-1", true)
        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.detail?.userData?.played)
        assertEquals(true, viewModel.uiState.value.episodes.single().userData?.played)
    }

    @Test
    fun tappedArtworkWarmsTheDetailBackgroundDuringMetadataLoad() {
        assertTrue(sharedElementTransition.contains("pendingArtworkUrl"))
        assertTrue(mediaRow.contains("item.backdropUrl ?: item.posterUrl"))
        assertTrue(appNavigation.contains("openingArtworkUrl"))
        assertTrue(itemDetail.contains("artworkUrl = openingArtworkUrl"))
        assertTrue(detailShared.contains("DetailArtworkCrossfadeMs = 120"))
    }

    @Test
    fun continueWatchingUsesConfiguredWideCardOverlays() {
        assertTrue(mediaRow.contains("overlay = rowItem.overlay"))
        assertTrue(backdropCard.contains("overlayState.enabled && overlay != null"))
        assertTrue(backdropCard.contains("variant = CardOverlayVariant.Wide"))
    }

    @Test
    fun selectingSeriesEpisodeImmediatelyResetsItsPlaybackOverrides() = runItemDetailTest {
        val viewModel = itemDetailViewModel(
            personalDataRepository = RecordingPersonalDataRepository(mutableListOf()),
        )
        viewModel.seedSeriesDetail()

        viewModel.selectVersion(1)
        viewModel.selectAudioTrack(2)
        viewModel.selectSubtitle(3)
        viewModel.selectSeriesEpisode("season-1-episode-1")

        val state = viewModel.uiState.value
        assertEquals("season-1-episode-1", state.selectedEpisodeContentId)
        assertEquals(null, state.selectedEpisodeDetail)
        assertTrue(state.isLoadingSelectedEpisodeDetail)
        assertEquals(0, state.selectedVersionIndex)
        assertEquals(0, state.selectedAudioIndex)
        assertEquals(-1, state.selectedSubtitleIndex)
        assertFalse(state.hasExplicitVersionSelection)
        assertFalse(state.hasExplicitAudioSelection)
        assertFalse(state.hasExplicitSubtitleSelection)
    }

    @Test
    fun selectedEpisodeReplacesCompactSeriesEditorialAndRailKeepsCompactIdentity() {
        assertTrue(seriesDetail.contains("} else if (usesEpisodeEditorial) {"))
        assertTrue(seriesDetail.contains("selectedEpisodeOverview"))
        assertTrue(seriesDetail.contains("belowOverview = if (isExpandedDetailLayout) null else playbackSelector"))
        assertTrue(
            seriesDetail.contains(
                "directorText = fixedSeriesCredit",
            ),
        )
        assertTrue(seriesDetail.contains("remember(detail.contentId, detail.cast) { seriesStarringCredit(detail) }"))
        assertFalse(seriesDetail.contains("selectedEpisodeCredit"))
        assertFalse(seriesDetail.contains("SelectedEpisodeInfo"))

        val railCard = episodeList
            .substringAfter("private fun EpisodeRailCard")
            .substringBefore("internal fun episodeProgressFraction")
        assertTrue(railCard.contains("EPISODE"))
        assertTrue(railCard.contains("NOW VIEWING"))
        assertTrue(episodeList.contains("showsEpisodeDetails: Boolean = false"))
        assertTrue(railCard.contains("if (showsEpisodeDetails)"))
        assertTrue(railCard.contains("episode.title"))
        assertTrue(railCard.contains("episodeMetadataLine"))
        assertTrue(railCard.contains("episode.overview"))
    }

    @Test
    fun fixedSeriesCreditAndPlaybackSelectorKeepStableFootprints() {
        assertTrue(seriesDetail.contains("PlaybackSelectorSkeleton()"))
        assertFalse(seriesDetail.contains("\"Loading…\""))
        assertTrue(seriesDetail.contains("reserveCreditSpace = !isExpandedDetailLayout && usesEpisodeEditorial"))
        assertTrue(seriesDetail.contains("isCreditLoading = false"))
        assertTrue(seriesDetail.contains("reserveOverviewSpace = !isExpandedDetailLayout && usesEpisodeEditorial"))
        assertTrue(detailShared.contains(".height(38.dp)"))
        assertTrue(detailShared.contains("minLines = if (reserveCollapsedSpace && !expanded) 3 else 1"))
        assertTrue(mediaSelectors.contains("repeat(3)"))
        // Rows and the card keep their loading footprint as a floor but grow
        // when a Version / Audio / Subtitles value wraps instead of truncating.
        assertTrue(mediaSelectors.contains(".heightIn(min = 44.dp)"))
        assertTrue(mediaSelectors.contains(".heightIn(min = 133.dp)"))
        assertFalse(mediaSelectors.contains("overflow = TextOverflow.Ellipsis,\n            fontWeight = FontWeight.SemiBold"))
    }

    @Test
    fun tabletHeroKeepsActionsWithinPosterAndCentersEditorialBelowWithoutChangingPhoneOrder() {
        val expandedHero = detailShared
            .substringAfter("private fun ExpandedDetailHero")
            .substringBefore("private fun ExpandedHeroTitle")
        val tabletActions = expandedHero.indexOf("actions()")
        val tabletOverview = expandedHero.indexOf("OverviewBlock(")
        val tabletCredit = expandedHero.indexOf("DetailCreditBlock(")
        val tabletSelector = expandedHero.indexOf("belowOverview?.invoke()")
        assertTrue(expandedHero.contains("Modifier.height(posterHeight)"))
        assertTrue(expandedHero.contains("contentAlignment = Alignment.TopCenter"))
        assertTrue(expandedHero.contains("1.00f to pageSurface"))
        assertTrue(expandedHero.contains(".padding(top = 24.dp, bottom = 40.dp)"))
        assertTrue(tabletActions >= 0)
        assertTrue(tabletOverview > tabletActions)
        assertTrue(tabletCredit > tabletOverview)
        assertTrue(tabletSelector > tabletCredit)

        val phoneHero = detailShared
            .substringAfter("fun DetailHero(")
            .substringBefore("private fun DetailCreditBlock")
        val phoneActions = phoneHero.indexOf("actions()")
        val phoneOverview = phoneHero.indexOf("OverviewBlock(")
        val phoneSelector = phoneHero.indexOf("belowOverview?.invoke()")
        assertTrue(phoneActions >= 0)
        assertTrue(phoneOverview > phoneActions)
        assertTrue(phoneSelector > phoneOverview)
    }

    @Test
    fun tabletSeriesUsesMainPlotAndMovesPlaybackSelectorsBelowEpisodes() {
        assertTrue(seriesDetail.contains("val isExpandedDetailLayout = maxWidth >= ExpandedDetailBreakpoint"))
        assertTrue(
            seriesDetail.contains(
                "overviewText = if (isExpandedDetailLayout) {\n                    detail.overview",
            ),
        )
        assertTrue(seriesDetail.contains("belowOverview = if (isExpandedDetailLayout) null else playbackSelector"))
        assertTrue(seriesDetail.contains("eyebrow = if (isExpandedDetailLayout) null else eyebrow"))
        assertTrue(seriesDetail.contains("expandedBelowOverview = {"))
        assertTrue(seriesDetail.contains("episodeSection(true)"))
        assertTrue(seriesDetail.contains("episodeSection(false)"))

        val expandedEpisodes = seriesDetail.indexOf("episodeSection(true)")
        val expandedSelectors = seriesDetail.indexOf("playbackSelector()", startIndex = expandedEpisodes)
        assertTrue(expandedEpisodes >= 0)
        assertTrue(expandedSelectors > expandedEpisodes)
        assertFalse(seriesDetail.contains("item(contentType = \"detail-playback-selectors\")"))

        val expandedHero = detailShared
            .substringAfter("private fun ExpandedDetailHero")
            .substringBefore("private fun ExpandedHeroTitle")
        assertTrue(expandedHero.contains("horizontalAlignment = Alignment.CenterHorizontally"))
        assertTrue(expandedHero.contains("horizontalAlignment = Alignment.CenterHorizontally,"))
        assertTrue(expandedHero.contains("contentAlignment = Alignment.TopCenter"))
        assertTrue(expandedHero.contains("belowOverview?.invoke()"))

        val phoneHero = detailShared
            .substringAfter("fun DetailHero(")
            .substringBefore("private fun DetailCreditBlock")
        assertTrue(phoneHero.contains("belowOverview?.invoke()"))

        val creditBlock = detailShared
            .substringAfter("private fun DetailCreditBlock")
            .substringBefore("private fun Backdrop")
        assertTrue(creditBlock.contains("textAlign = TextAlign.Start"))
    }

    @Test
    fun tabletEpisodeRailUsesTapFocusWithoutChangingMobileCentreSelection() {
        assertTrue(seriesDetail.contains("selectsCenteredEpisode = !showsEpisodeDetails"))
        assertTrue(seriesDetail.contains("tapToFocusEpisode = showsEpisodeDetails"))
        assertTrue(seriesDetail.contains("text = \"Tap to focus\""))
        assertTrue(episodeList.contains("tapToFocusEpisode: Boolean = false"))
        assertTrue(episodeList.contains("if (tapToFocusEpisode)"))
        assertTrue(episodeList.contains("contentPadding = PaddingValues(horizontal = SafePadding"))
        assertFalse(episodeList.contains("((maxWidth - cardWidth) / 2)"))

        val railCard = episodeList
            .substringAfter("private fun EpisodeRailCard")
            .substringBefore("internal fun episodeProgressFraction")
        assertTrue(railCard.contains("onClick = onSelect"))
        assertTrue(railCard.contains(".clickable(onClick = onPlayClick)"))
        assertTrue(railCard.contains("vertical = if (showsEpisodeDetails) 1.dp else 2.dp"))
    }

    @Test
    fun homeReselectOnlyExpandsChromeAndHomeChromeUsesDetailMaterial() {
        assertFalse(mainScreen.contains("homeScrollToTopTick"))
        assertFalse(homeScreen.contains("scrollToTopTick"))
        assertTrue(mainScreen.contains("homeBottomNavMinimized = false"))
        assertTrue(bottomNav.contains(".background(SiloDetailActionControlActive)"))
        assertTrue(bottomNav.contains(".border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)"))
        assertTrue(bottomNav.contains("Modifier.size(25.dp)"))
        assertTrue(bottomNav.contains("Color.White.copy(alpha = 0.28f)"))
        assertTrue(bottomNav.contains("Color.White.copy(alpha = 0.46f)"))
        assertTrue(bottomNav.contains("PillExpandedMaxWidth = 380.dp"))
        assertTrue(bottomNav.contains("PillExpandedWindowBreakpoint = 560.dp"))
        assertTrue(bottomNav.contains("maxWidth.coerceAtMost(PillExpandedMaxWidth)"))
        assertTrue(
            bottomNav.contains(
                ".align(if (isExpandedWindow) Alignment.Center else Alignment.CenterStart)",
            ),
        )
        assertTrue(bottomNav.contains("Alignment.CenterStart"))
        assertTrue(bottomNav.contains("Alignment.Center"))
        assertTrue(topBarActions.contains("opaque -> SiloDetailActionControlActive"))
        assertTrue(topBarActions.contains("contentColor = if (opaque) Color.White"))
    }

    @Test
    fun detailChromeStaysFixedAndAllClosePathsUseSmoothDownwardDismissal() {
        assertTrue(itemDetail.contains("rememberSwipeDownDismissState()"))
        assertTrue(itemDetail.contains("BackHandler { requestDismiss() }"))
        assertTrue(itemDetail.contains("onClick = requestDismiss"))
        assertFalse(itemDetail.contains("topControlTranslationY"))
        assertTrue(itemDetail.contains(".statusBarsPadding()"))
        assertTrue(itemDetail.contains("SiloDetailActionControlActive.copy(alpha = 0.38f)"))
        assertTrue(itemDetail.contains("Color.White.copy(alpha = 0.36f)"))
        assertTrue(swipeBack.contains("class SwipeDownDismissState"))
        assertTrue(swipeBack.contains("offset.animateTo("))
        assertTrue(swipeBack.contains("dismissing = true"))
        assertTrue(swipeBack.contains("state.dismiss(onDismiss = currentOnDismiss)"))
        assertFalse(swipeBack.contains("initialVelocity = initialVelocity.coerceAtLeast(0f)"))
        assertTrue(appNavigation.contains("DetailCardOpenDurationMs = 600"))
        assertTrue(appNavigation.contains("DetailCardCloseDurationMs = 440"))
        assertTrue(appNavigation.contains("CubicBezierEasing(0.32f, 0.00f, 0.20f, 1.00f)"))
        assertTrue(appNavigation.contains("CubicBezierEasing(0.40f, 0.00f, 0.20f, 1.00f)"))
        assertTrue(appNavigation.contains("slideOutVertically("))
        assertTrue(appNavigation.contains("delayMillis = DetailCardOpenDurationMs - 180"))
        assertFalse(appNavigation.contains("DetailCardCloseHoldDurationMs"))
        assertTrue(swipeBack.contains("internal var offsetPx by mutableFloatStateOf(0f)"))
        assertFalse(swipeBack.contains("scope.launch { state.offset.snapTo"))
        assertFalse(appNavigation.contains("popExitTransition = { ExitTransition.None }"))
    }

    @Test
    fun openingAndClosingDetailPreservesTheSourceRowPosition() {
        assertFalse(mediaRow.contains("settledBrowseContentId"))
        assertFalse(mediaRow.contains("animateScrollToItem(index)"))
        assertFalse(appNavigation.contains("settledBrowseContentId"))
        assertFalse(sharedElementTransition.contains("settledBrowseContentId"))
    }

    @Test
    fun liveDetailRequestStartsBeforeDurableCacheRead() {
        val liveRequest = viewModel.indexOf("val liveDetail = async")
        val cacheRead = viewModel.indexOf("seedCachedDetail()", startIndex = liveRequest)
        val liveAwait = viewModel.indexOf("liveDetail.await()", startIndex = cacheRead)
        assertTrue(liveRequest >= 0)
        assertTrue(cacheRead > liveRequest)
        assertTrue(liveAwait > cacheRead)
    }

    private fun runItemDetailTest(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun itemDetailViewModel(
        personalDataRepository: RecordingPersonalDataRepository,
        catalogRepository: CatalogRepository = CatalogRepository(CatalogApi(dummyHttpClient())),
    ): ItemDetailViewModel =
        ItemDetailViewModel(
            catalogRepository = catalogRepository,
            personalDataRepository = personalDataRepository,
            downloadsRepository = DownloadsRepository(EmptyDownloadsApi()),
            downloadEnqueuer = unsafeInstance(),
            ebookReaderRepository = EbookReaderRepository(EbookReaderApi(dummyHttpClient())),
            recommendationRepository = RecommendationRepository(RecommendationApi(dummyHttpClient())),
            metadataAiRepository = org.siloserver.silo.repository.MetadataAiRepository(
                org.siloserver.silo.network.api.DefaultMetadataAiApi(dummyHttpClient()),
            ),
            savedStateHandle = SavedStateHandle(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun ItemDetailViewModel.seedSeriesDetail() {
        val field = ItemDetailViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val flow = field.get(this) as MutableStateFlow<ItemDetailUiState>
        val seasonOneEpisodes = listOf(
            EpisodeListItem(
                contentId = "season-1-episode-1",
                seasonNumber = 1,
                episodeNumber = 1,
            ),
        )
        val seasonTwoEpisodes = listOf(
            EpisodeListItem(
                contentId = "season-2-episode-1",
                seasonNumber = 2,
                episodeNumber = 1,
            ),
        )
        flow.value = ItemDetailUiState(
            isLoading = false,
            detail = ItemDetail(
                contentId = "series-1",
                type = "series",
                title = "Series",
            ),
            seasons = listOf(
                Season(contentId = "season-1", seasonNumber = 1),
                Season(contentId = "season-2", seasonNumber = 2),
            ),
            selectedSeasonNumber = 1,
            episodes = seasonOneEpisodes,
            episodesBySeason = mapOf(
                1 to seasonOneEpisodes,
                2 to seasonTwoEpisodes,
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun ItemDetailViewModel.seedDetail(played: Boolean) {
        val field = ItemDetailViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val flow = field.get(this) as MutableStateFlow<ItemDetailUiState>
        flow.value = ItemDetailUiState(
            isLoading = false,
            detail = ItemDetail(
                contentId = "movie-1",
                type = "movie",
                title = "Movie",
                userData = LeafItemUserData(played = played),
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun ItemDetailViewModel.seedEpisodeDetail() {
        val field = ItemDetailViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val flow = field.get(this) as MutableStateFlow<ItemDetailUiState>
        val episode = EpisodeListItem(
            contentId = "season-1-episode-1",
            seasonNumber = 1,
            episodeNumber = 1,
            userData = LeafItemUserData(played = false),
        )
        flow.value = ItemDetailUiState(
            isLoading = false,
            detail = ItemDetail(
                contentId = episode.contentId,
                type = "episode",
                title = "Episode",
                userData = LeafItemUserData(played = false),
            ),
            selectedSeasonNumber = 1,
            episodes = listOf(episode),
            episodesBySeason = mapOf(1 to listOf(episode)),
        )
    }

    private class RecordingPersonalDataRepository(
        private val responses: MutableList<suspend () -> ApiResult<Unit>>,
    ) : PersonalDataRepository(PersonalDataApi(dummyHttpClient())) {
        val watchedCalls = mutableListOf<Boolean>()

        override suspend fun setWatched(itemId: String, watched: Boolean): ApiResult<Unit> {
            watchedCalls += watched
            return responses.removeFirstOrNull()?.invoke() ?: ApiResult.Success(Unit)
        }
    }

    private class EmptyDownloadsApi : DownloadsApi(dummyHttpClient()) {
        override suspend fun list(): ApiResult<DownloadsListResponse> =
            ApiResult.Success(DownloadsListResponse())
    }

    private inline fun <reified T : Any> unsafeInstance(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }

    companion object {
        private fun dummyHttpClient(): HttpClient =
            HttpClient(MockEngine { respond("{}") })
    }
}
