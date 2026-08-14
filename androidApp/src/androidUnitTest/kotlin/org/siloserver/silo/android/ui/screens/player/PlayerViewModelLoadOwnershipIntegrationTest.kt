package org.siloserver.silo.android.ui.screens.player

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.downloads.DownloadMetadataStore
import org.siloserver.silo.common.downloads.DownloadStorage
import org.siloserver.silo.common.downloads.LegacyDownloadImporter
import org.siloserver.silo.common.downloads.OfflineMediaResolver
import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.player.AudioCapabilityManager
import org.siloserver.silo.common.player.FinalPlaybackPositionWriter
import org.siloserver.silo.common.player.PlaybackAnalyticsListener
import org.siloserver.silo.common.network.SiloClientBuildIdentity
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.SleepTimerController
import org.siloserver.silo.common.player.StartParams
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.common.player.video.VideoPlaybackSessionCoordinator
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.common.player.video.VideoPlaybackStartResult
import org.siloserver.silo.common.player.video.VideoPlaybackStarter
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.domain.player.IntroAutoSkipController
import org.siloserver.silo.libass.LibassBridge
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
import org.siloserver.silo.model.playback.PlaybackStreamV3
import org.siloserver.silo.model.playback.PlaybackTrackIdentityV3
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.DefaultSubtitlesApi
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.PlaybackApi
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.SubtitlesRepository
import org.siloserver.silo.repository.port.LocalTrackSelection
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.repository.port.UserItemStatePort
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.encodeCatalogSubtitlePreference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerViewModelLoadOwnershipIntegrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // StandardTestDispatcher, NOT Unconfined. Unconfined resumes continuations
    // inline on whichever thread completed the suspending call, and reentrant
    // resumptions land in that thread's internal unconfined event loop — a queue
    // the test scheduler cannot reach. Waiting for such a continuation from
    // another dispatcher was a genuine race: measured 2 failures in 6 idle runs.
    // A standard dispatcher gives every continuation an explicit scheduler queue
    // that `runTest` drains while the test body is suspended.
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: SiloDatabase

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SiloDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun differentContentLoadsCompletingOutOfOrderKeepNewestAndStopStaleReady() =
        runTest(dispatcher) {
            val starter = DeferredNonCooperativeStarter()
            val fixture = playerViewModel(starter, backgroundScope)
            val store = ViewModelStore().also { it.put("player", fixture.viewModel) }
            try {
                fixture.viewModel.loadContent(contentId = "old", preferredFileId = 1)
                starter.awaitRequestCount(1)
                fixture.viewModel.loadContent(contentId = "new", preferredFileId = 2)
                starter.awaitRequestCount(2)

                starter.complete(1, ready(starter.request(1), "new-session"))
                fixture.viewModel.awaitState {
                    it.contentId == "new" && it.sessionId == "new-session" && !it.isLoading
                }
                starter.complete(0, ready(starter.request(0), "old-session"))
                fixture.manager.awaitStopped("old-session")

                val state = fixture.viewModel.uiState.value
                assertEquals("new", state.contentId)
                assertEquals("new-session", state.sessionId)
                assertEquals(2, state.mediaFileId)
                assertEquals(listOf("old-session"), fixture.manager.stoppedSessions)
            } finally {
                store.clear()
            }
        }

    @Test
    fun sameContentFileAndQualityLoadsCompletingOutOfOrderKeepNewestRequest() =
        runTest(dispatcher) {
            val starter = DeferredNonCooperativeStarter()
            val fixture = playerViewModel(starter, backgroundScope)
            val store = ViewModelStore().also { it.put("player", fixture.viewModel) }
            try {
                fixture.viewModel.loadContent(
                    contentId = "movie",
                    preferredFileId = 10,
                    preferredQuality = "720p",
                )
                starter.awaitRequestCount(1)
                fixture.viewModel.loadContent(
                    contentId = "movie",
                    preferredFileId = 20,
                    preferredQuality = "4k",
                )
                starter.awaitRequestCount(2)

                starter.complete(1, ready(starter.request(1), "new-session"))
                fixture.viewModel.awaitState { it.sessionId == "new-session" && !it.isLoading }
                starter.complete(0, ready(starter.request(0), "old-session"))
                fixture.manager.awaitStopped("old-session")

                val state = fixture.viewModel.uiState.value
                assertEquals("movie", state.contentId)
                assertEquals(20, state.mediaFileId)
                assertEquals("stream-new-session", state.streamUrl)
                assertEquals("new-session", state.sessionId)
            } finally {
                store.clear()
            }
        }

    @Test
    fun staleErrorCannotOverwriteCurrentReady() = runTest(dispatcher) {
        val starter = DeferredNonCooperativeStarter()
        val fixture = playerViewModel(starter, backgroundScope)
        val store = ViewModelStore().also { it.put("player", fixture.viewModel) }
        try {
            fixture.viewModel.loadContent(contentId = "old", preferredFileId = 1)
            starter.awaitRequestCount(1)
            fixture.viewModel.loadContent(contentId = "new", preferredFileId = 2)
            starter.awaitRequestCount(2)

            starter.complete(1, ready(starter.request(1), "new-session"))
            fixture.viewModel.awaitState { it.sessionId == "new-session" && !it.isLoading }
            starter.complete(
                0,
                VideoPlaybackStartResult.Error(
                    contentId = "old",
                    message = "stale failure",
                ),
            )
            // Drain, do not wait on a predicate. `awaitState { sessionId ==
            // "new-session" }` was already true the moment it was called, so it
            // returned without the stale error having been handled at all — the
            // assertions below then proved nothing. Draining the scheduler makes
            // "the stale error was processed AND still did not overwrite" the
            // thing actually under test.
            advanceUntilIdle()

            val state = fixture.viewModel.uiState.value
            assertEquals("new", state.contentId)
            assertEquals("new-session", state.sessionId)
            assertNull(state.error)
            assertFalse(state.isLoading)
        } finally {
            store.clear()
        }
    }

    @Test
    fun exitDuringDeferredLoadRejectsAndStopsLateReady() = runTest(dispatcher) {
        val starter = DeferredNonCooperativeStarter()
        val fixture = playerViewModel(starter, backgroundScope)
        val store = ViewModelStore().also { it.put("player", fixture.viewModel) }
        try {
            fixture.viewModel.loadContent(contentId = "movie", preferredFileId = 8)
            starter.awaitRequestCount(1)

            fixture.viewModel.onExit()
            starter.complete(0, ready(starter.request(0), "late-session"))
            fixture.manager.awaitStopped("late-session")

            val state = fixture.viewModel.uiState.value
            assertNull(state.sessionId)
            assertNull(state.streamUrl)
            assertFalse(state.isPlaying)
        } finally {
            store.clear()
        }
    }

    @Test
    fun clearDuringDeferredLoadRejectsAndStopsLateReady() = runTest(dispatcher) {
        val starter = DeferredNonCooperativeStarter()
        val fixture = playerViewModel(starter, backgroundScope)
        val store = ViewModelStore().also { it.put("player", fixture.viewModel) }

        fixture.viewModel.loadContent(contentId = "movie", preferredFileId = 8)
        starter.awaitRequestCount(1)
        store.clear()

        starter.complete(0, ready(starter.request(0), "late-session"))
        fixture.manager.awaitStopped("late-session")

        val state = fixture.viewModel.uiState.value
        assertNull(state.sessionId)
        assertNull(state.streamUrl)
        assertFalse(state.isPlaying)
    }

    private fun playerViewModel(
        starter: DeferredNonCooperativeStarter,
        scope: CoroutineScope,
    ): PlayerFixture {
        val client = noOpClient()
        val tokenManager = FakeTokenManager()
        val profileRepository = FakeProfileRepository(client, tokenManager)
        val manager = RecordingPlaybackSessionManager(client, tokenManager)
        val healthApi = HealthApi(client)
        val personalDataRepository = PersonalDataRepository(PersonalDataApi(client))
        val context = ApplicationProvider.getApplicationContext<Application>()
        val capabilityDetector = PlaybackCapabilityDetector(
            context,
            AudioCapabilityManager(context),
            LibassBridge(false),
            SiloClientBuildIdentity(buildNumber = "5", channel = "release"),
        )
        return PlayerFixture(
            viewModel = PlayerViewModel(
                videoPlaybackCoordinator = VideoPlaybackSessionCoordinator(starter),
                catalogRepository = CatalogRepository(CatalogApi(client)),
                playbackSessionManager = manager,
                playbackAnalytics = PlaybackAnalyticsListener(),
                profileRepository = profileRepository,
                personalDataRepository = personalDataRepository,
                capabilityDetector = capabilityDetector,
                offlineMediaResolver = OfflineMediaResolver(
                    DownloadMetadataStore(db),
                    DownloadStorage(tmp.newFolder()),
                    LegacyDownloadImporter(tmp.newFolder(), db),
                ),
                serverRegistry = FakeServerRegistry(),
                serverReachabilityMonitor = ServerReachabilityMonitor(healthApi, scope),
                playerSettingsStore = FakePlayerSettingsStore(),
                introAutoSkipController = IntroAutoSkipController(scope),
                sessionLifecycle = PlaybackSessionLifecycle(
                    manager,
                    healthApi,
                    personalDataRepository,
                    scope,
                ),
                sleepTimer = SleepTimerController(scope),
                subtitlesRepository = SubtitlesRepository(DefaultSubtitlesApi(client)),
                userItemStatePort = NoOpUserItemStatePort,
                finalPlaybackPositionWriter = FinalPlaybackPositionWriter(
                    scope = scope,
                    scopeProvider = { null },
                    write = {},
                ),
            ),
            manager = manager,
        )
    }

    private fun ready(
        request: VideoPlaybackStartRequest,
        sessionId: String,
    ) = VideoPlaybackStartResult.Ready(
        contentId = request.contentId,
        fileId = request.preferredFileId,
        streamUrl = "stream-$sessionId",
        playMethod = PlayMethod.DIRECT,
        title = request.contentId,
        subtitle = null,
        artworkUrl = null,
        startPositionSeconds = 0.0,
        sessionId = sessionId,
        mediaFileId = request.preferredFileId,
    )

    private data class PlayerFixture(
        val viewModel: PlayerViewModel,
        val manager: RecordingPlaybackSessionManager,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MobileVideoPlaybackStarterCancellationTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cancellationAfterAllocationBeforeAdoptionStopsSessionAndPropagates() =
        runTest(dispatcher) {
            val client = starterCatalogClient()
            val tokenManager = FakeTokenManager()
            val profileRepository = FakeProfileRepository(client, tokenManager)
            val manager = RecordingPlaybackSessionManager(client, tokenManager)
            val context = ApplicationProvider.getApplicationContext<Application>()
            val adoptionEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            var allocated = false
            val starter = MobileVideoPlaybackStarter(
                catalogRepository = CatalogRepository(CatalogApi(client)),
                playbackSessionManager = manager,
                profileRepository = profileRepository,
                capabilityDetector = PlaybackCapabilityDetector(
                    context,
                    AudioCapabilityManager(context),
                    LibassBridge(false),
                    SiloClientBuildIdentity(buildNumber = "5", channel = "release"),
                ),
                playerSettingsStore = FakePlayerSettingsStore(),
                sessionLifecycle = PlaybackSessionLifecycle(
                    manager,
                    HealthApi(client),
                    PersonalDataRepository(PersonalDataApi(client)),
                    backgroundScope,
                ),
                reachabilityMonitor = ServerReachabilityMonitor(HealthApi(client), backgroundScope),
                sessionAllocator = MobileVideoSessionAllocator {
                    allocated = true
                    ApiResult.Success(allocatedReady("allocated-session"))
                },
                sessionAdopter = MobileVideoSessionAdopter { _, _ ->
                    adoptionEntered.complete(Unit)
                    awaitCancellation()
                },
            )

            var cancellationPropagatedFromStarter = false
            var starterReturnedNormally = false
            val result = async {
                try {
                    starter.start(
                        VideoPlaybackStartRequest(
                            contentId = "starter",
                            preferredFileId = 41,
                            roomId = null,
                            resumePositionOverride = null,
                        ),
                    ).also { starterReturnedNormally = true }
                } catch (error: CancellationException) {
                    cancellationPropagatedFromStarter = true
                    throw error
                }
            }
            adoptionEntered.await()
            assertTrue(allocated)

            result.cancel()
            assertFailsWith<CancellationException> { result.await() }

            assertTrue(cancellationPropagatedFromStarter)
            assertFalse(starterReturnedNormally)
            assertEquals(listOf("allocated-session"), manager.stoppedSessions)
            assertEquals(listOf(true), manager.stopContextsActive)
        }

    private fun starterCatalogClient(): HttpClient =
        HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/api/v1/watch/starter") {
                    respond(
                        content = """
                            {
                              "content_id": "starter",
                              "type": "movie",
                              "title": "Starter",
                              "versions": [
                                {
                                  "file_id": 41,
                                  "container": "mkv",
                                  "duration": 120.0
                                }
                              ]
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = JSON_HEADERS,
                    )
                } else {
                    respond(
                        content = """{"error":"not_found","message":"not found"}""",
                        status = HttpStatusCode.NotFound,
                        headers = JSON_HEADERS,
                    )
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
}

/**
 * The phone starter must take its subtitle preferences from the server's
 * resolved `effective_*` fields, the way TvVideoPlaybackStarter does.
 *
 * The settings screens write these preferences canonically now
 * (`PUT /settings/values/{key}?scope=profile`); nothing on the server mirrors
 * a canonical write back into `user_profiles`, so the profile columns
 * `GET /profiles` serves are stale from the first edit. Reading them here is
 * how the same profile ends up auto-selecting a different subtitle track on
 * the phone than on the TV.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MobileVideoPlaybackStarterSubtitlePreferenceTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun serverResolvedSubtitlePreferencesWinOverTheStaleProfileColumns() = runTest(dispatcher) {
        val ready = start(
            effective = """
                "effective_subtitle_language": "ja",
                "effective_subtitle_mode": "always",
                "effective_show_forced_subtitles": false,
            """.trimIndent(),
            // What GET /profiles still serves after a canonical-only write.
            profile = Profile(
                id = PROFILE_ID,
                name = "Profile",
                subtitleLanguage = "en",
                subtitleMode = "off",
                showForcedSubtitles = true,
            ),
        )

        assertEquals("ja", ready.preferredTextLanguage)
        assertEquals("always", ready.preferredSubtitleMode)
        assertFalse(ready.showForcedSubtitles)
    }

    @Test
    fun profileColumnsRemainTheFallbackWhenTheServerSendsNoResolvedValues() =
        runTest(dispatcher) {
            val ready = start(
                effective = "",
                profile = Profile(
                    id = PROFILE_ID,
                    name = "Profile",
                    subtitleLanguage = "de",
                    subtitleMode = "always",
                    showForcedSubtitles = false,
                ),
            )

            assertEquals("de", ready.preferredTextLanguage)
            assertEquals("always", ready.preferredSubtitleMode)
            assertFalse(ready.showForcedSubtitles)
        }

    @Test
    fun persistedPerFileTracksAreIncludedInTheInitialV3Allocation() = runTest(dispatcher) {
        val audioTracks = listOf(
            AudioTrack(codec = "aac", language = "en", title = "Stereo"),
            AudioTrack(codec = "truehd", language = "en", title = "Atmos"),
        )
        // Catalog order intentionally differs from the server's combined
        // external-then-embedded subtitle index space. English is catalog
        // ordinal 0 but combined index 1.
        val subtitleTracks = listOf(
            SubtitleTrack(index = 12, codec = "subrip", language = "en", title = "English"),
            SubtitleTrack(index = 0, codec = "srt", language = "fr", title = "French", external = true),
        )
        val persisted = LocalTrackSelection(
            audioFingerprint = audioTrackFingerprint(audioTracks[1]),
            subtitleFingerprint = encodeCatalogSubtitlePreference(subtitleTracks, 0),
        )
        val localState = object : UserItemStatePort by NoOpUserItemStatePort {
            override suspend fun localTrackSelection(contentId: String, fileId: Int) = persisted
        }
        var allocation: MobileVideoSessionAllocation? = null

        start(
            effective = "",
            profile = Profile(id = PROFILE_ID, name = "Profile"),
            versionFields = """
                "audio_tracks": [
                  {"codec":"aac","language":"en","title":"Stereo"},
                  {"codec":"truehd","language":"en","title":"Atmos"}
                ],
                "subtitle_tracks": [
                  {"index":12,"codec":"subrip","language":"en","title":"English","external":false},
                  {"index":0,"codec":"srt","language":"fr","title":"French","external":true}
                ]
            """.trimIndent(),
            userItemStatePort = localState,
            onAllocation = { allocation = it },
        )

        assertEquals(1, allocation?.audioTrackIndex)
        assertEquals(1, allocation?.subtitleTrackIndex)
    }

    @Test
    fun explicitPlaybackSubtitleIndexWinsAndPassesThroughUnchanged() = runTest(dispatcher) {
        var allocation: MobileVideoSessionAllocation? = null

        start(
            effective = "",
            profile = Profile(id = PROFILE_ID, name = "Profile"),
            versionFields = """
                "subtitle_tracks": [
                  {"index":12,"codec":"subrip","language":"en","title":"English","external":false},
                  {"index":0,"codec":"srt","language":"fr","title":"French","external":true}
                ]
            """.trimIndent(),
            explicitSubtitleTrackIndex = 1,
            onAllocation = { allocation = it },
        )

        assertEquals(1, allocation?.subtitleTrackIndex)
    }

    @Test
    fun serverSelectedSubtitleIndexIsRetainedForSessionRenewal() = runTest(dispatcher) {
        var adoptedParams: StartParams? = null

        start(
            effective = "",
            profile = Profile(id = PROFILE_ID, name = "Profile"),
            readyStart = allocatedReady("subtitle-session", selectedSubtitleIndex = 4),
            onAdoption = { adoptedParams = it },
        )

        assertEquals(4, adoptedParams?.subtitleTrackIndex)
    }

    @Test
    fun unknownSourceDurationStaysUnknownAtTheStarterBoundary() = runTest(dispatcher) {
        val ready = start(
            effective = "",
            profile = Profile(id = PROFILE_ID, name = "Profile"),
        )

        assertNull(ready.durationSeconds)
    }

    private suspend fun TestScope.start(
        effective: String,
        profile: Profile,
        versionFields: String = "",
        explicitSubtitleTrackIndex: Int? = null,
        userItemStatePort: UserItemStatePort = NoOpUserItemStatePort,
        onAllocation: (MobileVideoSessionAllocation) -> Unit = {},
        readyStart: VideoSessionStartV3.Ready = allocatedReady("subtitle-session"),
        onAdoption: (StartParams) -> Unit = {},
    ): VideoPlaybackStartResult.Ready {
        val client = catalogClient(effective, versionFields)
        val tokenManager = FakeTokenManager()
        val profileRepository = FakeProfileRepository(client, tokenManager, profile)
        val manager = RecordingPlaybackSessionManager(client, tokenManager)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val starter = MobileVideoPlaybackStarter(
            catalogRepository = CatalogRepository(CatalogApi(client)),
            playbackSessionManager = manager,
            profileRepository = profileRepository,
            capabilityDetector = PlaybackCapabilityDetector(
                context,
                AudioCapabilityManager(context),
                LibassBridge(false),
                SiloClientBuildIdentity(buildNumber = "5", channel = "release"),
            ),
            playerSettingsStore = FakePlayerSettingsStore(),
            sessionLifecycle = PlaybackSessionLifecycle(
                manager,
                HealthApi(client),
                PersonalDataRepository(PersonalDataApi(client)),
                backgroundScope,
            ),
            reachabilityMonitor = ServerReachabilityMonitor(HealthApi(client), backgroundScope),
            userItemStatePort = userItemStatePort,
            sessionAllocator = {
                onAllocation(it)
                ApiResult.Success(readyStart)
            },
            sessionAdopter = { params, _ -> onAdoption(params) },
        )

        val result = starter.start(
            VideoPlaybackStartRequest(
                contentId = "starter",
                preferredFileId = 41,
                roomId = null,
                resumePositionOverride = null,
                subtitleTrackIndex = explicitSubtitleTrackIndex,
            ),
        )
        assertTrue(result is VideoPlaybackStartResult.Ready, "expected a ready start, got $result")
        return result
    }

    private fun catalogClient(effective: String, versionFields: String): HttpClient {
        val extraVersionFields = versionFields
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let { ",\n$it" }
            .orEmpty()
        return HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/api/v1/watch/starter") {
                    respond(
                        content = """
                            {
                              "content_id": "starter",
                              "type": "movie",
                              "title": "Starter",
                              $effective
                              "versions": [
                                {
                                  "file_id": 41,
                                  "container": "mkv",
                                  "duration": 120.0
                                  $extraVersionFields
                                }
                              ]
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = JSON_HEADERS,
                    )
                } else {
                    respond(
                        content = """{"error":"not_found","message":"not found"}""",
                        status = HttpStatusCode.NotFound,
                        headers = JSON_HEADERS,
                    )
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
    }
}

