package org.siloserver.silo.tv.ui.screens.player

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.SessionState
import org.siloserver.silo.common.player.StartParams
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.common.player.downloadedSubtitleArtifactTrackId
import org.siloserver.silo.common.player.subtitleArtifactTrackId
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.personal.SyncProgressItem
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.PLAYBACK_PLAN_V3_FEATURE
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDecisionOutcome
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEffectiveRecipeV3
import org.siloserver.silo.model.playback.PlaybackEngineKind
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
import org.siloserver.silo.model.playback.PlaybackStreamV3
import org.siloserver.silo.model.playback.PlaybackSubtitleArtifactV3
import org.siloserver.silo.model.playback.PlaybackSubtitleDecisionV3
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PlaybackTrackIdentityV3
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.network.api.HealthStatus
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.PlaybackApi
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.ProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private suspend fun awaitHarnessCondition(
    transactionScheduler: TestCoroutineScheduler,
    cleanupScheduler: TestCoroutineScheduler,
    timeoutMillis: Long,
    condition: suspend () -> Boolean,
) {
    val started = TimeSource.Monotonic.markNow()
    while (!condition()) {
        // runTest already owns and drives its transaction scheduler. Driving it
        // again from another thread can execute nominally single-threaded test
        // tasks concurrently. Only a genuinely separate manager-cleanup
        // scheduler needs manual progress here.
        if (cleanupScheduler !== transactionScheduler) {
            cleanupScheduler.runCurrent()
        }
        if (started.elapsedNow() >= timeoutMillis.milliseconds) {
            throw AssertionError("Timed out waiting for subtitle transaction cleanup")
        }
        yield()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
class SubtitleTransactionIntegrationTest {
    @Test
    fun `sidecar replacement remains unpublished until exact typed Media3 mount`() = runTest {
        val harness = harness(
            replanResponse = { _, _ -> response(sidecarPlan("s2", FILE_ID, B_INDEX)) },
        )
        harness.start(sidecarA)

        harness.adapter.select(sidecarB)
        runCurrent()
        harness.awaitReplans(1)
        harness.awaitAdopted("s2")

        assertEquals(listOf("s1"), harness.replanBaseSessions)
        assertReplan(harness.replanBodies.single(), audioIndex = 0, subtitleIndex = B_INDEX)
        harness.assertActiveSession("s2")
        assertTrue(harness.stoppedSessions.isEmpty())
        assertTrue(harness.persistence.isEmpty())

        harness.mountPending(
            expectedSessionId = "s2",
            tracks =
            listOf(
                media3Track(
                    index = 6,
                    trackId = "label-decoy",
                    label = "English",
                ),
                harness.sidecarMountedTrack(
                    expectedSessionId = "s2",
                    serverIndex = B_INDEX,
                    playerIndex = 9,
                ),
            ),
        )
        runCurrent()
        harness.awaitStopped("s1")
        harness.awaitPersistence(1)
        runCurrent()

        assertEquals(listOf(Harness.MountedSelection("s2", 9)), harness.media3Selections)
        harness.assertActiveSession("s2")
        assertEquals(mapOf("s1" to 1), harness.stopCounts())
        assertEquals(listOf(sidecarB), harness.persistence.map { it.first.identity })
        assertEquals("s2", harness.persistence.single().second.sessionId)
        harness.assertNoOrphans()
    }

    @Test
    fun `cleanup wait advances the manager-owned test scheduler`() = runTest {
        val cleanupDispatcher = StandardTestDispatcher()
        val cleanupJob = SupervisorJob()
        val cleanupScope = CoroutineScope(cleanupJob + cleanupDispatcher)
        try {
            val harness = harness(
                replanResponse = { _, _ -> response(sidecarPlan("s2", FILE_ID, B_INDEX)) },
                committedSessionCleanupScope = cleanupScope,
                committedSessionCleanupScheduler = cleanupDispatcher.scheduler,
            )
            harness.start(sidecarA)

            harness.adapter.select(sidecarB)
            runCurrent()
            harness.awaitReplans(1)
            harness.awaitAdopted("s2")
            harness.mountPending(
                expectedSessionId = "s2",
                tracks = listOf(
                    harness.sidecarMountedTrack(
                        expectedSessionId = "s2",
                        serverIndex = B_INDEX,
                        playerIndex = 9,
                    ),
                ),
            )
            runCurrent()

            harness.awaitStopped("s1")

            assertEquals(mapOf("s1" to 1), harness.stopCounts())
            harness.assertNoOrphans()
        } finally {
            cleanupJob.cancelAndJoin()
        }
    }

    @Test
    fun `cleanup wait never drives the shared transaction scheduler concurrently`() = runTest {
        val firstTaskRunning = AtomicBoolean(false)
        val overlapObserved = AtomicBoolean(false)
        val completed = AtomicBoolean(false)

        backgroundScope.launch {
            firstTaskRunning.set(true)
            Thread.sleep(100)
            firstTaskRunning.set(false)
        }
        backgroundScope.launch {
            overlapObserved.set(firstTaskRunning.get())
            completed.set(true)
        }

        awaitHarnessCondition(
            transactionScheduler = testScheduler,
            cleanupScheduler = testScheduler,
            timeoutMillis = EVENT_TIMEOUT_MS,
            condition = completed::get,
        )

        assertFalse(overlapObserved.get())
    }

    @Test
    fun `off supersedes an in-flight sidecar and replans from the committed session`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val harness = harness(
            replanResponse = { index, _ ->
                if (index == 0) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    response(sidecarPlan("s2", FILE_ID, B_INDEX))
                } else {
                    response(basePlan("s3", FILE_ID, audioIndex = 0))
                }
            },
        )
        harness.start(sidecarA)

        harness.adapter.select(sidecarB)
        firstEntered.await()
        harness.adapter.select(SubtitleIdentity.Off)
        releaseFirst.complete(Unit)
        runCurrent()
        harness.awaitReplans(2)
        harness.awaitStopped("s2")
        harness.awaitAdopted("s3")
        assertTrue(
            testScheduler.currentTime < EVENT_TIMEOUT_MS,
            "Adoption reached the pending Media3 mount deadline before the test could mount it.",
        )
        runCurrent()

        assertEquals(listOf("s1", "s1"), harness.replanBaseSessions)
        assertReplan(harness.replanBodies[0], audioIndex = 0, subtitleIndex = B_INDEX)
        assertReplan(harness.replanBodies[1], audioIndex = 0, subtitleIndex = -1)
        harness.assertActiveSession("s3")
        assertEquals(mapOf("s2" to 1), harness.stopCounts())
        assertTrue(harness.persistence.isEmpty())

        harness.mountPending(expectedSessionId = "s3", tracks = emptyList())
        runCurrent()
        harness.awaitStopped("s1")
        harness.awaitPersistence(1)
        runCurrent()

        assertEquals(listOf(Harness.MountedSelection("s3", -1)), harness.media3Selections)
        harness.assertActiveSession("s3")
        assertEquals(mapOf("s2" to 1, "s1" to 1), harness.stopCounts())
        assertEquals(listOf(SubtitleIdentity.Off), harness.persistence.map { it.first.identity })
        assertEquals("s3", harness.persistence.single().second.sessionId)
        harness.assertNoOrphans()
    }

    @Test
    fun `burn-in commits without a Media3 text selection`() = runTest {
        val burnIn = SubtitleIdentity.ServerBurnIn(B_INDEX)
        val harness = harness(
            replanResponse = { _, _ -> response(burnInPlan("s2", FILE_ID, B_INDEX)) },
        )
        harness.start(sidecarA)

        harness.adapter.select(burnIn)
        runCurrent()
        harness.awaitReplans(1)
        harness.awaitAdopted("s2")
        harness.awaitStopped("s1")
        harness.awaitPersistence(1)
        runCurrent()

        assertEquals(listOf("s1"), harness.replanBaseSessions)
        assertReplan(harness.replanBodies.single(), audioIndex = 0, subtitleIndex = B_INDEX)
        harness.assertActiveSession("s2")
        assertEquals(mapOf("s1" to 1), harness.stopCounts())
        assertTrue(harness.media3Selections.isEmpty())
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(listOf(burnIn), harness.persistence.map { it.first.identity })
        assertEquals("s2", harness.persistence.single().second.sessionId)
        harness.assertNoOrphans()
    }

    @Test
    fun `audio replan rebases downloaded subtitle and waits for exact download mount`() = runTest {
        val downloaded = downloadedIdentity(DOWNLOAD_ID)
        val originalDownloaded = downloadedRow(
            index = 40,
            downloadId = DOWNLOAD_ID,
            url = "/stream/s1/subtitles/$DOWNLOAD_ID.vtt",
        )
        val harness = harness(
            replanResponse = { _, _ -> response(basePlan("s2", FILE_ID, audioIndex = 2)) },
        )
        harness.start(
            committedIdentity = downloaded,
            subtitleTracks = listOf(originalDownloaded),
            audioTracks = listOf(AudioTrack(index = 0), AudioTrack(index = 2)),
        )
        assertStart(harness.startBodies.single(), audioIndex = 0, subtitleIndex = -1)
        harness.assertActiveSession("s1")
        assertTrue(harness.adapter.snapshot.subtitleTracks.none { it.source == "server_artifact" })
        harness.adapter.restoreCommittedLocalMount()
        assertEquals(downloaded, harness.adapter.snapshot.localMountIdentity)
        harness.mountPending(
            expectedSessionId = "s1",
            tracks = listOf(
                harness.downloadedMountedTrack(
                    expectedSessionId = "s1",
                    downloadId = DOWNLOAD_ID,
                    playerIndex = 7,
                ),
            ),
        )
        runCurrent()
        assertEquals(downloaded, harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertTrue(harness.persistence.isEmpty())
        assertEquals(
            listOf(Harness.MountedSelection("s1", 7)),
            harness.media3Selections,
        )

        harness.adapter.selectAudio(2)
        runCurrent()
        harness.awaitReplans(1)
        harness.awaitAdopted("s2")

        assertEquals(listOf("s1"), harness.replanBaseSessions)
        assertReplan(harness.replanBodies.single(), audioIndex = 2, subtitleIndex = -1)
        harness.assertActiveSession("s2")
        assertTrue(harness.stoppedSessions.isEmpty())
        assertTrue(harness.persistence.isEmpty())
        assertEquals(
            "/stream/s2/subtitles/$DOWNLOAD_ID.vtt",
            harness.adapter.snapshot.subtitleTracks.single { it.downloadId == DOWNLOAD_ID }.url,
        )

        harness.mountPending(
            expectedSessionId = "s2",
            tracks =
            listOf(
                media3Track(3, "download-decoy", "English"),
                harness.downloadedMountedTrack(
                    expectedSessionId = "s2",
                    downloadId = DOWNLOAD_ID,
                    playerIndex = 8,
                ),
            ),
        )
        runCurrent()
        harness.awaitStopped("s1")
        harness.awaitPersistence(1)
        runCurrent()

        assertEquals(
            listOf(
                Harness.MountedSelection("s1", 7),
                Harness.MountedSelection("s2", 8),
            ),
            harness.media3Selections,
        )
        harness.assertActiveSession("s2")
        assertEquals(mapOf("s1" to 1), harness.stopCounts())
        assertEquals(1, harness.persistence.size)
        assertEquals(downloaded, harness.persistence.single().first.identity)
        assertEquals(2, harness.persistence.single().first.audioTrackIndex)
        assertEquals("s2", harness.persistence.single().second.sessionId)
        harness.assertNoOrphans()
    }

    @Test
    fun `refresh owner cannot cross a content reset and a fresh owner remains live`() = runTest {
        val harness = harness(replanResponse = { _, _ -> error("No replan expected") })
        harness.start(sidecarA)
        val staleOwner = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Download)
        val staleRow = downloadedRow(50, 501, "/stream/s1/subtitles/downloaded/501.vtt")

        val resetContext = harness.replaceContent(
            contentId = "content-2",
            mediaFileId = 84,
            versionId = "version-84",
            subtitleTracks = emptyList(),
        )
        harness.adapter.resetContent(resetContext, SubtitleIdentity.Off)
        harness.awaitStopped("s1")
        runCurrent()
        val afterReset = harness.adapter.snapshot
        val persistenceBefore = harness.persistence.toList()
        val selectionsBefore = harness.media3Selections.toList()

        assertFalse(harness.adapter.applyRefresh(staleOwner, listOf(staleRow), 501))
        assertFalse(harness.adapter.selectFromRefresh(staleOwner, downloadedIdentity(501)))
        runCurrent()

        assertEquals(afterReset, harness.adapter.snapshot)
        assertEquals(persistenceBefore, harness.persistence)
        assertEquals(selectionsBefore, harness.media3Selections)
        assertTrue(harness.replanBodies.isEmpty())
        harness.assertActiveSession("s2")
        assertEquals(mapOf("s1" to 1), harness.stopCounts())
        harness.assertNoOrphans()

        val freshOwner = harness.adapter.beginRefresh(TvSubtitleRefreshSource.Download)
        val freshRow = downloadedRow(51, 502, "/stream/s2/subtitles/downloaded/502.vtt")
        assertTrue(harness.adapter.applyRefresh(freshOwner, listOf(freshRow), null))

        assertEquals(afterReset.subtitleRefreshNonce + 1, harness.adapter.snapshot.subtitleRefreshNonce)
        assertEquals(
            "/stream/s2/subtitles/downloaded/502.vtt",
            harness.adapter.snapshot.subtitleTracks.single { it.downloadId == 502 }.url,
        )
        assertEquals(SubtitleIdentity.Off, harness.adapter.snapshot.committedIdentity)
        assertNull(harness.adapter.snapshot.pendingIdentity)
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(persistenceBefore, harness.persistence)
        assertTrue(harness.replanBodies.isEmpty())
    }

    private fun TestScope.harness(
        replanResponse: suspend (Int, JsonObject) -> PlaybackDecisionResponseV3,
        committedSessionCleanupScope: CoroutineScope = backgroundScope,
        committedSessionCleanupScheduler: TestCoroutineScheduler = testScheduler,
    ): Harness = Harness(
        scope = backgroundScope,
        transactionScheduler = testScheduler,
        committedSessionCleanupScope = committedSessionCleanupScope,
        committedSessionCleanupScheduler = committedSessionCleanupScheduler,
        replanResponse = replanResponse,
    )

    private class Harness(
        private val scope: CoroutineScope,
        private val transactionScheduler: TestCoroutineScheduler,
        committedSessionCleanupScope: CoroutineScope,
        private val committedSessionCleanupScheduler: TestCoroutineScheduler,
        private val replanResponse: suspend (Int, JsonObject) -> PlaybackDecisionResponseV3,
    ) {
        val stoppedSessions: MutableList<String> =
            Collections.synchronizedList(mutableListOf())
        val replanBodies: MutableList<JsonObject> =
            Collections.synchronizedList(mutableListOf())
        val startBodies: MutableList<JsonObject> =
            Collections.synchronizedList(mutableListOf())
        val replanBaseSessions: MutableList<String> =
            Collections.synchronizedList(mutableListOf())
        val persistence: MutableList<Pair<CommittedSubtitle, TvSubtitlePlaybackContext>> =
            Collections.synchronizedList(mutableListOf())
        private val adoptedPlaybackRows: MutableMap<String, List<PlayerSubtitleInfo>> =
            Collections.synchronizedMap(mutableMapOf())
        val media3Selections = mutableListOf<MountedSelection>()

        private val replanEvents = Channel<Unit>(Channel.UNLIMITED)
        private val persistenceEvents = Channel<Unit>(Channel.UNLIMITED)
        private val startIndex = AtomicInteger()
        private val replanIndex = AtomicInteger()
        private val remount = SubtitleRemountReselection()
        private var mountGeneration = 0L
        private val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val payload = when {
                    path == "/api/v1/playback/start" -> {
                        val body = SiloJson.parseToJsonElement(
                            request.body.toByteArray().decodeToString(),
                        ).jsonObject
                        startBodies += body
                        when (startIndex.getAndIncrement()) {
                            0 -> if (
                                (body["subtitle_track_index"]?.jsonPrimitive?.intOrNull ?: -1) == -1
                            ) {
                                response(basePlan("s1", FILE_ID, audioIndex = 0))
                            } else {
                                response(sidecarPlan("s1", FILE_ID, A_INDEX))
                            }
                            1 -> response(basePlan("s2", 84, audioIndex = 0))
                            else -> error("Unexpected playback start")
                        }
                    }
                    path.endsWith("/replan") -> {
                        replanBaseSessions += path
                            .substringBeforeLast("/replan")
                            .substringAfterLast('/')
                        val body = SiloJson.parseToJsonElement(
                            request.body.toByteArray().decodeToString(),
                        ).jsonObject
                        replanBodies += body
                        replanEvents.send(Unit)
                        replanResponse(replanIndex.getAndIncrement(), body)
                    }
                    request.method == HttpMethod.Delete &&
                        path.startsWith("/api/v1/playback/") -> {
                        val sessionId = path.substringAfterLast('/')
                        stoppedSessions += sessionId
                        null
                    }
                    else -> null
                }
                respond(
                    content = payload?.let(SiloJson::encodeToString) ?: "{}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val manager = PlaybackSessionManager(
            playbackRepository = PlaybackRepository(PlaybackApi(client)),
            tokenManager = IntegrationTokenManager,
            committedSessionCleanupScope = committedSessionCleanupScope,
        )
        val lifecycle = PlaybackSessionLifecycle(
            sessionManager = manager,
            profileRepository = IntegrationProfileRepository(),
            healthApi = IntegrationHealthApi(),
            personalDataRepository = IntegrationPersonalDataRepository(),
            scope = scope,
        )
        lateinit var adapter: TvSubtitleTransactionAdapter
            private set

        suspend fun start(
            committedIdentity: SubtitleIdentity,
            subtitleTracks: List<PlayerSubtitleInfo> = emptyList(),
            audioTracks: List<AudioTrack> = listOf(AudioTrack(index = 0)),
        ) {
            val initialSubtitleIndex = committedIdentity.serverTrackIndex()
            val startResult = manager.startVideoSessionV3(
                        fileId = FILE_ID,
                        profileId = PROFILE_ID,
                        capabilities = capabilities,
                        clientPlaybackContext = playbackContext,
                        audioTrackIndex = 0,
                        subtitleTrackIndex = initialSubtitleIndex,
                        qualityPreference = "original",
                        startPosition = 42.0,
                        subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                    )
            val success = assertIs<ApiResult.Success<VideoSessionStartV3>>(
                startResult,
                (startResult as? ApiResult.NetworkError)?.exception?.stackTraceToString(),
            )
            val ready = assertIs<VideoSessionStartV3.Ready>(
                success.data,
            )
            if (committedIdentity is SubtitleIdentity.Downloaded) {
                assertEquals(PlaybackSubtitleModeV3.OFF, ready.plan.subtitle.mode)
                assertNull(ready.plan.subtitle.artifact)
                assertNull(ready.plan.selectedTracks.subtitle)
                assertTrue(ready.session.subtitleUrls.isNullOrEmpty())
            }
            lifecycle.adoptActiveSession(
                params = startParams(
                    contentId = CONTENT_ID,
                    fileId = FILE_ID,
                    audioTrackIndex = 0,
                    subtitleTrackIndex = initialSubtitleIndex,
                ),
                session = ready.session,
                manageProgress = false,
                renewMissingSessionWithLegacyStart = false,
            )
            adapter = TvSubtitleTransactionAdapter(
                scope = scope,
                stagedPort = PlaybackSessionManagerTvSubtitleStagedReplanPort(manager, lifecycle),
                persistencePort = object : TvSubtitlePersistencePort {
                    override suspend fun persist(
                        committed: CommittedSubtitle,
                        context: TvSubtitlePlaybackContext,
                    ): Boolean {
                        persistence += committed to context
                        persistenceEvents.send(Unit)
                        return true
                    }
                },
                durablePersistenceScope = scope,
                settlementScope = scope,
                onCommittedPlayback = { adoption ->
                    val candidate = requireNotNull(adoption.playback.ready)
                    val adopted = lifecycle.adoptActiveSessionIfCurrent(
                        params = startParams(
                            contentId = CONTENT_ID,
                            fileId = candidate.session.mediaFileId,
                            audioTrackIndex = adoption.committed.audioTrackIndex,
                            subtitleTrackIndex = adoption.committed.identity.serverTrackIndex(),
                        ),
                        session = candidate.session,
                        manageProgress = false,
                        renewMissingSessionWithLegacyStart = false,
                        deferPublication = true,
                        isCurrent = adoption::isCurrent,
                    )
                    if (adopted && adoption.isCurrent()) {
                        adoptedPlaybackRows[candidate.session.sessionId] =
                            adoption.playback.subtitleTracks
                        TvSubtitleAdoptionResult.Adopted
                    } else {
                        TvSubtitleAdoptionResult.Superseded
                    }
                },
                onCommittedPlaybackConfirmed = { true },
                onCommittedPlaybackRollback = { _, _ -> true },
            )
            adapter.resetContent(
                context = context(
                    subtitleTracks = subtitleTracks,
                    audioTracks = audioTracks,
                ),
                committedIdentity = committedIdentity,
            )
        }

        suspend fun replaceContent(
            contentId: String,
            mediaFileId: Int,
            versionId: String,
            subtitleTracks: List<PlayerSubtitleInfo>,
        ): TvSubtitlePlaybackContext {
            val ready = assertIs<VideoSessionStartV3.Ready>(
                assertIs<ApiResult.Success<VideoSessionStartV3>>(
                    manager.startVideoSessionV3(
                        fileId = mediaFileId,
                        profileId = PROFILE_ID,
                        capabilities = capabilities,
                        clientPlaybackContext = playbackContext,
                        audioTrackIndex = 0,
                        subtitleTrackIndex = -1,
                        qualityPreference = "original",
                        startPosition = 0.0,
                        subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                    ),
                ).data,
            )
            lifecycle.adoptActiveSession(
                params = startParams(
                    contentId = contentId,
                    fileId = mediaFileId,
                    audioTrackIndex = 0,
                    subtitleTrackIndex = -1,
                ),
                session = ready.session,
                manageProgress = false,
                renewMissingSessionWithLegacyStart = false,
            )
            assertIs<ApiResult.Success<Unit>>(manager.stopSession("s1"))
            return context(
                contentId = contentId,
                mediaFileId = mediaFileId,
                versionId = versionId,
                sessionId = ready.session.sessionId,
                subtitleTracks = subtitleTracks,
            )
        }

        fun context(
            contentId: String = CONTENT_ID,
            mediaFileId: Int = FILE_ID,
            versionId: String = "version-$FILE_ID",
            sessionId: String = "s1",
            subtitleTracks: List<PlayerSubtitleInfo> = emptyList(),
            audioTracks: List<AudioTrack> = listOf(AudioTrack(index = 0)),
        ): TvSubtitlePlaybackContext = TvSubtitlePlaybackContext(
            contentId = contentId,
            mediaFileId = mediaFileId,
            versionId = versionId,
            sessionId = sessionId,
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            qualityPreference = "original",
            subtitleTracks = subtitleTracks,
            audioTracks = audioTracks,
            outputRouteGeneration = OUTPUT_GENERATION,
            capabilities = capabilities,
            clientPlaybackContext = playbackContext,
            writeScope = tvTestPlaybackWriteScope,
        )

        fun mountPending(
            expectedSessionId: String,
            tracks: List<PlayerTrackEntry>,
        ) {
            assertActiveSession(expectedSessionId)
            val identity = requireNotNull(adapter.snapshot.localMountIdentity)
            mountGeneration += 1
            remount.arm(identity, mountGeneration)
            val event = assertIs<TvSubtitleRemountEvent.Select>(
                remount.consume(
                    subtitleTracks = tracks,
                    snapshotKey = "mount-$mountGeneration",
                    settled = true,
                ),
            )
            media3Selections += MountedSelection(expectedSessionId, event.trackIndex)
            adapter.reportMountedSelection(
                identity = event.owner.identity,
                selected = true,
                snapshotKey = "mount-$mountGeneration",
                settled = true,
            )
        }

        fun sidecarMountedTrack(
            expectedSessionId: String,
            serverIndex: Int,
            playerIndex: Int,
        ): PlayerTrackEntry {
            val row = mountedRow(expectedSessionId) {
                it.index == serverIndex && it.source == "server_artifact"
            }
            assertEquals("/stream/$expectedSessionId/subtitles/$serverIndex.vtt", row.url)
            val artifactTrackId = subtitleArtifactTrackId(row.index)
            assertEquals(subtitleArtifactTrackId(serverIndex), artifactTrackId)
            return media3Track(
                index = playerIndex,
                trackId = artifactTrackId,
                label = row.label ?: error("Adopted sidecar row omitted its label"),
            )
        }

        fun downloadedMountedTrack(
            expectedSessionId: String,
            downloadId: Int,
            playerIndex: Int,
        ): PlayerTrackEntry {
            val row = mountedRow(expectedSessionId) { it.downloadId == downloadId }
            assertEquals("/stream/$expectedSessionId/subtitles/$downloadId.vtt", row.url)
            val artifactTrackId = downloadedSubtitleArtifactTrackId(
                requireNotNull(row.downloadId),
            )
            assertEquals(downloadedSubtitleArtifactTrackId(downloadId), artifactTrackId)
            return media3Track(
                index = playerIndex,
                trackId = artifactTrackId,
                label = row.label ?: error("Downloaded row omitted its label"),
            )
        }

        private fun mountedRow(
            expectedSessionId: String,
            predicate: (PlayerSubtitleInfo) -> Boolean,
        ): PlayerSubtitleInfo {
            assertActiveSession(expectedSessionId)
            val snapshotRow = adapter.snapshot.subtitleTracks.single(predicate)
            adoptedPlaybackRows[expectedSessionId]?.let { adoptedRows ->
                assertEquals(snapshotRow, adoptedRows.single(predicate))
            }
            return snapshotRow
        }

        suspend fun awaitStopped(sessionId: String) {
            if (sessionId in stoppedSessions) return
            awaitHarnessCondition(
                transactionScheduler = transactionScheduler,
                cleanupScheduler = committedSessionCleanupScheduler,
                timeoutMillis = EVENT_TIMEOUT_MS,
                condition = { sessionId in stoppedSessions },
            )
        }

        suspend fun awaitReplans(count: Int) {
            while (replanBodies.size < count) {
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { replanEvents.receive() }
                }
            }
        }

        suspend fun awaitAdopted(sessionId: String) {
            awaitHarnessCondition(
                transactionScheduler = transactionScheduler,
                cleanupScheduler = transactionScheduler,
                timeoutMillis = EVENT_TIMEOUT_MS,
                condition = {
                    manager.activeSessionIdForTest() == sessionId &&
                        lifecycle.activeSessionId() == sessionId
                },
            )
        }

        suspend fun awaitPersistence(count: Int) {
            while (persistence.size < count) {
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { persistenceEvents.receive() }
                }
            }
        }

        fun stopCounts(): Map<String, Int> =
            stoppedSessions.groupingBy { it }.eachCount()

        fun assertActiveSession(expectedSessionId: String) {
            assertEquals(expectedSessionId, manager.activeSessionIdForTest())
            assertEquals(expectedSessionId, lifecycle.activeSessionId())
        }

        suspend fun assertNoOrphans() {
            awaitHarnessCondition(
                transactionScheduler = transactionScheduler,
                cleanupScheduler = committedSessionCleanupScheduler,
                timeoutMillis = EVENT_TIMEOUT_MS,
                condition = { manager.orphanedSessionIdsForTest().isEmpty() },
            )
            assertEquals(emptySet(), manager.orphanedSessionIdsForTest())
        }

        data class MountedSelection(
            val sessionId: String,
            val trackIndex: Int,
        )
    }

    private companion object {
        // Publication cleanup, orphan drainage, replan, adoption, and
        // persistence are all owned by this harness's structured scope. Keep a
        // short deadlock backstop: hosted scheduling must not require a wider
        // wall-clock allowance.
        const val EVENT_TIMEOUT_MS = 5_000L

        const val CONTENT_ID = "content-1"
        const val FILE_ID = 42
        const val PROFILE_ID = "profile-1"
        const val A_INDEX = 3
        const val B_INDEX = 4
        const val DOWNLOAD_ID = 312
        const val OUTPUT_GENERATION = 7L

        val sidecarA = SubtitleIdentity.ServerSidecar(A_INDEX)
        val sidecarB = SubtitleIdentity.ServerSidecar(
            B_INDEX,
            SubtitleMediaIdentity(label = "English", language = "en", codecFamily = "webvtt"),
        )
        val capabilities = ClientCodecCapabilities(
            codecsVideo = listOf("hevc"),
            codecsAudio = listOf("eac3"),
            containers = listOf("mkv"),
        )
        val playbackContext = ClientPlaybackContext(
            formFactor = "tv",
            appVersion = "integration-test",
            output = PlaybackOutputContext(outputRouteGeneration = OUTPUT_GENERATION),
        )

        fun startParams(
            contentId: String,
            fileId: Int,
            audioTrackIndex: Int?,
            subtitleTrackIndex: Int?,
        ) = StartParams(
            contentId = contentId,
            fileId = fileId,
            capabilities = capabilities,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
            qualityPreference = "original",
            startPosition = 42.0,
            clientPlaybackContext = playbackContext,
        )

        fun response(plan: PlaybackPlanV3) = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            sessionId = plan.sessionId,
            playbackPlan = plan,
        )

        fun basePlan(
            sessionId: String,
            fileId: Int,
            audioIndex: Int,
        ) = PlaybackPlanV3(
            planId = "plan-$sessionId",
            sessionId = sessionId,
            delivery = PlaybackDelivery.SERVER_REMUX_HLS,
            engine = PlaybackEngineKind.MEDIA3_HLS,
            stream = PlaybackStreamV3(
                url = "/stream/$sessionId/master.m3u8",
                protocol = PlaybackStreamProtocol.HLS,
                container = "mpegts",
                mimeType = "application/x-mpegURL",
            ),
            selectedTracks = SelectedPlaybackTracksV3(
                audio = PlaybackTrackIdentityV3("file:$fileId:audio:$audioIndex", audioIndex),
            ),
            effectiveRecipe = PlaybackEffectiveRecipeV3(
                videoCodec = "hevc",
                audioCodec = "eac3",
            ),
            decisionReason = "integration-test",
            requestedMediaFileId = fileId,
            effectiveMediaFileId = fileId,
        )

        fun sidecarPlan(
            sessionId: String,
            fileId: Int,
            subtitleIndex: Int,
        ) = basePlan(sessionId, fileId, audioIndex = 0).copy(
            selectedTracks = SelectedPlaybackTracksV3(
                audio = PlaybackTrackIdentityV3("file:$fileId:audio:0", 0),
                subtitle = PlaybackTrackIdentityV3(
                    "file:$fileId:subtitle:$subtitleIndex",
                    subtitleIndex,
                ),
            ),
            subtitle = PlaybackSubtitleDecisionV3(
                mode = PlaybackSubtitleModeV3.CONVERT,
                trackId = "file:$fileId:subtitle:$subtitleIndex",
                artifact = PlaybackSubtitleArtifactV3(
                    url = "/stream/$sessionId/subtitles/$subtitleIndex.vtt",
                    mimeType = "text/vtt",
                    format = "webvtt",
                ),
            ),
        )

        fun burnInPlan(
            sessionId: String,
            fileId: Int,
            subtitleIndex: Int,
        ) = basePlan(sessionId, fileId, audioIndex = 0).copy(
            selectedTracks = SelectedPlaybackTracksV3(
                audio = PlaybackTrackIdentityV3("file:$fileId:audio:0", 0),
                subtitle = PlaybackTrackIdentityV3(
                    "file:$fileId:subtitle:$subtitleIndex",
                    subtitleIndex,
                ),
            ),
            subtitle = PlaybackSubtitleDecisionV3(
                mode = PlaybackSubtitleModeV3.BURN_IN,
                trackId = "file:$fileId:subtitle:$subtitleIndex",
            ),
        )

        fun assertReplan(body: JsonObject, audioIndex: Int, subtitleIndex: Int) {
            val selected = body.getValue("selected_tracks").jsonObject
            assertEquals(audioIndex, selected.getValue("audio").jsonObject.getValue("index").jsonPrimitive.int)
            if (subtitleIndex < 0) {
                assertTrue(selected["subtitle"] == null || selected["subtitle"].toString() == "null")
            } else {
                assertEquals(
                    subtitleIndex,
                    selected.getValue("subtitle").jsonObject.getValue("index").jsonPrimitive.int,
                )
            }
            assertEquals(OUTPUT_GENERATION, body.getValue("output_route_generation").jsonPrimitive.content.toLong())
        }

        fun assertStart(body: JsonObject, audioIndex: Int, subtitleIndex: Int) {
            assertEquals(audioIndex, body.getValue("audio_track_index").jsonPrimitive.int)
            assertEquals(
                subtitleIndex,
                body["subtitle_track_index"]?.jsonPrimitive?.intOrNull ?: -1,
            )
            assertEquals(
                OUTPUT_GENERATION,
                body.getValue("output_route_generation").jsonPrimitive.content.toLong(),
            )
        }

        fun downloadedIdentity(downloadId: Int) = SubtitleIdentity.Downloaded(
            downloadId = downloadId,
            media = SubtitleMediaIdentity(
                trackId = downloadedSubtitleArtifactTrackId(downloadId),
                label = "English",
                language = "en",
                codecFamily = "webvtt",
                forced = false,
                hearingImpaired = false,
            ),
        )

        fun downloadedRow(index: Int, downloadId: Int, url: String) = PlayerSubtitleInfo(
            index = index,
            language = "en",
            codec = "webvtt",
            label = "English",
            source = "downloaded",
            forced = false,
            url = url,
            downloadId = downloadId,
        )

        fun media3Track(index: Int, trackId: String, label: String) = PlayerTrackEntry(
            index = index,
            trackId = trackId,
            label = label,
            displayLabel = label,
            language = "en",
            codecOrMime = "text/vtt",
            isSelected = false,
        )
    }
}

