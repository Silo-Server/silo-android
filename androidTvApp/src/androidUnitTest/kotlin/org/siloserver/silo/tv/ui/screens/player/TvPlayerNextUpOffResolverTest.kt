package org.siloserver.silo.tv.ui.screens.player

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.network.SiloClientBuildIdentity
import org.siloserver.silo.common.player.AudioCapabilityManager
import org.siloserver.silo.common.player.FinalPlaybackPositionWriter
import org.siloserver.silo.common.player.PlaybackAnalyticsListener
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.SleepTimerController
import org.siloserver.silo.common.player.video.VideoPlaybackSessionCoordinator
import org.siloserver.silo.common.player.video.VideoPlaybackStartResult
import org.siloserver.silo.domain.player.IntroAutoSkipController
import org.siloserver.silo.libass.LibassBridge
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.DefaultSubtitlesApi
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.SubtitlesRepository
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.tv.testing.FakePlayerSettingsStore

/**
 * Show Next Up Off on TV while the next-episode lookup is still running, on a
 * plan with no source duration. Off can reach `nextUpEnabled.value` before
 * its collector runs, so the lookup's own close is the one that decides.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TvPlayerNextUpOffResolverTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun lookupFinishingAfterOffKeepsPlaybackTheSystemControlsMovedOffTheEnd() = runTest(dispatcher) {
        offWhileTheLookupWaits(leaveTheEnd = { onPlaybackLeftEnd() }, offCollectorFirst = false) { closes ->
            assertEquals(0, closes)
        }
    }

    @Test
    fun lookupFinishingAfterOffKeepsPlaybackAViewModelSeekMovedOffTheEnd() = runTest(dispatcher) {
        offWhileTheLookupWaits(leaveTheEnd = { remoteSeek(30.0) }, offCollectorFirst = false) { closes ->
            assertEquals(0, closes)
        }
    }

    @Test
    fun offCollectorRunningBeforeTheLookupKeepsPlaybackMovedOffTheEnd() = runTest(dispatcher) {
        offWhileTheLookupWaits(leaveTheEnd = { onPlaybackLeftEnd() }, offCollectorFirst = true) { closes ->
            assertEquals(0, closes)
        }
    }

    @Test
    fun lookupFinishingAfterOffClosesAnEndNothingMovedFrom() = runTest(dispatcher) {
        offWhileTheLookupWaits(leaveTheEnd = null, offCollectorFirst = false) { closes ->
            assertEquals(1, closes)
        }
    }

    private suspend fun TestScope.offWhileTheLookupWaits(
        leaveTheEnd: (TvPlayerViewModel.() -> Unit)?,
        offCollectorFirst: Boolean,
        assertCloses: (Int) -> Unit,
    ) {
        val lookupGate = CompletableDeferred<Unit>()
        val settings = FakePlayerSettingsStore()
        val viewModel = tvPlayerViewModel(backgroundScope, settings, catalogClient(lookupGate))
        val store = ViewModelStore().also { it.put("player", viewModel) }
        try {
            var closes = 0
            backgroundScope.launch { viewModel.closeAtEndRequests.collect { closes++ } }
            viewModel.mutableUiState().update {
                it.copy(
                    isLoading = false, contentId = "s1e1", seriesId = "show",
                    seasonNumber = 1, episodeNumber = 1, duration = 0.0, position = 1200.0,
                )
            }
            viewModel.invokePrivate("resolveNextEpisode")
            runCurrent()

            // The end arrives while the lookup waits: the finished overlay shows on its own.
            viewModel.onApproachingEnd(videoEnded = true)
            runCurrent()
            assertTrue(viewModel.uiState.value.showNextUp)
            assertEquals(0.0, viewModel.uiState.value.duration)

            leaveTheEnd?.invoke(viewModel)
            runCurrent()

            if (offCollectorFirst) {
                settings.nextUpEnabledFlow.value = false
                runCurrent()
            } else {
                // Off is in nextUpEnabled.value, but its collector (subscribed to the
                // original flow) has not run yet.
                viewModel.setPrivate("nextUpEnabled", MutableStateFlow(false))
            }

            lookupGate.complete(Unit)
            awaitOnMain { viewModel.uiState.value.nextEpisode != null }
            if (!offCollectorFirst) {
                settings.nextUpEnabledFlow.value = false
                runCurrent()
            }

            assertCloses(closes)
            assertFalse(viewModel.uiState.value.showNextUp)
            assertTrue(viewModel.uiState.value.nextUpCountdownSeconds == null)
        } finally {
            store.clear()
        }
    }

    /** The lookup's replies come back on the mock engine's thread; drain Main until [done]. */
    private fun TestScope.awaitOnMain(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out" }
            runCurrent()
            Thread.yield()
        }
        runCurrent()
    }

    private fun catalogClient(lookupGate: CompletableDeferred<Unit>): HttpClient = HttpClient(
        MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/v2/catalog/series/show/seasons/1/episodes" -> {
                    lookupGate.await()
                    """{"items":[{"content_id":"s1e1","season_number":1,"episode_number":1},""" +
                        """{"content_id":"s1e2","season_number":1,"episode_number":2,"title":"Next"}]}"""
                }
                "/api/v2/catalog/series/show/seasons" -> """{"items":[{"content_id":"s1","season_number":1}]}"""
                else -> return@MockEngine respond("""{"status":404}""", HttpStatusCode.NotFound, JSON_HEADERS)
            }
            respond(body, headers = JSON_HEADERS)
        },
    ) { install(ContentNegotiation) { json(SiloJson) } }

    private fun tvPlayerViewModel(
        scope: CoroutineScope,
        settings: FakePlayerSettingsStore,
        catalog: HttpClient,
    ): TvPlayerViewModel {
        val client = catalog
        val tokens = FakeTokenManager()
        val manager = PlaybackSessionManager(PlaybackRepository(sequencedPlayback(client, tokens)), tokens)
        val personalData = PersonalDataRepository(PersonalDataApi(client))
        val context = ApplicationProvider.getApplicationContext<Application>()
        return TvPlayerViewModel(
            videoPlaybackCoordinator = VideoPlaybackSessionCoordinator { error("no playback start in this test") as VideoPlaybackStartResult },
            playbackSessionManager = manager,
            playbackAnalytics = PlaybackAnalyticsListener(),
            capabilityDetector = PlaybackCapabilityDetector(
                context,
                AudioCapabilityManager(context),
                LibassBridge(false),
                SiloClientBuildIdentity(buildNumber = "5", channel = "release"),
            ),
            playerSettingsStore = settings,
            introAutoSkipController = IntroAutoSkipController(scope),
            sessionLifecycle = PlaybackSessionLifecycle(manager, HealthApi(client), personalData, scope),
            sleepTimer = SleepTimerController(scope),
            subtitlesRepository = SubtitlesRepository(
                DefaultSubtitlesApi(
                    org.siloserver.silo.network.apiv2.SubtitleReadsV2Api(client, tokens, ApiV2Gate.Unrestricted),
                    org.siloserver.silo.network.apiv2.SubtitleDownloadV2Api(client, tokens, ApiV2Gate.Unrestricted),
                    org.siloserver.silo.network.apiv2.SubtitleAiReadsV2Api(client, tokens, ApiV2Gate.Unrestricted),
                    org.siloserver.silo.network.apiv2.SubtitleAiCreateV2Api(client, tokens, ApiV2Gate.Unrestricted),
                ),
            ),
            userItemStatePort = NoOpUserItemStatePort,
            finalPlaybackPositionWriter = FinalPlaybackPositionWriter(scope = scope, scopeProvider = { null }, write = {}),
            catalogRepository = CatalogRepository(CatalogApi(client)),
            serverReachabilityMonitor = ServerReachabilityMonitor(
                org.siloserver.silo.network.apiv2.ApiV2Probe(client)::probeFresh, scope, { null },
            ),
            // A library ID sends the lookup straight to the API, on this test's dispatchers.
            launchArgs = TvPlayerLaunchArgs(contentId = "s1e1", libraryId = 1),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun TvPlayerViewModel.mutableUiState(): MutableStateFlow<TvPlayerViewModel.UiState> =
        TvPlayerViewModel::class.java.getDeclaredField("_uiState").let {
            it.isAccessible = true
            it.get(this) as MutableStateFlow<TvPlayerViewModel.UiState>
        }

    private fun TvPlayerViewModel.setPrivate(name: String, value: StateFlow<Boolean>) {
        TvPlayerViewModel::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.set(this, value)
        }
    }

    private fun TvPlayerViewModel.invokePrivate(name: String) {
        TvPlayerViewModel::class.java.getDeclaredMethod(name).let {
            it.isAccessible = true
            it.invoke(this)
        }
    }

    private fun sequencedPlayback(client: HttpClient, tokens: TokenManager) =
        org.siloserver.silo.repository.SequencedPlayback(
            org.siloserver.silo.network.apiv2.PlaybackV2Api(client, ApiV2Gate.Unrestricted),
            tokens,
            object : org.siloserver.silo.network.DurableLoginAuthorityProvider {
                override suspend fun snapshotDurableLoginAuthority(): org.siloserver.silo.network.DurableLoginAuthority? = null
            },
            object : org.siloserver.silo.repository.PlaybackJournalStore {
                var entries = emptyList<org.siloserver.silo.repository.PlaybackJournalEntry>()
                override suspend fun read() = entries
                override suspend fun write(entries: List<org.siloserver.silo.repository.PlaybackJournalEntry>) {
                    this.entries = entries
                }
            },
        ) { "stop" }

    private class FakeTokenManager : TokenManager {
        override suspend fun snapshotCurrentScope(): org.siloserver.silo.network.AuthScopeSnapshot? = null
        override val sessionExpired = MutableSharedFlow<Unit>()
        override suspend fun getAccessToken(): String = "access-token"
        override suspend fun getRefreshToken(): String? = null
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) = Unit
        override suspend fun clearTokens() = Unit
        override suspend fun invalidateSession() = Unit
        override suspend fun getProfileId(): String = "profile"
        override suspend fun setProfileId(profileId: String?) = Unit
        override suspend fun getProfileToken(): String? = null
        override suspend fun setProfileToken(token: String?) = Unit
        override suspend fun getServerUrl(): String = "https://silo.test"
        override suspend fun setServerUrl(url: String) = Unit
        override suspend fun getCurrentServerId(): String = "server"
        override suspend fun switchActiveServer(serverId: String?) = Unit
        override suspend fun signOutCurrentServer() = Unit
    }

    private companion object {
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