private class DeferredNonCooperativeStarter : VideoPlaybackStarter {
    private data class Pending(
        val request: VideoPlaybackStartRequest,
        val continuation: Continuation<VideoPlaybackStartResult>,
        var completed: Boolean = false,
    )

    private val pending = mutableListOf<Pending>()

    /** Replayable so a request that lands before the wait begins is still seen. */
    private val requestCount = MutableStateFlow(0)

    override suspend fun start(request: VideoPlaybackStartRequest): VideoPlaybackStartResult =
        suspendCoroutine { continuation ->
            requestCount.value = synchronized(pending) {
                pending += Pending(request, continuation)
                pending.size
            }
        }

    fun request(index: Int): VideoPlaybackStartRequest =
        synchronized(pending) { pending[index].request }

    fun complete(index: Int, result: VideoPlaybackStartResult) {
        val continuation = synchronized(pending) {
            pending[index].also {
                check(!it.completed) { "Request $index already completed" }
                it.completed = true
            }.continuation
        }
        continuation.resume(result)
    }

    suspend fun awaitRequestCount(count: Int) {
        awaitRealTime { requestCount.first { it >= count } }
    }
}

private class RecordingPlaybackSessionManager(
    client: HttpClient,
    tokenManager: TokenManager,
) : PlaybackSessionManager(
    PlaybackRepository(PlaybackApi(client)),
    tokenManager,
) {
    private val stopped = mutableListOf<String>()
    private val stopActiveContexts = mutableListOf<Boolean>()
    private val stoppedSignal = MutableStateFlow<Set<String>>(emptySet())

    val stoppedSessions: List<String>
        get() = synchronized(stopped) { stopped.toList() }

    val stopContextsActive: List<Boolean>
        get() = synchronized(stopActiveContexts) { stopActiveContexts.toList() }

    override suspend fun stopSession(sessionId: String): ApiResult<Unit> {
        val contextActive = currentCoroutineContext().isActive
        synchronized(stopped) {
            stopped += sessionId
            stopActiveContexts += contextActive
        }
        stoppedSignal.update { it + sessionId }
        return ApiResult.Success(Unit)
    }

    suspend fun awaitStopped(sessionId: String) {
        awaitRealTime { stoppedSignal.first { sessionId in it } }
    }
}

