package org.siloserver.silo.common.player

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import org.siloserver.silo.model.personal.SyncProgressItem
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PLAYBACK_PLAN_V3_FEATURE
import org.siloserver.silo.model.playback.NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE
import org.siloserver.silo.model.playback.SEEK_REANCHOR_V3_FEATURE
import org.siloserver.silo.model.playback.PlaybackDecisionOutcome
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEffectiveRecipeV3
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
import org.siloserver.silo.model.playback.PlaybackStreamV3
import org.siloserver.silo.model.playback.PlaybackTimelineV3
import org.siloserver.silo.model.playback.PlaybackTrackIdentityV3
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.network.api.HealthStatus
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.PlaybackApi
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.PlaybackRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaybackPublicationSettlementIntegrationTest {
    @Test
    fun `joint rollback converges before failed B cleanup and next C drains orphan`() =
        runTest {
            val harness = SettlementHarness(
                scope = backgroundScope,
                starts = listOf(
                    response(plan("session-a", 42)),
                    response(plan("session-b", 84)),
                    response(plan("session-c", 126)),
                ),
                stopThrowableBehavior = { sessionId, attempt ->
                    if (sessionId == "session-b" && attempt <= 2) {
                        AssertionError("raw stop failure $attempt")
                    } else {
                        null
                    }
                },
            )
            harness.startAndAdopt(fileId = 42, deferPublication = false)
            harness.startAndAdopt(fileId = 84, deferPublication = true)

            assertTrue(
                harness.lifecycle.rollbackCurrentPendingPublication { sessionId ->
                    harness.manager.rollbackUnpublishedVideoSession(sessionId)
                },
            )

            assertEquals("session-a", harness.manager.activeSessionIdForTest())
            assertEquals("session-a", harness.lifecycle.activeSessionId())
            assertEquals(setOf("session-b"), harness.manager.orphanedSessionIdsForTest())
            assertEquals(2, harness.stopAttempts("session-b"))

            harness.startAndAdopt(fileId = 126, deferPublication = true)

            assertEquals("session-c", harness.manager.activeSessionIdForTest())
            assertEquals("session-c", harness.lifecycle.activeSessionId())
            assertEquals(emptySet(), harness.manager.orphanedSessionIdsForTest())
            assertEquals(3, harness.stopAttempts("session-b"))
        }

    @Test
    fun `joint rollback cancellation converges lifecycle before cancellation surfaces`() =
        runTest {
            val stopEntered = CompletableDeferred<Unit>()
            val releaseStop = CompletableDeferred<Unit>()
            val harness = SettlementHarness(
                scope = backgroundScope,
                starts = listOf(
                    response(plan("session-a", 42)),
                    response(plan("session-b", 84)),
                    response(plan("session-c", 126)),
                ),
                stopBehavior = { sessionId, attempt ->
                    if (sessionId == "session-b" && attempt == 1) {
                        stopEntered.complete(Unit)
                        releaseStop.await()
                    }
                    HttpStatusCode.OK
                },
            )
            harness.startAndAdopt(fileId = 42, deferPublication = false)
            harness.startAndAdopt(fileId = 84, deferPublication = true)

            val rollback = async {
                harness.lifecycle.rollbackCurrentPendingPublication { sessionId ->
                    harness.manager.rollbackUnpublishedVideoSession(sessionId)
                }
            }
            stopEntered.await()
            rollback.cancel(CancellationException("cancel joint rollback"))
            releaseStop.complete(Unit)
            rollback.join()

            assertEquals("session-a", harness.manager.activeSessionIdForTest())
            assertEquals("session-a", harness.lifecycle.activeSessionId())
            assertEquals(emptySet(), harness.manager.orphanedSessionIdsForTest())
            assertFailsWith<CancellationException> { rollback.await() }

            harness.startAndAdopt(fileId = 126, deferPublication = true)
            assertEquals("session-c", harness.manager.activeSessionIdForTest())
            assertEquals("session-c", harness.lifecycle.activeSessionId())
        }

    @Test
    fun `post Ready failure restores A stops B and leaves the next load unblocked`() =
        runTest {
            val harness = SettlementHarness(
                scope = backgroundScope,
                starts = listOf(
                    response(plan("session-a", 42)),
                    response(plan("session-b", 84)),
                    response(plan("session-c", 126)),
                ),
            )
            harness.startAndAdopt(fileId = 42, deferPublication = false)
            harness.startAndAdopt(fileId = 84, deferPublication = true)

            assertTrue(
                harness.lifecycle.rollbackCurrentPendingPublication { sessionId ->
                    harness.manager.rollbackUnpublishedVideoSession(sessionId)
                },
            )
            assertEquals("session-a", harness.manager.activeSessionIdForTest())
            assertEquals("session-a", harness.lifecycle.activeSessionId())
            assertEquals(mapOf("session-b" to 1), harness.stopCounts())

            harness.startAndAdopt(fileId = 126, deferPublication = true)
            assertEquals("session-c", harness.manager.activeSessionIdForTest())
            assertEquals("session-c", harness.lifecycle.activeSessionId())

            assertTrue(
                harness.lifecycle.rollbackCurrentPendingPublication { sessionId ->
                    harness.manager.rollbackUnpublishedVideoSession(sessionId)
                },
            )
            assertEquals("session-a", harness.manager.activeSessionIdForTest())
            assertEquals("session-a", harness.lifecycle.activeSessionId())
            assertEquals(
                mapOf("session-b" to 1, "session-c" to 1),
                harness.stopCounts(),
            )
        }

    @Test
    fun `seek waits for joint B rollback and never splits lifecycle from manager UI`() =
        runTest {
            val planA = plan("session-a", 42)
            val harness = SettlementHarness(
                scope = backgroundScope,
                starts = listOf(
                    response(
                        planA,
                        features = listOf(
                            PLAYBACK_PLAN_V3_FEATURE,
                            NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE,
                            SEEK_REANCHOR_V3_FEATURE,
                        ),
                    ),
                    response(plan("session-b", 84)),
                ),
                replanResponse = response(
                    planA.copy(
                        timeline = PlaybackTimelineV3(
                            sourceStartSeconds = 90.0,
                            streamOriginSeconds = 90.0,
                            playerStartSeconds = 0.0,
                            timelineOffsetSeconds = 90.0,
                            canSeekAnywhere = false,
                            seekRestoration = "source_position",
                        ),
                    ),
                    features = listOf(
                        PLAYBACK_PLAN_V3_FEATURE,
                        NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE,
                        SEEK_REANCHOR_V3_FEATURE,
                    ),
                ),
            )
            harness.startAndAdopt(fileId = 42, deferPublication = false)
            harness.startAndAdopt(fileId = 84, deferPublication = true)

            val seek = async {
                harness.manager.reanchorActiveVideoSession(positionSeconds = 90.0)
            }
            repeat(3) { yield() }

            assertFalse(seek.isCompleted)
            assertEquals(0, harness.replanCalls)
            assertEquals("session-b", harness.manager.activeSessionIdForTest())
            assertEquals("session-b", harness.lifecycle.activeSessionId())

            assertTrue(
                harness.lifecycle.rollbackCurrentPendingPublication { sessionId ->
                    harness.manager.rollbackUnpublishedVideoSession(sessionId)
                },
            )
            assertIs<ApiResult.Success<VideoSessionStartV3>>(seek.await())

            assertEquals(1, harness.replanCalls)
            assertEquals("session-a", harness.manager.activeSessionIdForTest())
            assertEquals("session-a", harness.lifecycle.activeSessionId())
        }

    @Test
    fun `fresh load preflight rolls pending B back before failed C and exit stops A`() =
        runTest {
            val harness = SettlementHarness(
                scope = backgroundScope,
                starts = listOf(
                    response(plan("session-a", 42)),
                    response(plan("session-b", 84)),
                ),
            )
            harness.startAndAdopt(fileId = 42, deferPublication = false)
            harness.startAndAdopt(fileId = 84, deferPublication = true)

            assertTrue(
                harness.lifecycle.rollbackCurrentPendingPublication { sessionId ->
                    harness.manager.rollbackUnpublishedVideoSession(sessionId)
                },
            )

            // C fails before allocating a replacement. Both authoritative
            // owners and the lifecycle-backed UI must still expose A.
            assertEquals("session-a", harness.manager.activeSessionIdForTest())
            assertEquals("session-a", harness.lifecycle.activeSessionId())

            // A stale B cleanup arriving after the preflight is harmless.
            assertFalse(harness.manager.rollbackUnpublishedVideoSession("session-b"))
            assertFalse(harness.lifecycle.rollbackUnpublishedActiveSession("session-b"))
            assertEquals("session-a", harness.manager.activeSessionIdForTest())
            assertEquals("session-a", harness.lifecycle.activeSessionId())

            harness.lifecycle.stop()

            assertEquals(null, harness.manager.activeSessionIdForTest())
            assertEquals(null, harness.lifecycle.activeSessionId())
            assertEquals(
                mapOf("session-a" to 1, "session-b" to 1),
                harness.stopCounts(),
            )
        }

    @Test
    fun `joint rollback restores manager and lifecycle predecessor`() = runTest {
        val harness = SettlementHarness(
            scope = backgroundScope,
            starts = listOf(
                response(plan("session-a", 42)),
                response(plan("session-b", 84)),
            ),
        )
        harness.startAndAdopt(fileId = 42, deferPublication = false)
        harness.startAndAdopt(fileId = 84, deferPublication = true)

        assertTrue(
            harness.lifecycle.settlePendingPublicationIfCurrent(
                sessionId = "session-b",
                confirm = false,
                settleManager = {
                    harness.manager.rollbackUnpublishedVideoSession("session-b")
                },
            ),
        )

        assertEquals("session-a", harness.manager.activeSessionIdForTest())
        assertEquals("session-a", harness.lifecycle.activeSessionId())
        assertEquals(mapOf("session-b" to 1), harness.stopCounts())
    }

    @Test
    fun `joint confirm retains replacement in manager and lifecycle and stops predecessor`() =
        runTest {
            val harness = SettlementHarness(
                scope = backgroundScope,
                starts = listOf(
                    response(plan("session-a", 42)),
                    response(plan("session-b", 84)),
                ),
            )
            harness.startAndAdopt(fileId = 42, deferPublication = false)
            harness.startAndAdopt(fileId = 84, deferPublication = true)

            assertTrue(
                harness.lifecycle.settlePendingPublicationIfCurrent(
                    sessionId = "session-b",
                    confirm = true,
                    settleManager = {
                        harness.manager.confirmVideoSessionPublication("session-b")
                    },
                ),
            )
            harness.awaitStopped("session-a")

            assertEquals("session-b", harness.manager.activeSessionIdForTest())
            assertEquals("session-b", harness.lifecycle.activeSessionId())
            assertEquals(mapOf("session-a" to 1), harness.stopCounts())
        }

    @Test
    fun `reset cannot enter lifecycle between manager and lifecycle confirmation`() = runTest {
        val harness = SettlementHarness(
            scope = backgroundScope,
            starts = listOf(
                response(plan("session-a", 42)),
                response(plan("session-b", 84)),
                response(plan("session-c", 126)),
            ),
        )
        harness.startAndAdopt(fileId = 42, deferPublication = false)
        harness.startAndAdopt(fileId = 84, deferPublication = true)
        val managerConfirmed = CompletableDeferred<Unit>()
        val releaseLifecycleConfirmation = CompletableDeferred<Unit>()

        val settleB = async {
            harness.lifecycle.settlePendingPublicationIfCurrent(
                sessionId = "session-b",
                confirm = true,
                settleManager = {
                    val confirmed =
                        harness.manager.confirmVideoSessionPublication("session-b")
                    managerConfirmed.complete(Unit)
                    releaseLifecycleConfirmation.await()
                    confirmed
                },
            )
        }
        managerConfirmed.await()

        val readyC = harness.startManager(fileId = 126, deferPublication = true)
        val adoptC = async {
            harness.adopt(
                ready = readyC,
                fileId = 126,
                deferPublication = true,
            )
        }
        yield()

        assertFalse(adoptC.isCompleted)
        assertEquals("session-c", harness.manager.activeSessionIdForTest())
        assertEquals("session-b", harness.lifecycle.activeSessionId())

        releaseLifecycleConfirmation.complete(Unit)
        assertTrue(settleB.await())
        assertTrue(adoptC.await())

        assertEquals("session-c", harness.manager.activeSessionIdForTest())
        assertEquals("session-c", harness.lifecycle.activeSessionId())
        assertTrue(
            harness.lifecycle.settlePendingPublicationIfCurrent(
                sessionId = "session-c",
                confirm = false,
                settleManager = {
                    harness.manager.rollbackUnpublishedVideoSession("session-c")
                },
            ),
        )
        assertEquals("session-b", harness.manager.activeSessionIdForTest())
        assertEquals("session-b", harness.lifecycle.activeSessionId())
    }

    @Test
    fun `concurrent exit waits for rollback settlement then stops restored owner`() = runTest {
        val harness = SettlementHarness(
            scope = backgroundScope,
            starts = listOf(
                response(plan("session-a", 42)),
                response(plan("session-b", 84)),
            ),
        )
        harness.startAndAdopt(fileId = 42, deferPublication = false)
        harness.startAndAdopt(fileId = 84, deferPublication = true)
        val settlementEntered = CompletableDeferred<Unit>()
        val releaseSettlement = CompletableDeferred<Unit>()

        val settlement = async {
            harness.lifecycle.settlePendingPublicationIfCurrent(
                sessionId = "session-b",
                confirm = false,
                settleManager = {
                    settlementEntered.complete(Unit)
                    releaseSettlement.await()
                    harness.manager.rollbackUnpublishedVideoSession("session-b")
                },
            )
        }
        settlementEntered.await()
        val exit = async { harness.lifecycle.stop() }
        yield()

        assertFalse(exit.isCompleted)

        releaseSettlement.complete(Unit)
        assertTrue(settlement.await())
        exit.await()

        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(null, harness.lifecycle.activeSessionId())
        assertEquals(
            mapOf("session-a" to 1, "session-b" to 1),
            harness.stopCounts(),
        )
    }

    private class SettlementHarness(
        scope: kotlinx.coroutines.CoroutineScope,
        private val starts: List<PlaybackDecisionResponseV3>,
        private val replanResponse: PlaybackDecisionResponseV3? = null,
        private val stopBehavior: suspend (sessionId: String, attempt: Int) -> HttpStatusCode =
            { _, _ -> HttpStatusCode.OK },
        private val stopThrowableBehavior: (sessionId: String, attempt: Int) -> Throwable? =
            { _, _ -> null },
    ) {
        private val startIndex = AtomicInteger()
        private val stoppedEvents = Channel<String>(Channel.UNLIMITED)
        private val stoppedSessions: MutableList<String> =
            Collections.synchronizedList(mutableListOf())
        private val stopAttemptCounts: MutableMap<String, Int> =
            Collections.synchronizedMap(mutableMapOf())
        var replanCalls: Int = 0
            private set
        private val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                var responseStatus = HttpStatusCode.OK
                val body = when {
                    path == "/api/v1/playback/start" ->
                        SiloJson.encodeToString(starts[startIndex.getAndIncrement()])
                    path.endsWith("/replan") -> {
                        replanCalls += 1
                        SiloJson.encodeToString(requireNotNull(replanResponse))
                    }
                    request.method == HttpMethod.Delete &&
                        path.startsWith("/api/v1/playback/") -> {
                        val sessionId = path.substringAfterLast('/')
                        val attempt = synchronized(stopAttemptCounts) {
                            val next = (stopAttemptCounts[sessionId] ?: 0) + 1
                            stopAttemptCounts[sessionId] = next
                            next
                        }
                        stoppedSessions += sessionId
                        stoppedEvents.send(sessionId)
                        stopThrowableBehavior(sessionId, attempt)?.let { throw it }
                        responseStatus = stopBehavior(sessionId, attempt)
                        "{}"
                    }
                    else -> "{}"
                }
                respond(
                    content = body,
                    status = responseStatus,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val manager = PlaybackSessionManager(
            playbackRepository = PlaybackRepository(PlaybackApi(client)),
            tokenManager = SettlementTokenManager,
        )
        val lifecycle = PlaybackSessionLifecycle(
            sessionManager = manager,
            healthApi = SettlementHealthApi(),
            personalDataRepository = SettlementPersonalDataRepository(),
            scope = scope,
        )

        suspend fun startAndAdopt(
            fileId: Int,
            deferPublication: Boolean,
        ): VideoSessionStartV3.Ready {
            val ready = startManager(fileId, deferPublication)
            assertTrue(adopt(ready, fileId, deferPublication))
            return ready
        }

        suspend fun startManager(
            fileId: Int,
            deferPublication: Boolean,
        ): VideoSessionStartV3.Ready = assertIs<VideoSessionStartV3.Ready>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(
                manager.startVideoSessionV3(
                    fileId = fileId,
                    profileId = "profile-1",
                    capabilities = capabilities,
                    clientPlaybackContext = playbackContext,
                    audioTrackIndex = 0,
                    subtitleTrackIndex = null,
                    qualityPreference = "original",
                    startPosition = 0.0,
                    subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                    deferPublication = deferPublication,
                ),
            ).data,
        )

        suspend fun adopt(
            ready: VideoSessionStartV3.Ready,
            fileId: Int,
            deferPublication: Boolean,
        ): Boolean = lifecycle.adoptActiveSessionIfCurrent(
            params = StartParams(
                contentId = "content-$fileId",
                fileId = fileId,
                capabilities = capabilities,
                audioTrackIndex = 0,
                subtitleTrackIndex = null,
                qualityPreference = "original",
                startPosition = 0.0,
                clientPlaybackContext = playbackContext,
            ),
            session = ready.session,
            manageProgress = false,
            deferPublication = deferPublication,
            isCurrent = { true },
        )

        suspend fun awaitStopped(sessionId: String) {
            if (sessionId in stoppedSessions) return
            while (stoppedEvents.receive() != sessionId) {
                // Drain unrelated manager-owned cleanup completions.
            }
        }

        fun stopCounts(): Map<String, Int> =
            stoppedSessions.groupingBy { it }.eachCount()

        fun stopAttempts(sessionId: String): Int =
            stopAttemptCounts[sessionId] ?: 0

        private companion object {
            val capabilities = ClientCodecCapabilities(
                codecsVideo = listOf("hevc"),
                codecsAudio = listOf("eac3"),
                containers = listOf("mkv"),
            )
            val playbackContext = ClientPlaybackContext(
                formFactor = "tv",
                appVersion = "test",
                output = PlaybackOutputContext(outputContextId = "7"),
            )
        }
    }

    private companion object {
        fun response(
            plan: PlaybackPlanV3,
            features: List<String> = listOf(
                PLAYBACK_PLAN_V3_FEATURE,
                NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE,
            ),
        ): PlaybackDecisionResponseV3 =
            PlaybackDecisionResponseV3(
                protocolVersion = 3,
                serverFeatures = features,
                outcome = PlaybackDecisionOutcome.PLAYABLE,
                sessionId = plan.sessionId,
                playbackPlan = plan,
            )

        fun plan(sessionId: String, fileId: Int): PlaybackPlanV3 = PlaybackPlanV3(
            planId = "plan-$sessionId",
            planAttemptKey = "v3:test:$sessionId",
            sessionId = sessionId,
            delivery = PlaybackDelivery.SERVER_REMUX_HLS,
            stream = PlaybackStreamV3(
                url = "/stream/$sessionId/master.m3u8",
                protocol = PlaybackStreamProtocol.HLS,
                container = "mpegts",
                mimeType = "application/x-mpegURL",
            ),
            selectedTracks = SelectedPlaybackTracksV3(
                audio = PlaybackTrackIdentityV3("file:$fileId:audio:0", 0),
            ),
            effectiveRecipe = PlaybackEffectiveRecipeV3(
                videoCodec = "hevc",
                audioCodec = "eac3",
            ),
            decisionReason = "test",
            requestedMediaFileId = fileId,
            effectiveMediaFileId = fileId,
        )
    }
}

private fun PlaybackSessionLifecycle.activeSessionId(): String? =
    (state.value as? SessionState.Active)?.session?.sessionId

private class SettlementHealthApi : HealthApi(HttpClient()) {
    override suspend fun checkHealth(): ApiResult<HealthStatus> =
        ApiResult.Success(HealthStatus(status = "ok"))
}

private class SettlementPersonalDataRepository : PersonalDataRepository(
    personalDataApi = PersonalDataApi(HttpClient()),
) {
    override suspend fun syncProgress(items: List<SyncProgressItem>): ApiResult<Unit> =
        ApiResult.Success(Unit)
}

private object SettlementTokenManager : TokenManager {
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
