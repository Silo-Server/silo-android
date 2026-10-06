package org.siloserver.silo.android.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.download.DownloadsListResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.repository.port.PersonalWrite
import org.siloserver.silo.repository.port.PersonalWriteHandle
import org.siloserver.silo.repository.port.UserItemStatePort
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
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MobileDetailActionsTest {
    @Test
    fun watchedTogglePersistsOptimisticStateOnSuccess() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf({ ApiResult.Success(Unit) }),
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val viewModel = itemDetailViewModel(repository, contentId = "movie-1")
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
            // The first reply's delay must be virtual time so the second toggle overtakes it.
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val viewModel = itemDetailViewModel(repository, contentId = "movie-1")
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
    fun markingEpisodeWatchedUpdatesTheEpisodeAndSeasonCache() = runItemDetailTest {

        val repository = RecordingPersonalDataRepository(
            mutableListOf({ ApiResult.Success(Unit) }),
            engineDispatcher = StandardTestDispatcher(testScheduler),
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
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val viewModel = itemDetailViewModel(repository)
        viewModel.seedEpisodeDetail()

        viewModel.setEpisodeWatched("season-1-episode-1", true)
        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.detail?.userData?.played)
        assertEquals(true, viewModel.uiState.value.episodes.single().userData?.played)
    }

    @Test
    fun markingSeasonWatchedWritesTheSeasonAndRefreshesWhatItChanged() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf({ ApiResult.Success(Unit) }),
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val catalogRequests = mutableListOf<String>()
        val viewModel = itemDetailViewModel(repository, recordingCatalogRepository(catalogRequests))
        viewModel.seedSeriesDetail()
        // uiState is a derived flow; let it observe the seeded state.
        runCurrent()

        viewModel.setSeasonWatched(viewModel.uiState.value.seasons[1], true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("/api/v2/watched/season-2"), repository.watchedPaths)
        assertEquals(listOf(true), repository.watchedCalls)
        assertEquals(true, state.seasons[1].userData?.played)
        assertFalse(state.seasons[0].userData?.played == true)
        assertFalse(state.episodes.single().userData?.played == true)
        assertTrue("/api/v2/catalog/series/series-1/seasons" in catalogRequests)
        assertTrue("/api/v2/catalog/series/series-1/seasons/1/episodes" in catalogRequests)
        // The changed season reloads when it is next selected; the visible one is kept.
        assertEquals(setOf(1), state.episodesBySeason.keys)
    }

    @Test
    fun markingVisibleSeasonWatchedChecksItsEpisodes() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf({ ApiResult.Success(Unit) }),
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val viewModel = itemDetailViewModel(repository, recordingCatalogRepository(mutableListOf()))
        viewModel.seedSeriesDetail()
        // uiState is a derived flow; let it observe the seeded state.
        runCurrent()

        viewModel.setSeasonWatched(viewModel.uiState.value.seasons[0], true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("/api/v2/watched/season-1"), repository.watchedPaths)
        assertEquals(true, state.episodes.single().userData?.played)
        assertEquals(true, state.episodesBySeason.getValue(1).single().userData?.played)
        // Season 2 was not changed, so its cached episodes stay.
        assertFalse(state.episodesBySeason.getValue(2).single().userData?.played == true)
    }

    @Test
    fun failedSeasonWatchedRestoresTheSeasonAndItsEpisodes() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf({ ApiResult.Error(500, "failed", "season failed") }),
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val catalogRequests = mutableListOf<String>()
        val viewModel = itemDetailViewModel(repository, recordingCatalogRepository(catalogRequests))
        viewModel.seedSeriesDetail()
        // uiState is a derived flow; let it observe the seeded state.
        runCurrent()

        viewModel.setSeasonWatched(viewModel.uiState.value.seasons[0], true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("/api/v2/watched/season-1"), repository.watchedPaths)
        assertFalse(state.seasons[0].userData?.played == true)
        assertFalse(state.episodes.single().userData?.played == true)
        assertFalse(state.episodesBySeason.getValue(1).single().userData?.played == true)
        assertEquals(emptyList(), catalogRequests)
    }

    @Test
    fun failedSeasonWatchedKeepsEpisodesMarkedWhileItWasPending() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf(
                {
                    delay(100)
                    ApiResult.Error(500, "failed", "season failed")
                },
                { ApiResult.Success(Unit) },
            ),
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val viewModel = itemDetailViewModel(repository, recordingCatalogRepository(mutableListOf()))
        viewModel.seedSeriesDetail(
            seasonOneEpisodes = listOf(
                EpisodeListItem(contentId = "s1e1", seasonNumber = 1, episodeNumber = 1),
                EpisodeListItem(
                    contentId = "s1e2",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    userData = LeafItemUserData(played = true),
                ),
            ),
        )
        runCurrent()

        viewModel.setSeasonWatched(viewModel.uiState.value.seasons[0], true)
        runCurrent()
        viewModel.setEpisodeWatched("s1e2", false)
        advanceUntilIdle()

        assertEquals(listOf("/api/v2/watched/season-1", "/api/v2/watched/s1e2"), repository.watchedPaths)
        val state = viewModel.uiState.value
        assertFalse(state.seasons[0].userData?.played == true)
        val played = state.episodes.associate { it.contentId to (it.userData?.played == true) }
        // E1 reverts with the failed season write; E2 keeps its own successful change.
        assertEquals(mapOf("s1e1" to false, "s1e2" to false), played)
    }

    @Test
    fun failedSeasonAndEpisodeWritesBothRollBack() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf(
                {
                    delay(100)
                    ApiResult.Error(500, "failed", "season failed")
                },
                { ApiResult.Error(500, "failed", "episode failed") },
            ),
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val viewModel = itemDetailViewModel(repository, recordingCatalogRepository(mutableListOf()))
        viewModel.seedSeriesDetail()
        runCurrent()

        viewModel.setSeasonWatched(viewModel.uiState.value.seasons[0], true)
        runCurrent()
        viewModel.setEpisodeWatched("season-1-episode-1", false)
        advanceUntilIdle()

        // The episode's failed write rolls back to the season's optimistic state;
        // the season rollback must then still undo that.
        assertFalse(viewModel.uiState.value.episodes.single().userData?.played == true)
        assertFalse(viewModel.uiState.value.seasons[0].userData?.played == true)
    }

    @Test
    fun markingSeasonWatchedClearsTheVisibleResumePoint() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf({ ApiResult.Success(Unit) }),
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        // Every catalog read fails, so the post-write refresh cannot correct the list.
        val viewModel = itemDetailViewModel(repository, recordingCatalogRepository(mutableListOf()))
        viewModel.seedSeriesDetail(
            seasonOneEpisodes = listOf(
                EpisodeListItem(
                    contentId = "s1e1",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    userData = LeafItemUserData(isInProgress = true, positionSeconds = 120.0, durationSeconds = 1000.0),
                ),
            ),
        )
        runCurrent()

        viewModel.setSeasonWatched(viewModel.uiState.value.seasons[0], true)
        advanceUntilIdle()

        val episode = viewModel.uiState.value.episodes.single()
        assertEquals(true, episode.userData?.played)
        assertEquals(null, episode.userData?.positionSeconds)
        assertFalse(episode.userData?.isInProgress == true)
    }

    @Test
    fun failedSeasonWriteDoesNotUndoANewerSeriesWrite() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf(
                {
                    delay(100)
                    ApiResult.Error(500, "failed", "season failed")
                },
                { ApiResult.Success(Unit) },
            ),
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val viewModel = itemDetailViewModel(
            repository,
            recordingCatalogRepository(mutableListOf()),
            contentId = "series-1",
        )
        viewModel.seedSeriesDetail()
        runCurrent()

        viewModel.setSeasonWatched(viewModel.uiState.value.seasons[0], true)
        runCurrent()
        viewModel.toggleWatched()
        advanceUntilIdle()

        assertEquals(listOf("/api/v2/watched/season-1", "/api/v2/watched/series-1"), repository.watchedPaths)
        val state = viewModel.uiState.value
        assertEquals(true, state.detail?.userData?.played)
        assertEquals(true, state.seasons[0].userData?.played)
        assertEquals(true, state.episodes.single().userData?.played)
    }

    @Test
    fun seriesWriteAppliesToAnEpisodeWhoseNewerWriteFailed() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf(
                {
                    delay(100)
                    ApiResult.Success(Unit)
                },
                { ApiResult.Error(500, "failed", "episode failed") },
            ),
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        // Every catalog read fails, so only the series callback can update the episode.
        val viewModel = itemDetailViewModel(
            repository,
            recordingCatalogRepository(mutableListOf()),
            contentId = "series-1",
        )
        viewModel.seedSeriesDetail()
        runCurrent()

        viewModel.toggleWatched()
        runCurrent()
        viewModel.setEpisodeWatched("season-1-episode-1", true)
        advanceUntilIdle()

        assertEquals(listOf("/api/v2/watched/series-1", "/api/v2/watched/season-1-episode-1"), repository.watchedPaths)
        assertEquals(true, viewModel.uiState.value.episodes.single().userData?.played)
    }

    @Test
    fun seriesWriteKeepsAnEpisodeWrittenSuccessfullyWhileItWasPending() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf(
                {
                    delay(100)
                    ApiResult.Success(Unit)
                },
                { ApiResult.Success(Unit) },
                { ApiResult.Error(500, "failed", "episode failed") },
            ),
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val viewModel = itemDetailViewModel(
            repository,
            recordingCatalogRepository(mutableListOf()),
            contentId = "series-1",
        )
        viewModel.seedSeriesDetail(
            seasonOneEpisodes = listOf(
                EpisodeListItem(
                    contentId = "s1e1",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    userData = LeafItemUserData(played = true),
                ),
            ),
        )
        runCurrent()

        viewModel.toggleWatched()
        runCurrent()
        viewModel.setEpisodeWatched("s1e1", false)
        runCurrent()
        viewModel.setEpisodeWatched("s1e1", true)
        advanceUntilIdle()

        // The successful unmark stands; the failed re-mark and the later series
        // response do not overwrite it.
        assertFalse(viewModel.uiState.value.episodes.single().userData?.played == true)
    }

    @Test
    fun markingSeriesWatchedRefreshesSeasonsAndVisibleEpisodes() = runItemDetailTest {
        val repository = RecordingPersonalDataRepository(
            mutableListOf({ ApiResult.Success(Unit) }),
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val catalogRequests = mutableListOf<String>()
        val viewModel = itemDetailViewModel(
            repository,
            recordingCatalogRepository(catalogRequests),
            contentId = "series-1",
        )
        viewModel.seedSeriesDetail()

        viewModel.toggleWatched()
        advanceUntilIdle()

        assertEquals(listOf("/api/v2/watched/series-1"), repository.watchedPaths)
        assertEquals(true, viewModel.uiState.value.detail?.userData?.played)
        assertTrue("/api/v2/catalog/series/series-1/seasons" in catalogRequests)
        assertTrue("/api/v2/catalog/series/series-1/seasons/1/episodes" in catalogRequests)
        assertEquals(setOf(1), viewModel.uiState.value.episodesBySeason.keys)
    }

    @Test
    fun selectingSeriesEpisodeImmediatelyResetsItsPlaybackOverrides() = runItemDetailTest {
        val viewModel = itemDetailViewModel(
            personalDataRepository = RecordingPersonalDataRepository(mutableListOf()),
            catalogRepository = pendingCatalogRepository(),
        )
        viewModel.seedSeriesDetail()

        viewModel.selectVersion(1)
        viewModel.selectAudioTrack(2)
        viewModel.selectSubtitle(3)
        viewModel.selectSeriesEpisode("season-1-episode-1")
        // uiState is a derived flow; let it observe the synchronous reset.
        runCurrent()

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

    private val viewModels = mutableListOf<ViewModel>()

    /**
     * Every mock engine in a test runs on the scheduler dispatcher, so no
     * response can arrive from a real thread after Main is reset — that
     * dispatches into a missing Main dispatcher and fails the test at random.
     */
    private fun runItemDetailTest(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            viewModels.forEach { it.viewModelScope.cancel() }
            viewModels.clear()
            Dispatchers.resetMain()
        }
    }

    /** Records catalog paths; every read fails to decode, so state stays as seeded. */
    private fun kotlinx.coroutines.test.TestScope.recordingCatalogRepository(
        requests: MutableList<String>,
    ): CatalogRepository = CatalogRepository(
        CatalogApi(
            dummyHttpClient(StandardTestDispatcher(testScheduler)) { request ->
                requests += request.url.encodedPath
                respond("{}")
            },
        ),
    )

    /** A catalog client that never answers, so a selection's loading flag stays observable. */
    private fun kotlinx.coroutines.test.TestScope.pendingCatalogRepository(): CatalogRepository = CatalogRepository(
        CatalogApi(dummyHttpClient(StandardTestDispatcher(testScheduler)) { CompletableDeferred<Unit>().await(); respond("{}") }),
    )

    private fun kotlinx.coroutines.test.TestScope.itemDetailViewModel(
        personalDataRepository: RecordingPersonalDataRepository,
        catalogRepository: CatalogRepository? = null,
        contentId: String? = null,
    ): ItemDetailViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ItemDetailViewModel(
            catalogRepository = catalogRepository ?: CatalogRepository(CatalogApi(dummyHttpClient(dispatcher))),
            personalDataRepository = personalDataRepository,
            downloadsRepository = DownloadsRepository(EmptyDownloadsApi(dispatcher)),
            downloadEnqueuer = unsafeInstance(),
            ebookReaderRepository = dummyEbookReaderRepository(dispatcher),
            recommendationRepository = RecommendationRepository(RecommendationApi(dummyHttpClient(dispatcher))),
            metadataAiRepository = org.siloserver.silo.repository.MetadataAiRepository(
                org.siloserver.silo.network.api.DefaultMetadataAiApi(dummyHttpClient(dispatcher), gate = org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted),
            ),
            savedStateHandle = SavedStateHandle(contentId?.let { mapOf("contentId" to it) } ?: emptyMap()),
        ).also { viewModels += it }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ItemDetailViewModel.seedSeriesDetail(
        seasonOneEpisodes: List<EpisodeListItem> = listOf(
            EpisodeListItem(
                contentId = "season-1-episode-1",
                seasonNumber = 1,
                episodeNumber = 1,
            ),
        ),
    ) {
        val field = ItemDetailViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val flow = field.get(this) as MutableStateFlow<ItemDetailUiState>
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

    /**
     * Watched writes are v2 personal writes: the repository pins a
     * [PersonalWriteHandle] from the port, then PUTs/DELETEs
     * `/api/v2/watched/{id}` under that scope. Each queued response answers
     * one write in order; anything else is a 204.
     */
    private class RecordingPersonalDataRepository private constructor(
        private val responses: MutableList<suspend () -> ApiResult<Unit>>,
        private val scope: AuthScopeSnapshot,
        private val engineDispatcher: kotlinx.coroutines.CoroutineDispatcher?,
        val watchedCalls: MutableList<Boolean>,
        val watchedPaths: MutableList<String>,
    ) : PersonalDataRepository(
        personalDataApi = PersonalDataApi(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        engineDispatcher?.let { dispatcher = it }
                        addHandler { request ->
                            if (!request.url.encodedPath.startsWith("/api/v2/watched/")) {
                                return@addHandler respond("", HttpStatusCode.NotFound)
                            }
                            watchedCalls += request.method == HttpMethod.Post
                            watchedPaths += request.url.encodedPath
                            when (val outcome = responses.removeFirstOrNull()?.invoke() ?: ApiResult.Success(Unit)) {
                                is ApiResult.Success -> respond("", HttpStatusCode.NoContent)
                                is ApiResult.Error -> respond(
                                    """{"type":"about:blank","title":"${outcome.error}","status":${outcome.code},"detail":"${outcome.message}"}""",
                                    HttpStatusCode.fromValue(outcome.code),
                                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                                )
                                is ApiResult.NetworkError -> throw outcome.exception
                            }
                        }
                    },
                ),
            ) { install(ContentNegotiation) { json(SiloJson) } },
            tokenManager = object : TokenManager by TokenManagerImpl() {
                override suspend fun snapshotCurrentScope() = scope
            },
        ),
        userItemStatePort = object : UserItemStatePort by NoOpUserItemStatePort {
            override suspend fun beginPersonalWrite(command: PersonalWrite) = PersonalWriteHandle(1L, scope, command)
        },
    ) {
        constructor(
            responses: MutableList<suspend () -> ApiResult<Unit>>,
            engineDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
        ) : this(
            responses,
            AuthScopeSnapshot("s1", "p1", "https://silo.example", "pt", identityGeneration = 1),
            engineDispatcher,
            mutableListOf(),
            mutableListOf(),
        )
    }

    private object NoDevices : org.siloserver.silo.network.DeviceMetadataProvider {
        override suspend fun current(): org.siloserver.silo.network.SiloDeviceMetadata? = null
    }

    private class EmptyDownloadsApi(dispatcher: CoroutineDispatcher) : DownloadsApi(
        registry = org.siloserver.silo.network.apiv2.DownloadRegistryV2Api(dummyHttpClient(dispatcher), org.siloserver.silo.network.TokenManagerImpl(), NoDevices, org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted),
        tokens = org.siloserver.silo.network.TokenManagerImpl(),
        creation = org.siloserver.silo.network.apiv2.DownloadCreationV2Api(dummyHttpClient(dispatcher), org.siloserver.silo.network.TokenManagerImpl(), NoDevices,
            org.siloserver.silo.network.apiv2.DownloadRegistryV2Api(dummyHttpClient(dispatcher), org.siloserver.silo.network.TokenManagerImpl(), NoDevices, org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted), org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted),
    ) {
        override suspend fun list(scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<DownloadsListResponse> =
            ApiResult.Success(DownloadsListResponse())
    }

    private fun dummyEbookReaderRepository(dispatcher: CoroutineDispatcher): EbookReaderRepository {
        val v2 = org.siloserver.silo.network.apiv2.EbookReaderV2Api(dummyHttpClient(dispatcher), org.siloserver.silo.network.TokenManagerImpl(), org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted)
        return EbookReaderRepository(EbookReaderApi(v2), v2)
    }

    private inline fun <reified T : Any> unsafeInstance(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return (field.get(null) as Unsafe).allocateInstance(T::class.java) as T
    }

    companion object {
        private fun dummyHttpClient(
            dispatcher: CoroutineDispatcher,
            handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData = { respond("{}") },
        ): HttpClient = HttpClient(MockEngine(MockEngineConfig().apply { this.dispatcher = dispatcher; addHandler(handler) }))
    }
}