private class FakeProfileRepository(
    client: HttpClient,
    tokenManager: TokenManager,
    private val profile: Profile = Profile(id = PROFILE_ID, name = "Profile"),
) : ProfileRepository(ProfileApi(client), tokenManager) {
    override suspend fun getActiveProfileId(): String = PROFILE_ID

    override suspend fun listProfiles(): ApiResult<List<Profile>> = ApiResult.Success(listOf(profile))
}

private class FakeTokenManager : TokenManager {
    override val sessionExpired = MutableSharedFlow<Unit>()
    override suspend fun getAccessToken(): String = "access-token"
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) = Unit
    override suspend fun clearTokens() = Unit
    override suspend fun invalidateSession() = Unit
    override suspend fun getProfileId(): String = PROFILE_ID
    override suspend fun setProfileId(profileId: String?) = Unit
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) = Unit
    override suspend fun getServerUrl(): String = "https://silo.test"
    override suspend fun setServerUrl(url: String) = Unit
    override suspend fun getCurrentServerId(): String = SERVER_ID
    override suspend fun switchActiveServer(serverId: String?) = Unit
    override suspend fun signOutCurrentServer() = Unit
}

private class FakeServerRegistry : ServerRegistry {
    override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(emptyList())
    override val activeServerId: StateFlow<String?> = MutableStateFlow(SERVER_ID)
    override val activeEntry: StateFlow<ServerEntry?> = MutableStateFlow(null)
    override suspend fun addOrUpdate(url: String, fetchedName: String?): String = SERVER_ID
    override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
    override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
    override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
    override suspend fun remove(serverId: String) = Unit
    override suspend fun signOut(serverId: String) = Unit
    override suspend fun switchTo(serverId: String) = Unit
    override suspend fun touchActive() = Unit
}