private fun PlaybackSessionLifecycle.activeSessionId(): String? =
    (state.value as? SessionState.Active)?.session?.sessionId

private fun SubtitleIdentity.serverTrackIndex(): Int = when (this) {
    SubtitleIdentity.Off -> -1
    is SubtitleIdentity.ServerSidecar -> serverIndex
    is SubtitleIdentity.ServerBurnIn -> serverIndex
    is SubtitleIdentity.Embedded -> serverIndex
    is SubtitleIdentity.Downloaded,
    is SubtitleIdentity.LocalMedia3,
    -> -1
}

private class IntegrationProfileRepository : ProfileRepository(
    profileApi = ProfileApi(HttpClient()),
    tokenManager = IntegrationTokenManager,
) {
    override suspend fun getActiveProfileId(): String = "profile-1"
}

private class IntegrationHealthApi : HealthApi(HttpClient()) {
    override suspend fun checkHealth(): ApiResult<HealthStatus> =
        ApiResult.Success(HealthStatus(status = "ok"))
}

private class IntegrationPersonalDataRepository : PersonalDataRepository(
    personalDataApi = PersonalDataApi(HttpClient()),
) {
    override suspend fun syncProgress(items: List<SyncProgressItem>): ApiResult<Unit> =
        ApiResult.Success(Unit)
}

private object IntegrationTokenManager : TokenManager {
    override val sessionExpired: SharedFlow<Unit> = MutableSharedFlow()
    override suspend fun getAccessToken(): String? = null
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {}
    override suspend fun clearTokens() {}
    override suspend fun invalidateSession() {}
    override suspend fun getProfileId(): String? = null
    override suspend fun setProfileId(profileId: String?) {}
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) {}
    override suspend fun getServerUrl(): String = ""
    override suspend fun setServerUrl(url: String) {}
    override suspend fun getCurrentServerId(): String? = null
    override suspend fun switchActiveServer(serverId: String?) {}
    override suspend fun signOutCurrentServer() {}
    override suspend fun snapshotCurrentScope(): AuthScopeSnapshot? = null
}
