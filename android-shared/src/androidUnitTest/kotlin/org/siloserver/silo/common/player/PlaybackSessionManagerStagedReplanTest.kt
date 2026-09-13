package org.siloserver.silo.common.player

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
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE
import org.siloserver.silo.model.playback.PLAYBACK_PLAN_V3_FEATURE
import org.siloserver.silo.model.playback.PlaybackDecisionOutcome
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEffectiveRecipeV3
import org.siloserver.silo.model.playback.PlaybackEmbeddedSubtitleV3
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackSourceDescriptorV3
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
import org.siloserver.silo.model.playback.PlaybackStreamV3
import org.siloserver.silo.model.playback.PlaybackSubtitleArtifactV3
import org.siloserver.silo.model.playback.PlaybackSubtitleDecisionV3
import org.siloserver.silo.model.playback.PlaybackSubtitleInventoryItemV3
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PlaybackTerminalV3
import org.siloserver.silo.model.playback.PlaybackTrackIdentityV3
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.AuthScopeAttributeKey
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DurableLoginAuthority
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.PlaybackV2Api
import org.siloserver.silo.network.apiv2.SEQUENCED_PROGRESS_FEATURE
import org.siloserver.silo.repository.PlaybackJournalEntry
import org.siloserver.silo.repository.PlaybackJournalStore
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.SequencedPlayback