private class FakePlayerSettingsStore : PlayerSettingsStore {
    override val autoSkipIntroFlow: Flow<Boolean> = flowOf(false)
    override val autoSkipCreditsFlow: Flow<Boolean> = flowOf(false)
    override val autoPlayNextFlow: Flow<Boolean> = flowOf(true)
    override val hdrEnabledFlow: Flow<Boolean> = flowOf(true)
    override val dvProfile7HDR10FallbackFlow: Flow<Boolean> = flowOf(true)
    override val dolbyVisionEnabledFlow: Flow<Boolean> = flowOf(true)
    override val matchContentFrameRateFlow: Flow<Boolean> = flowOf(false)
    override val pictureInPictureEnabledFlow: Flow<Boolean> = flowOf(true)
    override val downloadsWifiOnlyFlow: Flow<Boolean> = flowOf(true)
    override val keepWatchedDownloadsFlow: Flow<Boolean> = flowOf(false)
    override val defaultDownloadQualityFlow: Flow<String> = flowOf("original")
    override val playbackSpeedFlow: Flow<Double> = flowOf(1.0)
    override val audioSyncMsFlow: Flow<Int> = flowOf(0)
    override val subtitleSyncMsFlow: Flow<Int> = flowOf(0)
    override val nextUpPromptSecondsFlow: Flow<Int> = flowOf(30)
    override val sleepTimerDefaultMinutesFlow: Flow<Int> = flowOf(30)
    override val resumeRewindSecondsFlow: Flow<Int> = flowOf(7)
    override val passOutThresholdFlow: Flow<Int> = flowOf(3)
    override val preferredQualityFlow: Flow<String> = flowOf("auto")
    override val maxBitrateKbpsFlow: Flow<Int?> = flowOf(null)
    override val audioLanguageFlow: Flow<String> = flowOf("")
    override val videoGravityFlow: Flow<String> = flowOf("fit")
    override val orientationModeFlow: Flow<String> = flowOf("auto")
    override val subtitleAppearanceFlow: Flow<SubtitleAppearance> = flowOf(SubtitleAppearance.DEFAULT)
    override val savedCustomSubtitleAppearanceFlow: Flow<SubtitleAppearance> =
        flowOf(SubtitleAppearance.DEFAULT)
    override val subtitleUsesDeviceOverrideFlow: Flow<Boolean> = flowOf(false)
    override val subtitleMatchesDeviceFlow: Flow<Boolean> = flowOf(false)
    override val showAudiobooksFlow: Flow<Boolean> = flowOf(false)
    override val effectiveSubtitleAppearanceFlow: Flow<SubtitleAppearance> =
        flowOf(SubtitleAppearance.DEFAULT)

