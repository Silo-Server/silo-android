package org.siloserver.silo.android.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
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
class MobileDetailActionsTest {
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
    fun markingEpisodeWatchedUpdatesTheEpisodeAndSeasonCache() = runItemDetailTest {

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