class PlaybackSessionManagerStagedReplanTest {
    @Test
    fun `deferred confirmation registers predecessor orphan before releasing reset waiter`() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackSessionManager.kt",
        ).readText()
        val confirmation = source
            .substringAfter("suspend fun confirmVideoSessionPublication(")
            .substringBefore("suspend fun rollbackUnpublishedVideoSession(")

        // Every insertion goes through the bounded helper now, so the ledger
        // cannot grow without limit when stops keep failing.
        val orphanRegistration = confirmation.indexOf("rememberOrphanedSessionLocked(")
        val waiterRelease = confirmation.indexOf("pending.settled.complete(Unit)")
        val registeredCleanup = confirmation.indexOf(
            "scheduleRegisteredCommittedSessionCleanup(",
        )

        assertTrue(orphanRegistration >= 0)
        assertTrue(orphanRegistration < waiterRelease)
        assertTrue(registeredCleanup > waiterRelease)
    }

    @Test
    fun `deferred confirm cleanup concurrent with orphan drain loses no ledger entry`() =
        runTest {
            val firstCleanupEntered = CompletableDeferred<Unit>()
            val releaseFirstCleanup = CompletableDeferred<Unit>()
            val oldAttempts = AtomicInteger()
            val harness = Harness(
                startResponses = listOf(response(basePlan()), response(basePlan(sessionId = "s2", fileId = 84))),
                replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
                stopBehavior = { sessionId ->
                    if (sessionId == "s1" && oldAttempts.incrementAndGet() == 1) {
                        firstCleanupEntered.complete(Unit)
                        releaseFirstCleanup.await()
                    }
                },
            )
            harness.start()
            val replacement = harness.startReady(fileId = 84, deferPublication = true)
            assertEquals("s2", replacement.session.sessionId)

            assertTrue(harness.manager.confirmVideoSessionPublication("s2"))
            firstCleanupEntered.await()
            assertEquals(setOf("s1"), harness.manager.orphanedSessionIdsForTest())

            // stopSession drains the same orphan ledger while confirmation's
            // asynchronous cleanup still owns its first network attempt.
            val stop = async(start = CoroutineStart.UNDISPATCHED) { harness.manager.stopSession("s2") }
            assertFalse(stop.isCompleted)
            assertEquals(listOf("s1"), harness.stopAttempts)
            releaseFirstCleanup.complete(Unit)
            stop.await()
            withWallClockAwaitTimeout {
                while (harness.manager.orphanedSessionIdsForTest().isNotEmpty()) {
                    yield()
                }
            }

            assertEquals(1, oldAttempts.get())
            assertEquals(emptySet(), harness.manager.orphanedSessionIdsForTest())
            assertTrue("s1" in harness.stoppedSessions)
            assertTrue("s2" in harness.stoppedSessions)
        }

    @Test
    fun `staging replacement does not swap attempt or stop old session`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        val original = harness.startReady()

        val staged = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        val candidate = assertIs<ApiResult.Success<StagedVideoReplan>>(staged).data
        assertEquals("s1", candidate.candidateSessionId)
        assertEquals("plan-s2", candidate.candidate.plan.planId)
        assertEquals(candidate.basePlaybackAttemptId, candidate.candidate.playbackAttemptId)
        assertEquals(original.playbackAttemptId, candidate.basePlaybackAttemptId)
        assertEquals(original.planAttemptId, candidate.basePlanAttemptId)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun stagedReplacementExposesTheOutputContextTheCandidateWasPlannedAgainst() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start()

        val staged = assertIs<ApiResult.Success<StagedVideoReplan>>(
            harness.manager.stageActiveVideoSessionReplan(
                classification = "output_route_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
                clientPlaybackContext = ClientPlaybackContext(
                    formFactor = "tv",
                    appVersion = "test",
                    output = PlaybackOutputContext(outputContextId = "11"),
                ),
            ),
        ).data

        assertEquals("11", staged.outputContextId)
        assertEquals(
            listOf(basePlan().planAttemptKey),
            harness.replanBodies.single()["attempted_plan_keys"]!!.jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun materialOutputRouteChangeRestartsFallbackHistory() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start()

        assertIs<ApiResult.Success<StagedVideoReplan>>(
            harness.manager.stageActiveVideoSessionReplan(
                classification = "output_route_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
                clientPlaybackContext = ClientPlaybackContext(
                    formFactor = "tv",
                    appVersion = "test",
                    output = PlaybackOutputContext(
                        outputContextId = "11",
                        sinkType = "hdmi",
                    ),
                ),
            ),
        )

        assertEquals(
            emptyList(),
            harness.replanBodies.single()["attempted_plan_keys"]!!.jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `commit replaces plan once without stopping the session`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start()
        val staged = assertIs<ApiResult.Success<StagedVideoReplan>>(
            harness.manager.stageActiveVideoSessionReplan(
                classification = "subtitle_track_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            ),
        ).data

        val committed = harness.manager.commitStagedVideoReplan(staged)

        assertEquals(
            "s1",
            assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(committed).data.session.sessionId,
        )
        assertEquals("plan-s2", assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(committed).data.plan.planId)
        assertEquals(staged.basePlaybackAttemptId, committed.data.playbackAttemptId)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)

        val consumed = harness.manager.commitStagedVideoReplan(staged)
        assertEquals(409, assertIs<ApiResult.Error>(consumed).code)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `native failure retry waits for publication rollback and preserves explicit subtitle index`() = runTest {
        val native = replanPlan("s2").let { plan ->
            plan.copy(
                delivery = PlaybackDelivery.ORIGINAL_HTTP,
                stream = PlaybackStreamV3(
                    url = "/api/v2/stream/s1",
                    protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE,
                    container = "mp4",
                    mimeType = "video/mp4",
                ),
                source = PlaybackSourceDescriptorV3(mediaFileId = 42, container = "mp4"),
                subtitle = plan.subtitle.copy(
                    mode = PlaybackSubtitleModeV3.RENDER,
                    artifact = null,
                    embedded = PlaybackEmbeddedSubtitleV3(streamIndex = 7, containerTrackId = "19"),
                    inventory = plan.subtitle.inventory.map { it.copy(codec = "mov_text") },
                ),
            )
        }
        val harness = Harness(
            replanResponse = { index, _ ->
                response(if (index == 0) native else replanPlan("s3"))
            },
        )
        harness.start()
        val stagedNative = harness.stageSidecar()
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(stagedNative, deferPublication = true),
        )

        // Enter the real publication barrier before checking the HTTP count.
        // A TV callback that retries before settling its native owner waits here.
        val retry = async(start = CoroutineStart.UNDISPATCHED) {
            harness.manager.replanActiveVideoSession(
                classification = "subtitle_embedded_failed",
                positionSeconds = 43.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            )
        }
        assertFalse(retry.isCompleted)
        assertEquals(listOf("s1"), harness.replanBaseSessions)
        assertTrue(harness.manager.rollbackUnpublishedVideoSession("s1"))

        val recovered = assertIs<ApiResult.Success<VideoSessionStartV3>>(retry.await()).data
        assertEquals("s1", assertIs<VideoSessionStartV3.Ready>(recovered).session.sessionId)
        assertEquals(listOf("s1", "s1"), harness.replanBaseSessions)
        val request = harness.replanBodies[1]
        assertEquals("subtitle_embedded_failed", request["failure"]!!.jsonObject["classification"]!!.jsonPrimitive.content)
        val subtitle = request["selected_tracks"]!!.jsonObject["subtitle"]!!.jsonObject
        assertEquals(4, subtitle["index"]!!.jsonPrimitive.int)
        assertEquals("file:42:subtitle:4", subtitle["id"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `deferred staged commit rollback restores base and unblocks replan from base`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                response(replanPlan(planId = if (index == 0) "s2" else "s3"))
            },
        )
        val renderedBase = harness.startReady()
        val replacement = harness.stageSidecar()

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(
                staged = replacement,
                deferPublication = true,
            ),
        )
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)

        val reverseMutation = async {
            harness.manager.stageActiveVideoSessionReplan(
                classification = "decoder_failure",
                positionSeconds = 43.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            )
        }
        yield()
        assertFalse(reverseMutation.isCompleted)
        assertEquals(listOf("s1"), harness.replanBaseSessions)

        harness.manager.rollbackUnpublishedVideoSession("s1")
        val stagedFromBase =
            assertIs<ApiResult.Success<StagedVideoReplan>>(reverseMutation.await()).data

        assertEquals("s1", stagedFromBase.candidateSessionId)
        assertEquals(listOf("s1", "s1"), harness.replanBaseSessions)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyMap(), harness.stoppedSessions.groupingBy { it }.eachCount())

        val serverCursor = replacement.candidate
        val secondRequest = harness.replanBodies[1]
        assertEquals(serverCursor.plan.planId, secondRequest["failed_plan_id"]!!.jsonPrimitive.content)
        assertEquals(serverCursor.planAttemptId, secondRequest["plan_attempt_id"]!!.jsonPrimitive.content)
        assertEquals(serverCursor.planAttemptKey, secondRequest["plan_attempt_key"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(serverCursor.planAttemptKey),
            secondRequest["attempted_plan_keys"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(1, secondRequest["attempt_count"]!!.jsonPrimitive.int)

        val failureEvent = harness.awaitRouteEvent("plan_failed", renderedBase.plan.planId)
        assertEquals(renderedBase.planAttemptId, failureEvent["plan_attempt_id"]!!.jsonPrimitive.content)
        assertEquals(renderedBase.planAttemptKey, failureEvent["plan_attempt_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deferred staged confirmation retains replacement without stopping the session`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start()
        val replacement = harness.stageSidecar()

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(
                staged = replacement,
                deferPublication = true,
            ),
        )
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)

        harness.manager.confirmVideoSessionPublication("s1")
        harness.manager.confirmVideoSessionPublication("s1")

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyMap(), harness.stoppedSessions.groupingBy { it }.eachCount())
    }

    @Test
    fun `committed predecessor cleanup can be owned and awaited by the caller scope`() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val harness = Harness(
            startResponses = listOf(response(basePlan()), response(basePlan(sessionId = "s2", fileId = 84))),
            committedSessionCleanupScope = backgroundScope,
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
            stopBehavior = {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
            },
        )
        harness.start()
        val replacement = harness.startReady(fileId = 84, deferPublication = true)
        assertEquals("s2", replacement.session.sessionId)

        assertTrue(harness.manager.confirmVideoSessionPublication("s2"))
        assertEquals(emptyList(), harness.stoppedSessions)

        cleanupEntered.await()

        assertEquals(emptyList(), harness.stoppedSessions)
        releaseCleanup.complete(Unit)
        harness.awaitStopped("s1")

        assertEquals(listOf("s1"), harness.stoppedSessions)
        assertEquals("s2", harness.manager.activeSessionIdForTest())
    }

    @Test
    fun `fresh start confirmation returns without waiting for cancellable predecessor cleanup`() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val harness = Harness(
            startResponses = listOf(response(basePlan()), response(basePlan(sessionId = "s2", fileId = 84))),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
            stopBehavior = {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
            },
        )
        harness.start()
        val replacement = harness.startReady(fileId = 84, deferPublication = true)

        val commit = async { harness.manager.confirmVideoSessionPublication("s2") }
        cleanupEntered.await()

        assertTrue(
            commit.isCompleted,
            "Once the active attempt swaps, old-session cleanup must not keep commit cancellable.",
        )
        assertTrue(commit.await())
        assertEquals("s2", replacement.session.sessionId)
        assertEquals("s2", harness.manager.activeSessionIdForTest())

        releaseCleanup.cancel(CancellationException("cleanup cancelled"))
    }

    @Test
    fun `throwing predecessor cleanup cannot escape fresh start confirmation`() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val harness = Harness(
            startResponses = listOf(response(basePlan()), response(basePlan(sessionId = "s2", fileId = 84))),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
            stopBehavior = {
                cleanupEntered.complete(Unit)
                throw AssertionError("old-session cleanup exploded")
            },
        )
        harness.start()
        val replacement = harness.startReady(fileId = 84, deferPublication = true)

        val committed = harness.manager.confirmVideoSessionPublication("s2")

        assertTrue(committed)
        assertEquals("s2", replacement.session.sessionId)
        assertEquals("s2", harness.manager.activeSessionIdForTest())
        cleanupEntered.await()
    }

    @Test
    fun `failed bounded cleanup remains orphaned until later stop drains it`() = runTest {
        val oldAttempts = AtomicInteger()
        val harness = Harness(
            committedSessionCleanupScope = backgroundScope,
            startResponses = listOf(response(basePlan()), response(basePlan(sessionId = "s2", fileId = 84))),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
            stopBehavior = { sessionId ->
                if (sessionId == "s1" && oldAttempts.incrementAndGet() <= 6) {
                    throw IllegalStateException("transient delete failure")
                }
            },
        )
        harness.start()
        harness.start(fileId = 84, deferPublication = true)

        assertTrue(harness.manager.confirmVideoSessionPublication("s2"))
        backgroundScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(6, oldAttempts.get())
        assertEquals(setOf("s1"), harness.manager.orphanedSessionIdsForTest())
        assertEquals(emptyList(), harness.stoppedSessions)

        harness.manager.stopSession("s2")

        assertEquals(7, oldAttempts.get())
        assertEquals(1, harness.stopBodies.filterIndexed { index, _ -> harness.stopAttempts[index] == "s1" }.distinct().size)
        assertTrue("s1" in harness.stoppedSessions)
        assertTrue("s2" in harness.stoppedSessions)
    }

    @Test
    fun `cancelled cleanup is eventually drained by content reset`() = runTest {
        val oldAttempts = AtomicInteger()
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s2", fileId = 63)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
            stopBehavior = { sessionId ->
                if (sessionId == "s1" && oldAttempts.incrementAndGet() == 1) {
                    throw CancellationException("cleanup cancelled")
                }
            },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 63, deferPublication = true)
        assertTrue(harness.manager.confirmVideoSessionPublication("s2"))
        harness.awaitStopAttempts("s1", count = 1)

        harness.start(fileId = 84)

        assertTrue(oldAttempts.get() >= 2)
        assertTrue("s1" in harness.stoppedSessions)
        assertEquals("s3", harness.manager.activeSessionIdForTest())
    }

    @Test
    fun `candidate stop exception cannot skip requested stop and is retained for drain`() = runTest {
        val candidateAttempts = AtomicInteger()
        val harness = Harness(
            startResponses = listOf(response(basePlan()), response(basePlan(sessionId = "s2", fileId = 84))),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
            stopBehavior = { sessionId ->
                if (sessionId == "s2" && candidateAttempts.incrementAndGet() <= 3) {
                    throw IllegalStateException("candidate stop failed")
                }
            },
        )
        harness.start()
        harness.start(fileId = 84, deferPublication = true)

        assertIs<ApiResult.Success<Unit>>(harness.manager.stopSession("s1"))

        assertTrue("s1" in harness.stoppedSessions)
        assertTrue("s2" in harness.stoppedSessions)
        assertEquals(4, candidateAttempts.get())
        assertEquals(listOf("s2", "s2", "s2", "s1", "s2"), harness.stopAttempts)
        assertEquals(1, harness.stopBodies.filterIndexed { index, _ -> harness.stopAttempts[index] == "s2" }.distinct().size)
    }

    @Test
    fun `requested stop failure still drains prior orphan`() = runTest {
        val oldAttempts = AtomicInteger()
        val requestedAttempts = AtomicInteger()
        val harness = Harness(
            committedSessionCleanupScope = backgroundScope,
            startResponses = listOf(response(basePlan()), response(basePlan(sessionId = "s2", fileId = 84))),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
            stopBehavior = { sessionId ->
                when {
                    sessionId == "s1" && oldAttempts.incrementAndGet() <= 6 ->
                        throw IllegalStateException("old cleanup failed")
                    sessionId == "s2" && requestedAttempts.incrementAndGet() == 1 ->
                        throw CancellationException("requested stop cancelled locally")
                }
            },
        )
        harness.start()
        harness.start(fileId = 84, deferPublication = true)
        assertTrue(harness.manager.confirmVideoSessionPublication("s2"))
        backgroundScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(6, oldAttempts.get())
        assertEquals(setOf("s1"), harness.manager.orphanedSessionIdsForTest())
        assertEquals(emptyList(), harness.stoppedSessions)

        assertFailsWith<CancellationException> { harness.manager.stopSession("s2") }

        assertEquals(7, oldAttempts.get())
        assertEquals(1, harness.stopBodies.filterIndexed { index, _ -> harness.stopAttempts[index] == "s1" }.distinct().size)
        assertTrue("s1" in harness.stoppedSessions)
        assertTrue("s2" in harness.stoppedSessions)
    }

    @Test
    fun `caller cancellation waits for contained cleanup then rethrows cancellation`() = runTest {
        val candidateEntered = CompletableDeferred<Unit>()
        val releaseCandidate = CompletableDeferred<Unit>()
        val harness = Harness(
            startResponses = listOf(response(basePlan()), response(basePlan(sessionId = "s2", fileId = 84))),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
            stopBehavior = { sessionId ->
                if (sessionId == "s2") {
                    candidateEntered.complete(Unit)
                    releaseCandidate.await()
                }
            },
        )
        harness.start()
        harness.start(fileId = 84, deferPublication = true)

        val stopJob = launch {
            harness.manager.stopSession("s1")
        }
        candidateEntered.await()
        stopJob.cancel(CancellationException("caller stopped"))
        releaseCandidate.complete(Unit)
        stopJob.join()

        assertTrue(stopJob.isCancelled)
        assertTrue("s1" in harness.stoppedSessions)
        assertTrue("s2" in harness.stoppedSessions)
    }

    @Test
    fun `discard consumes handle without stopping the shared session`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start()
        val staged = assertIs<ApiResult.Success<StagedVideoReplan>>(
            harness.manager.stageActiveVideoSessionReplan(
                classification = "subtitle_track_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            ),
        ).data

        harness.manager.discardStagedVideoReplan(staged)
        harness.manager.discardStagedVideoReplan(staged)

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
        assertEquals(
            409,
            assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(staged)).code,
        )
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `suspended replacement rollback releases publication before serialized cleanup`() = runTest {
        val firstStopStarted = CompletableDeferred<Unit>()
        val releaseFirstStop = CompletableDeferred<Unit>()
        val harness = Harness(
            startResponses = listOf(response(basePlan()), response(basePlan("s2", 84)), response(basePlan("s3", 126))),
            replanResponse = { _, _ -> error("No replan expected") },
            stopBehavior = { sessionId ->
                if (sessionId == "s2") {
                    firstStopStarted.complete(Unit)
                    releaseFirstStop.await()
                }
            },
        )
        harness.start()
        harness.start(84, deferPublication = true)
        val rollback = async { harness.manager.rollbackUnpublishedVideoSession("s2") }
        firstStopStarted.await()
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertFalse(harness.manager.confirmVideoSessionPublication("s2"))
        val next = async(start = CoroutineStart.UNDISPATCHED) { harness.start(126, deferPublication = true) }
        assertFalse(next.isCompleted)
        assertEquals(listOf("s2"), harness.stopAttempts)
        releaseFirstStop.complete(Unit)
        assertTrue(rollback.await())
        next.await()
        assertEquals("s3", harness.manager.activeSessionIdForTest())
        assertEquals(listOf("s2"), harness.stoppedSessions)
        harness.manager.rollbackUnpublishedVideoSession("s3")
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(listOf("s2", "s3"), harness.stoppedSessions)
    }

    @Test
    fun `stale handle cannot replace newer committed candidate`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                response(replanPlan(planId = if (index == 0) "s2" else "s3"))
            },
        )
        harness.start()
        val first = harness.stageSidecar()
        val second = harness.stageSidecar()

        val committed = assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(second),
        ).data
        assertEquals(second.candidate.plan.planId, committed.plan.planId)
        assertEquals(second.candidate.planAttemptId, committed.planAttemptId)
        val stale = harness.manager.commitStagedVideoReplan(first)

        assertEquals(409, assertIs<ApiResult.Error>(stale).code)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(
            emptyMap(), harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `content reset invalidates staged handle`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start(fileId = 42)
        val staged = harness.stageSidecar()

        harness.start(fileId = 84)
        val stale = harness.manager.commitStagedVideoReplan(staged)

        assertEquals(409, assertIs<ApiResult.Error>(stale).code)
        assertEquals("s3", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `burn in candidate commits without sidecar artifact`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ ->
                response(
                    replanPlan(planId = "s2").copy(
                        subtitle = PlaybackSubtitleDecisionV3(
                            mode = PlaybackSubtitleModeV3.BURN_IN,
                            trackId = subtitleTrackId(fileId = 42, index = 4),
                            inventory = subtitleInventory(
                                fileId = 42,
                                sessionId = "s1",
                                maxIndex = 4,
                                burnInIndex = 4,
                            ),
                        ),
                    ),
                )
            },
        )
        harness.start()

        val staged = harness.stageSidecar()
        val committed = harness.manager.commitStagedVideoReplan(staged)

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(committed)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `sidecar candidate without artifact is rejected`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ ->
                response(
                    replanPlan(planId = "s2").copy(
                        subtitle = PlaybackSubtitleDecisionV3(
                            mode = PlaybackSubtitleModeV3.CONVERT,
                            trackId = subtitleTrackId(fileId = 42, index = 4),
                            artifact = null,
                            inventory = subtitleInventory(
                                fileId = 42,
                                sessionId = "s1",
                                maxIndex = 4,
                            ),
                        ),
                    ),
                )
            },
        )
        harness.start()

        val staged = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        assertIs<ApiResult.Error>(staged)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `sidecar candidate for another server index is rejected`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ ->
                response(
                    replanPlan(planId = "s2").copy(
                        selectedTracks = SelectedPlaybackTracksV3(
                            audio = audioTrack(fileId = 42),
                            subtitle = PlaybackTrackIdentityV3(
                                id = subtitleTrackId(fileId = 42, index = 5),
                                index = 5,
                            ),
                        ),
                        subtitle = PlaybackSubtitleDecisionV3(
                            mode = PlaybackSubtitleModeV3.RENDER,
                            trackId = subtitleTrackId(fileId = 42, index = 5),
                            artifact = sidecarArtifact(sessionId = "s1", index = 5),
                            inventory = subtitleInventory(
                                fileId = 42,
                                sessionId = "s1",
                                maxIndex = 5,
                            ),
                        ),
                    ),
                )
            },
        )
        harness.start()

        val staged = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        assertIs<ApiResult.Error>(staged)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `sidecar identity may be remapped when server adapts to another edition`() = runTest {
        val remapped = replanPlan(planId = "s2").copy(
            requestedMediaFileId = 42,
            effectiveMediaFileId = 84,
            selectedTracks = SelectedPlaybackTracksV3(
                audio = audioTrack(fileId = 84),
                subtitle = PlaybackTrackIdentityV3(
                    id = subtitleTrackId(fileId = 84, index = 1),
                    index = 1,
                ),
            ),
            subtitle = PlaybackSubtitleDecisionV3(
                mode = PlaybackSubtitleModeV3.CONVERT,
                trackId = subtitleTrackId(fileId = 84, index = 1),
                artifact = sidecarArtifact(sessionId = "s1", index = 1),
                inventory = subtitleInventory(
                    fileId = 84,
                    sessionId = "s1",
                    maxIndex = 1,
                ),
            ),
        )
        val harness = Harness(replanResponse = { _, _ -> response(remapped) })
        harness.start()

        val staged = harness.manager.stageActiveVideoSessionReplan(
            classification = "decoder_failure",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        val candidate = assertIs<ApiResult.Success<StagedVideoReplan>>(staged).data.candidate
        assertEquals(84, candidate.plan.effectiveMediaFileId)
        assertEquals("file:84:subtitle:1", candidate.plan.selectedTracks.subtitle?.id)
    }

    @Test
    fun `immediate replan wrapper stages and commits replacement`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start()

        val replanned = harness.manager.replanActiveVideoSession(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        assertEquals(
            "s1",
            assertIs<VideoSessionStartV3.Ready>(
                assertIs<ApiResult.Success<VideoSessionStartV3>>(replanned).data,
            ).session.sessionId,
        )
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `content start rejects stage registration until replacement is installed`() = runTest {
        val replacementEntered = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        val starts = listOf(
            response(basePlan(sessionId = "s1", fileId = 42)),
            response(basePlan(sessionId = "s3", fileId = 84)),
        )
        val harness = Harness(
            startResponses = starts,
            startResponseOverride = { index ->
                if (index == 1) {
                    replacementEntered.complete(Unit)
                    releaseReplacement.await()
                }
                starts[index]
            },
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start(fileId = 42)

        val replacement = async { harness.start(fileId = 84) }
        replacementEntered.await()
        val duringReset = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )
        releaseReplacement.complete(Unit)
        replacement.await()

        assertEquals("content_reset_in_progress", assertIs<ApiResult.Error>(duringReset).error)
        assertEquals(emptyList(), harness.replanBodies)
        assertEquals("s3", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `unpublished replacement rollback restores predecessor and stops replacement once`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)

        harness.manager.rollbackUnpublishedVideoSession("s3")
        harness.manager.rollbackUnpublishedVideoSession("s3")

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        harness.awaitStopped("s3")
        assertEquals(mapOf("s3" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
    }

    @Test
    fun `default terminal fresh start clears prior active attempt`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                terminalResponse(
                    sessionId = "s3",
                    reason = "adaptation_unavailable",
                    message = "No compatible route.",
                ),
            ),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start(fileId = 42)

        harness.start(fileId = 84)

        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s3" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
    }

    @Test
    fun `deferred terminal fresh start preserves prior active attempt`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                terminalResponse(
                    sessionId = "s3",
                    reason = "adaptation_unavailable",
                    message = "No compatible route.",
                ),
            ),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start(fileId = 42)

        harness.start(fileId = 84, deferPublication = true)

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s3" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
        assertFalse(harness.manager.rollbackUnpublishedVideoSession("s3"))
    }

    @Test
    fun deferredUnexecutableFreshStartTerminalReplanRestoresPriorActiveAttempt() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(
                    basePlan(sessionId = "s3", fileId = 84).copy(
                        runtimeCorrections = listOf("future_runtime_fix"),
                    ),
                ),
            ),
            replanResponse = { _, _ ->
                terminalResponse(
                    sessionId = "s3",
                    reason = "adaptation_unavailable",
                    message = "No compatible route.",
                )
            },
        )
        harness.start(fileId = 42)

        harness.start(fileId = 84, deferPublication = true)

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `confirming replacement retains it and stops predecessor once`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)

        harness.manager.confirmVideoSessionPublication("s3")
        harness.manager.confirmVideoSessionPublication("s3")

        assertEquals("s3", harness.manager.activeSessionIdForTest())
        harness.awaitStopped("s1")
        assertEquals(mapOf("s1" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
    }

    @Test
    fun `reverse mutation waits for unpublished replacement rollback then stages from predecessor`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)

        val reverseMutation = async {
            harness.manager.stageActiveVideoSessionReplan(
                classification = "output_route_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            )
        }
        yield()

        assertFalse(reverseMutation.isCompleted)
        assertEquals(emptyList(), harness.replanBaseSessions)

        harness.manager.rollbackUnpublishedVideoSession("s3")
        val staged = assertIs<ApiResult.Success<StagedVideoReplan>>(reverseMutation.await()).data

        assertEquals("s1", staged.candidateSessionId)
        assertEquals("plan-s2", staged.candidate.plan.planId)
        assertEquals(listOf("s1"), harness.replanBaseSessions)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
    }

    @Test
    fun `new content start waits for unresolved replacement settlement and preserves predecessor`() =
        runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
                response(basePlan(sessionId = "s4", fileId = 126)),
            ),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)
        val nextStart = async {
            harness.start(fileId = 126, deferPublication = true)
        }
        yield()

        assertFalse(nextStart.isCompleted)
        assertEquals("s3", harness.manager.activeSessionIdForTest())

        assertTrue(harness.manager.rollbackUnpublishedVideoSession("s3"))
        nextStart.await()

        harness.awaitStopped("s3")
        assertEquals("s4", harness.manager.activeSessionIdForTest())

        harness.manager.rollbackUnpublishedVideoSession("s4")
        harness.awaitStopped("s4")

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s3" to 1, "s4" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `a start still completes when a deferred publication is never settled`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan()),
                response(basePlan(sessionId = "s9", fileId = 43)),
            ),
            pendingPublicationSettleTimeoutMs =
                PlaybackSessionManager.PENDING_PUBLICATION_SETTLE_TIMEOUT_MS,
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start()
        val replacement = harness.stageSidecar()
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(
                staged = replacement,
                deferPublication = true,
            ),
        )

        // Deliberately leave the publication unresolved. Production must
        // eventually roll it back so a later content start cannot wedge.
        harness.start(fileId = 43)

        assertEquals("s9", harness.manager.activeSessionIdForTest())
        assertEquals(
            emptyMap(),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
            "the abandoned publication should be rolled back exactly once",
        )
        assertFalse(harness.manager.rollbackUnpublishedVideoSession("s1"))
        assertFalse(harness.manager.confirmVideoSessionPublication("s1"))
        assertTrue(harness.manager.rollbackCurrentPendingVideoPublication())
        assertEquals(
            emptyMap(),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
            "late settlement must not issue a second stop",
        )
    }

    @Test
    fun `stopping unpublished replacement is an idempotent rollback to predecessor`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)

        harness.manager.stopSession("s3")
        harness.manager.rollbackUnpublishedVideoSession("s3")

        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s3" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())
    }

    @Test
    fun `stopping predecessor clears unresolved replacement and stops both once`() = runTest {
        val harness = Harness(
            startResponses = listOf(
                response(basePlan(sessionId = "s1", fileId = 42)),
                response(basePlan(sessionId = "s3", fileId = 84)),
            ),
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start(fileId = 42)
        harness.start(fileId = 84, deferPublication = true)

        harness.manager.stopSession("s1")

        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertFalse(harness.manager.confirmVideoSessionPublication("s3"))
        assertFalse(harness.manager.rollbackUnpublishedVideoSession("s3"))
        assertEquals(
            mapOf("s1" to 1, "s3" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `stop invalidates every staged plan and stops their shared session once`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                response(replanPlan(planId = if (index == 0) "s2" else "s3"))
            },
        )
        harness.start()
        val first = harness.stageSidecar()
        val second = harness.stageSidecar()

        harness.manager.stopSession("s1")

        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertEquals(409, assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(first)).code)
        assertEquals(409, assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(second)).code)
        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `stopping replacement invalidates a stale older plan handle`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                response(replanPlan(planId = if (index == 0) "s2" else "s3"))
            },
        )
        harness.start()
        val stale = harness.stageSidecar()
        val replacement = harness.stageSidecar()
        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(replacement),
        )

        harness.manager.stopSession("s1")

        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertEquals(
            409,
            assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(stale)).code,
        )
        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `delayed stop for stale session leaves active staged transaction untouched`() = runTest {
        val harness = Harness(
            startResponses = listOf(response(basePlan()), response(basePlan("s2", 84))),
            replanResponse = { _, _ -> response(replanPlan("candidate", sessionId = "s2")) },
        )
        harness.start()
        harness.start(84, deferPublication = true)
        assertTrue(harness.manager.confirmVideoSessionPublication("s2"))
        harness.awaitStopped("s1")
        val stagedFromS2 = harness.stageSidecar()

        harness.manager.stopSession("s1")

        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s1" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())

        assertIs<ApiResult.Success<VideoSessionStartV3.Ready>>(
            harness.manager.commitStagedVideoReplan(stagedFromS2),
        )
        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `stale stop and staged discard preserve the current session`() = runTest {
        val harness = Harness(
            startResponses = listOf(response(basePlan()), response(basePlan("s2", 84))),
            replanResponse = { _, _ -> response(replanPlan("candidate", sessionId = "s2")) },
        )
        harness.start()
        harness.start(84, deferPublication = true)
        assertTrue(harness.manager.confirmVideoSessionPublication("s2"))
        harness.awaitStopped("s1")
        val stagedFromS2 = harness.stageSidecar()

        harness.manager.stopSession("s1")

        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(mapOf("s1" to 1), harness.stoppedSessions.groupingBy { it }.eachCount())

        harness.manager.discardStagedVideoReplan(stagedFromS2)

        assertEquals("s2", harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `concurrent immediate replans serialize through both stage and commit`() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val harness = Harness(
            replanResponse = { index, _ ->
                if (index == 0) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
                response(replanPlan(planId = if (index == 0) "s2" else "s3"))
            },
        )
        harness.start()

        val first = async {
            harness.manager.replanActiveVideoSession(
                classification = "subtitle_track_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            )
        }
        firstEntered.await()
        val second = async {
            harness.manager.replanActiveVideoSession(
                classification = "subtitle_track_changed",
                positionSeconds = 43.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            )
        }
        repeat(3) { yield() }
        releaseFirst.complete(Unit)

        assertIs<ApiResult.Success<VideoSessionStartV3>>(first.await())
        assertIs<ApiResult.Success<VideoSessionStartV3>>(second.await())
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(
            emptyMap(), harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `native subtitle recovery rejection keeps the active session usable for another retry`() = runTest {
        val rejectedResponses = listOf(
            terminalResponse("s1", "adaptation_unavailable", "No subtitle route."),
            response(replanPlan(planId = "s2")).copy(protocolVersion = 2),
            response(replanPlan(planId = "s2").copy(runtimeCorrections = listOf("future_runtime_fix"))),
        )
        for (rejectedResponse in rejectedResponses) {
            val harness = Harness(
                replanResponse = { index, _ ->
                    if (index == 0) rejectedResponse else response(replanPlan(planId = "s3"))
                },
            )
            harness.start()

            val rejected = harness.manager.replanActiveVideoSession(
                classification = "subtitle_embedded_failed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            )

            assertIs<ApiResult.Error>(rejected)
            assertEquals("s1", harness.manager.activeSessionIdForTest())
            assertEquals(emptyList(), harness.stoppedSessions)

            val retried = harness.manager.replanActiveVideoSession(
                classification = "subtitle_embedded_failed",
                positionSeconds = 43.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            )
            assertIs<VideoSessionStartV3.Ready>(assertIs<ApiResult.Success<VideoSessionStartV3>>(retried).data)
                assertEquals("s1", harness.manager.activeSessionIdForTest())
            assertEquals(emptyMap(), harness.stoppedSessions.groupingBy { it }.eachCount())
        }
    }

    @Test
    fun `immediate terminal response preserves typed outcome and teardown`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                if (index == 0) {
                    response(replanPlan(planId = "s2"))
                } else {
                    terminalResponse(
                        sessionId = "s1",
                        reason = "adaptation_unavailable",
                        message = "No compatible route.",
                    )
                }
            },
        )
        harness.start()
        val staged = harness.stageSidecar()

        val result = harness.manager.replanActiveVideoSession(
            classification = "player_failure",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = null,
        )

        val terminal = assertIs<VideoSessionStartV3.Terminal>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(result).data,
        )
        assertEquals("adaptation_unavailable", terminal.reason)
        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )

        harness.manager.stopSession("s1")

        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertEquals(
            409,
            assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(staged)).code,
        )
        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `staged terminal response rejects candidate but keeps active attempt`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ ->
                terminalResponse(
                    sessionId = "s1",
                    reason = "adaptation_unavailable",
                    message = "No compatible route.",
                )
            },
        )
        harness.start()

        val result = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        assertIs<ApiResult.Error>(result)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
    }

    @Test
    fun `immediate incompatible response preserves server upgrade outcome`() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                val response = response(replanPlan(planId = if (index == 0) "s2" else "s3"))
                if (index == 0) response else response.copy(protocolVersion = 2)
            },
        )
        harness.start()
        val staged = harness.stageSidecar()

        val result = harness.manager.replanActiveVideoSession(
            classification = "player_failure",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = null,
        )

        assertIs<VideoSessionStartV3.ServerUpgradeRequired>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(result).data,
        )
        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)

        harness.manager.stopSession("s1")

        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertEquals(
            409,
            assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(staged)).code,
        )
        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun immediateUnexecutableRouteResponsePreservesTerminalOutcomeAndCleanup() = runTest {
        val harness = Harness(
            replanResponse = { index, _ ->
                if (index == 0) {
                    response(replanPlan(planId = "s2"))
                } else {
                    response(
                        replanPlan(planId = "s3").copy(
                            runtimeCorrections = listOf("future_runtime_fix"),
                        ),
                    )
                }
            },
        )
        harness.start()
        val staged = harness.stageSidecar()

        val result = harness.manager.replanActiveVideoSession(
            classification = "player_failure",
            positionSeconds = 42.0,
            audioTrackIndex = 0,
            subtitleTrackIndex = 4,
        )

        val terminal = assertIs<VideoSessionStartV3.Terminal>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(result).data,
        )
        assertEquals(PlaybackSessionManager.UNEXECUTABLE_ROUTE_REASON, terminal.reason)
        assertEquals(null, harness.manager.activeSessionIdForTest())
        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )

        harness.manager.stopSession("s1")

        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
        assertEquals(
            409,
            assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(staged)).code,
        )
        assertEquals(
            mapOf("s1" to 1),
            harness.stoppedSessions.groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `shared session remains alive after every staged owner discards`() = runTest {
        val harness = Harness(
            replanResponse = { _, _ -> response(replanPlan(planId = "s2")) },
        )
        harness.start()
        val first = harness.stageSidecar()
        val second = harness.stageSidecar()

        harness.manager.discardStagedVideoReplan(first)
        assertEquals(emptyList(), harness.stoppedSessions)

        harness.manager.discardStagedVideoReplan(second)
        assertEquals(emptyList(), harness.stoppedSessions)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(409, assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(first)).code)
        assertEquals(409, assertIs<ApiResult.Error>(harness.manager.commitStagedVideoReplan(second)).code)
    }

    @Test
    fun `foreign replan session stays unowned and blocks another replan until stop`() = runTest {
        val harness = Harness(replanResponse = { _, _ -> response(replanPlan("foreign", sessionId = "s2")) })
        harness.start()
        val first = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed", positionSeconds = 42.0,
            audioTrackIndex = 0, subtitleTrackIndex = 4,
        )
        assertEquals("invalid_decision", assertIs<ApiResult.Error>(first).error)
        assertEquals("s1", harness.manager.activeSessionIdForTest())
        assertEquals(emptyList(), harness.stoppedSessions)
        val second = harness.manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed", positionSeconds = 43.0,
            audioTrackIndex = 0, subtitleTrackIndex = 4,
        )
        assertEquals("replan_pending", assertIs<ApiResult.Error>(second).error)
        assertEquals(1, harness.replanBodies.size)
        assertEquals(emptyList(), harness.stoppedSessions)
        harness.manager.stopSession("s1")
        harness.manager.stopSession("s1")
        assertEquals(listOf("s1"), harness.stoppedSessions)
        assertEquals(listOf("s1"), harness.stopAttempts)
        assertEquals(null, harness.manager.activeSessionIdForTest())
    }

    private class Harness(
        startResponses: List<PlaybackDecisionResponseV3> = listOf(response(basePlan())),
        private val startResponseOverride: (suspend (Int) -> PlaybackDecisionResponseV3)? = null,
        pendingPublicationSettleTimeoutMs: Long? = PlaybackSessionManager.NEVER_SELF_HEAL,
        committedSessionCleanupScope: CoroutineScope? = null,
        private val replanResponse: suspend (Int, JsonObject) -> PlaybackDecisionResponseV3,
        private val stopBehavior: suspend (String) -> Unit = {},
    ) {
        val stoppedSessions: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val stopAttempts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val replanBodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        val replanBaseSessions: MutableList<String> =
            Collections.synchronizedList(mutableListOf())
        val routeEvents: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        private val stoppedEvents = Channel<String>(Channel.UNLIMITED)
        private val stopAttemptEvents = Channel<String>(Channel.UNLIMITED)
        private val routeEventSignals = Channel<Unit>(Channel.UNLIMITED)
        private val startIndex = AtomicInteger()
        private val replanIndex = AtomicInteger()
        private val identity = StagedReplanIdentity()
        private val journal = object : PlaybackJournalStore {
            var entries = emptyList<PlaybackJournalEntry>()
            override suspend fun read() = entries
            override suspend fun write(entries: List<PlaybackJournalEntry>) {
                this.entries = SiloJson.decodeFromString(SiloJson.encodeToString(entries))
            }
        }
        val stopBodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        private val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                assertEquals(identity.scope, request.attributes[AuthScopeAttributeKey])
                fun reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
                    body, status, headersOf(HttpHeaders.ContentType, "application/json"),
                )
                when {
                    path == "/api/v2/playback/capabilities" -> reply(
                        """{"installation_id":"11111111-1111-4111-8111-111111111111","revision":"1","state":"available","allowed":true,"protocol_versions":[3],"features":["sequenced_progress_v1"],"deliveries":["server_remux_hls"]}""",
                    )
                    path == "/api/v2/account/me" -> reply(
                        """{"id":"account-1","username":"test","email":"","role":"user"}""",
                    )
                    path == "/api/v2/playback/start" -> {
                        val index = startIndex.getAndIncrement()
                        reply(wireDecision(startResponseOverride?.invoke(index) ?: startResponses[index]), HttpStatusCode.Created)
                    }
                    path.endsWith("/replan") && path.startsWith("/api/v2/playback/") -> {
                        replanBaseSessions += path.substringBeforeLast("/replan").substringAfterLast('/')
                        val body = SiloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        replanBodies += body
                        reply(wireDecision(replanResponse(replanIndex.getAndIncrement(), body)))
                    }
                    path == "/api/v2/playback/route-events" -> {
                        val body = SiloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        routeEvents += body
                        routeEventSignals.send(Unit)
                        reply("""{"event_id":${body["event_id"]},"outcome":"accepted"}""", HttpStatusCode.Accepted)
                    }
                    request.method == HttpMethod.Delete && path.startsWith("/api/v2/playback/") -> {
                        val sessionId = path.substringAfterLast('/')
                        val body = SiloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        stopBodies += body
                        assertEquals(body["stop_id"]!!.jsonPrimitive.content,
                            journal.entries.single { it.sessionId == sessionId }.stop?.stopId)
                        stopAttempts += sessionId
                        stopAttemptEvents.send(sessionId)
                        stopBehavior(sessionId)
                        stoppedSessions += sessionId
                        stoppedEvents.send(sessionId)
                        reply("""{"stop_id":${body["stop_id"]},"outcome":"stopped"}""")
                    }
                    else -> error("Unexpected request ${request.method.value} $path")
                }
            },
        ) { install(ContentNegotiation) { json(SiloJson) } }
        private val sequenced = SequencedPlayback(PlaybackV2Api(client, ApiV2Gate.Unrestricted), identity, identity, journal) {
            java.util.UUID.randomUUID().toString()
        }
        val manager = PlaybackSessionManager(
            playbackRepository = PlaybackRepository(sequenced),
            tokenManager = identity,
            // Unresolved publication tests settle explicitly instead of using virtual-time self healing.
            pendingPublicationSettleTimeoutMs = pendingPublicationSettleTimeoutMs,
            committedSessionCleanupScope = committedSessionCleanupScope,
        )

        suspend fun start(
            fileId: Int = 42,
            deferPublication: Boolean = false,
        ) {
            assertIs<ApiResult.Success<VideoSessionStartV3>>(
                startResult(fileId, deferPublication),
            )
        }

        suspend fun startReady(
            fileId: Int = 42,
            deferPublication: Boolean = false,
        ): VideoSessionStartV3.Ready = assertIs(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(
                startResult(fileId, deferPublication),
            ).data,
        )

        private suspend fun startResult(
            fileId: Int,
            deferPublication: Boolean,
        ): ApiResult<VideoSessionStartV3> =
            manager.startVideoSessionV3(
                    fileId = fileId,
                    profileId = "profile-1",
                    capabilities = ClientCodecCapabilities(
                        codecsVideo = listOf("hevc"),
                        codecsAudio = listOf("eac3"),
                        containers = listOf("mkv"),
                    ),
                    clientPlaybackContext = ClientPlaybackContext(
                        formFactor = "tv",
                        appVersion = "test",
                        output = PlaybackOutputContext(outputContextId = "7"),
                    ),
                    audioTrackIndex = 0,
                    subtitleTrackIndex = null,
                    qualityPreference = "original",
                    startPosition = 0.0,
                    subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                    deferPublication = deferPublication,
                )

        suspend fun stageSidecar(): StagedVideoReplan = assertIs<ApiResult.Success<StagedVideoReplan>>(
            manager.stageActiveVideoSessionReplan(
                classification = "subtitle_track_changed",
                positionSeconds = 42.0,
                audioTrackIndex = 0,
                subtitleTrackIndex = 4,
            ),
        ).data

        suspend fun awaitStopped(sessionId: String) {
            if (sessionId in stoppedSessions) return
            while (stoppedEvents.receive() != sessionId) {
                // Drain unrelated cleanup completions until this owner stops.
            }
        }

        suspend fun awaitStopAttempts(sessionId: String, count: Int) {
            while (stopAttempts.count { it == sessionId } < count) {
                stopAttemptEvents.receive()
            }
        }

        suspend fun awaitRouteEvent(event: String, planId: String): JsonObject =
            withWallClockAwaitTimeout {
                while (true) {
                    routeEvents.firstOrNull { body ->
                        body["event"]?.jsonPrimitive?.content == event &&
                            body["plan_id"]?.jsonPrimitive?.content == planId
                    }?.let { return@withWallClockAwaitTimeout it }
                    routeEventSignals.receive()
                }
                error("unreachable")
            }
    }

    private companion object {
        fun wireDecision(response: PlaybackDecisionResponseV3): String {
            fun wire(value: JsonElement, key: String = ""): JsonElement = when (value) {
                is JsonObject -> JsonObject(value.mapValues { (name, child) -> wire(child, name) })
                is JsonArray -> JsonArray(value.map { wire(it) })
                is JsonPrimitive -> if (key in setOf("requested_media_file_id", "effective_media_file_id", "media_file_id"))
                    JsonPrimitive(value.content) else value
            }
            return wire(SiloJson.parseToJsonElement(SiloJson.encodeToString(response))).toString()
        }

        fun replanPlan(planId: String, sessionId: String = "s1") = sidecarPlan(sessionId).copy(
            planId = "plan-$planId", planAttemptKey = "v3:test:$planId",
        )

        fun basePlan(
            sessionId: String = "s1",
            fileId: Int = 42,
        ): PlaybackPlanV3 = PlaybackPlanV3(
            planId = "plan-$sessionId",
            planAttemptKey = "v3:test:$sessionId",
            sessionId = sessionId,
            delivery = PlaybackDelivery.SERVER_REMUX_HLS,
            stream = PlaybackStreamV3(
                url = "/api/v2/stream/$sessionId/master.m3u8",
                protocol = PlaybackStreamProtocol.HLS,
                container = "mpegts",
                mimeType = "application/x-mpegURL",
            ),
            selectedTracks = SelectedPlaybackTracksV3(audio = audioTrack(fileId)),
            effectiveRecipe = PlaybackEffectiveRecipeV3(
                videoCodec = "hevc",
                audioCodec = "eac3",
            ),
            decisionReason = "test",
            requestedMediaFileId = fileId,
            effectiveMediaFileId = fileId,
        )

        fun sidecarPlan(sessionId: String): PlaybackPlanV3 = basePlan(sessionId).copy(
            selectedTracks = SelectedPlaybackTracksV3(
                audio = audioTrack(fileId = 42),
                subtitle = PlaybackTrackIdentityV3(
                    id = subtitleTrackId(fileId = 42, index = 4),
                    index = 4,
                ),
            ),
            subtitle = PlaybackSubtitleDecisionV3(
                mode = PlaybackSubtitleModeV3.CONVERT,
                trackId = subtitleTrackId(fileId = 42, index = 4),
                artifact = sidecarArtifact(sessionId = sessionId, index = 4),
                inventory = subtitleInventory(
                    fileId = 42,
                    sessionId = sessionId,
                    maxIndex = 4,
                ),
            ),
        )

        fun subtitleInventory(
            fileId: Int,
            sessionId: String,
            maxIndex: Int,
            burnInIndex: Int? = null,
        ): List<PlaybackSubtitleInventoryItemV3> = (0..maxIndex).map { index ->
            val burnIn = index == burnInIndex
            PlaybackSubtitleInventoryItemV3(
                trackId = subtitleTrackId(fileId, index),
                combinedIndex = index,
                source = "embedded",
                delivery = if (burnIn) "burn_in_only" else "sidecar",
                url = if (burnIn) null else "/api/v2/stream/$sessionId/subtitles/$index.vtt",
            )
        }

        fun sidecarArtifact(sessionId: String, index: Int): PlaybackSubtitleArtifactV3 =
            PlaybackSubtitleArtifactV3(
                url = "/api/v2/stream/$sessionId/subtitles/$index.vtt",
                mimeType = "text/vtt",
                format = "webvtt",
            )

        fun audioTrack(fileId: Int): PlaybackTrackIdentityV3 =
            PlaybackTrackIdentityV3("file:$fileId:audio:0", 0)

        fun subtitleTrackId(fileId: Int, index: Int): String =
            "file:$fileId:subtitle:$index"

        fun response(plan: PlaybackPlanV3): PlaybackDecisionResponseV3 =
            PlaybackDecisionResponseV3(
                protocolVersion = 3,
                serverFeatures = listOf(
                    PLAYBACK_PLAN_V3_FEATURE,
                    NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE,
                    SEQUENCED_PROGRESS_FEATURE,
                ),
                outcome = PlaybackDecisionOutcome.PLAYABLE,
                sessionId = plan.sessionId,
                playbackPlan = plan,
            )

        fun terminalResponse(
            sessionId: String,
            reason: String,
            message: String,
        ): PlaybackDecisionResponseV3 = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(
                PLAYBACK_PLAN_V3_FEATURE,
                NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE,
                SEQUENCED_PROGRESS_FEATURE,
            ),
            outcome = PlaybackDecisionOutcome.ADAPTATION_UNAVAILABLE,
            sessionId = sessionId,
            terminal = PlaybackTerminalV3(
                reason = reason,
                message = message,
                retryable = false,
            ),
        )
    }
}

private class StagedReplanIdentity : TokenManager by org.siloserver.silo.network.TokenManagerImpl(), DurableLoginAuthorityProvider {
    val scope = AuthScopeSnapshot("server-1", "profile-1", "https://example.invalid", "proof",
        identityGeneration = 1, isIdentityGenerationStamped = true, credentialEpoch = 1)
    override suspend fun snapshotCurrentScope() = scope
    override suspend fun snapshotDurableLoginAuthority() = DurableLoginAuthority("login-1", scope)
}

/**
 * Wall-clock backstop for the awaits above.
 *
 * These wait on signals and spins whose progress depends on getting scheduled,
 * while the deadline counts real seconds regardless — so on a loaded CI runner
 * a merely-slow test failed as if it had raced. The deadline exists to turn a
 * hang into a failure, not to police latency.
 */
private suspend fun <T> withWallClockAwaitTimeout(
    block: suspend CoroutineScope.() -> T,
): T = withContext(Dispatchers.IO) {
    withTimeout(AWAIT_POLL_TIMEOUT_MS, block)
}

private const val AWAIT_POLL_TIMEOUT_MS = 30_000L