    override suspend fun setAutoSkipIntro(value: Boolean) = Unit
    override suspend fun setAutoSkipCredits(value: Boolean) = Unit
    override suspend fun setAutoPlayNext(value: Boolean) = Unit
    override suspend fun setHdrEnabled(value: Boolean) = Unit
    override suspend fun setDvProfile7HDR10Fallback(value: Boolean) = Unit
    override suspend fun setDolbyVisionEnabled(value: Boolean) = Unit
    override suspend fun setMatchContentFrameRate(value: Boolean) = Unit
    override suspend fun setPictureInPictureEnabled(value: Boolean) = Unit
    override suspend fun setDownloadsWifiOnly(value: Boolean) = Unit
    override suspend fun setKeepWatchedDownloads(value: Boolean) = Unit
    override suspend fun setDefaultDownloadQuality(value: String) = Unit
    override suspend fun setPlaybackSpeed(value: Double) = Unit
    override suspend fun setAudioSyncMs(value: Int) = Unit
    override suspend fun setSubtitleSyncMs(value: Int) = Unit
    override suspend fun setNextUpPromptSeconds(value: Int) = Unit
    override suspend fun setSleepTimerDefaultMinutes(value: Int) = Unit
    override suspend fun setResumeRewindSeconds(value: Int) = Unit
    override suspend fun setPassOutThreshold(value: Int) = Unit
    override suspend fun setPreferredQuality(value: String) = Unit
    override suspend fun setQuality(resolution: String, bitrateKbps: Int?) = Unit
    override suspend fun setAudioLanguage(value: String) = Unit
    override suspend fun setVideoGravity(value: String) = Unit
    override suspend fun setOrientationMode(value: String) = Unit
    override suspend fun setSubtitleAppearance(value: SubtitleAppearance) = Unit
    override suspend fun flushProjectedSubtitleAppearance() = Unit
    override suspend fun refreshFromServer() = Unit
    override suspend fun setSubtitleDeviceOverrideEnabled(enabled: Boolean) = Unit
    override suspend fun setSubtitleMatchesDevice(enabled: Boolean) = Unit
    override suspend fun setShowAudiobooks(enabled: Boolean) = Unit
    override suspend fun resetDeviceSetting(key: String) = Unit
    override suspend fun resetAllDeviceSettings() = Unit
    override suspend fun flushPendingDeviceSettings() = Unit
}

private fun allocatedReady(
    sessionId: String,
    selectedSubtitleIndex: Int? = null,
): VideoSessionStartV3.Ready {
    val selectedSubtitle = selectedSubtitleIndex?.let { index ->
        PlaybackTrackIdentityV3("file:41:subtitle:$index", index)
    }
    val plan = PlaybackPlanV3(
        planId = "plan-$sessionId",
        planAttemptKey = "v3:test:$sessionId",
        sessionId = sessionId,
        delivery = PlaybackDelivery.ORIGINAL_HTTP,
        stream = PlaybackStreamV3(
            url = "https://silo.test/stream/$sessionId",
            protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE,
            container = "mkv",
        ),
        decisionReason = "test",
        effectiveMediaFileId = 41,
        selectedTracks = SelectedPlaybackTracksV3(subtitle = selectedSubtitle),
    )
    return VideoSessionStartV3.Ready(
        session = PlaybackSessionResponse(
            sessionId = sessionId,
            userId = 1,
            profileId = PROFILE_ID,
            mediaFileId = 41,
            playMethod = PlayMethod.DIRECT,
            streamUrl = plan.stream.url,
        ),
        plan = plan,
        playbackAttemptId = "playback-attempt",
        planAttemptId = "plan-attempt",
        planAttemptKey = "plan-key",
        capabilities = ClientCodecCapabilities(),
        clientPlaybackContext = ClientPlaybackContext(formFactor = "mobile", appVersion = "test"),
    )
}

private fun noOpClient(): HttpClient =
    HttpClient(
        MockEngine {
            respond(
                content = """{"error":"not_found","message":"not found"}""",
                status = HttpStatusCode.NotFound,
                headers = JSON_HEADERS,
            )
        },
    ) {
        install(ContentNegotiation) { json(SiloJson) }
    }

private suspend fun PlayerViewModel.awaitState(
    predicate: (PlayerViewModel.PlayerUiState) -> Boolean,
) {
    awaitRealTime { uiState.first(predicate) }
}

/**
 * Runs [block] under a REAL, generous deadline.
 *
 * The deadline has to be real: this load path does unavoidable work on
 * `Dispatchers.IO` before it ever reaches the fake starter — the offline
 * preflight in `PlayerViewModel.tryLocalPlayback` and, beneath it,
 * `LegacyDownloadImporter` both hard-code that dispatcher — and virtual time
 * cannot advance a real thread. A purely virtual timeout raced straight past
 * that work and failed every test in this class.
 *
 * What must NOT come back is polling. Waiters here suspend on a signal, so a
 * result that arrives before the wait begins is still seen, and a waiter can no
 * longer give up on work that simply had not been dispatched yet.
 */
private suspend fun <T> awaitRealTime(block: suspend () -> T): T =
    withContext(Dispatchers.Default) {
        // The deadline exists to turn a hang into a failure, not to police
        // latency — a passing test signals in milliseconds and never waits.
        // Five seconds was tight enough that a full-suite run, with dozens of
        // Robolectric classes competing for the same JVM, could blow it while
        // the work was merely slow. That looked exactly like the race this
        // helper was written to remove, which is worse than useless.
        withTimeout(30_000) { block() }
    }


private const val SERVER_ID = "server"
private const val PROFILE_ID = "profile"
private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
