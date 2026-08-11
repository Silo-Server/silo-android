package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TvSubtitleSettlementOwnershipTest {
    @Test
    fun `new subtitle rolls back unpublished B before C stages and stale B ack is inert`() =
        runTest {
            val harness = harness(backgroundScope)
            harness.mountUnpublished(sidecarB)

            harness.adapter.select(sidecarC)
            runCurrent()

            harness.assertRollbackBeforeStage(
                nextClassification = "subtitle_track_changed",
                nextSubtitleIndex = 5,
            )
            val beforeStaleAck = harness.events.toList()
            harness.adapter.reportMountedSelection(
                identity = sidecarB,
                selected = true,
                snapshotKey = "stale-b",
                settled = true,
            )
            runCurrent()

            assertEquals(beforeStaleAck, harness.events)
            assertFalse(harness.events.any { it == "manager-confirm:session-1" })
            assertTrue(harness.persistence.isEmpty())
        }

    @Test
    fun `output route replan rolls back unpublished B before the new route stages`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)
        harness.adapter.updatePlaybackContext(
            context(
                sessionId = "session-1",
                outputRouteGeneration = 9,
            ),
        )

        harness.adapter.updateOutputRouteGeneration(9)
        runCurrent()

        harness.assertRollbackBeforeStage(
            nextClassification = "output_route_changed",
            nextSubtitleIndex = 4,
            outputRouteGeneration = 9,
        )
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `route updated while rollback is suspended survives settlement replay`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)
        harness.port.suspendNextRollback()

        harness.adapter.select(sidecarC)
        runCurrent()
        harness.port.awaitRollbackStarted()

        harness.adapter.updatePlaybackContext(
            context(
                sessionId = "session-1",
                outputRouteGeneration = 9,
            ),
        )
        harness.adapter.updateOutputRouteGeneration(9)
        harness.port.releaseRollback()
        runCurrent()

        harness.assertRollbackBeforeStage(
            nextClassification = "output_route_changed",
            nextSubtitleIndex = 5,
            outputRouteGeneration = 9,
        )
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `route updated while failed mount compensation is suspended survives restore`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)
        harness.port.suspendNextRollback()

        harness.adapter.reportMountedSelection(
            identity = sidecarB,
            selected = false,
            snapshotKey = "failed-b",
            settled = true,
        )
        runCurrent()
        harness.port.awaitRollbackStarted()

        harness.adapter.updatePlaybackContext(
            context(
                sessionId = "session-1",
                outputRouteGeneration = 9,
            ),
        )
        harness.port.releaseRollback()
        runCurrent()

        harness.assertRollbackBeforeStage(
            nextClassification = "output_route_changed",
            nextSubtitleIndex = 3,
            outputRouteGeneration = 9,
        )
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `newer replan during blocked load invalidation revokes obsolete discard`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)
        harness.port.suspendNextRollback()

        val invalidation = backgroundScope.async {
            harness.adapter.invalidateAndSettle()
        }
        runCurrent()
        harness.port.awaitRollbackStarted()

        harness.adapter.updatePlaybackContext(
            context(
                sessionId = "session-1",
                outputRouteGeneration = 9,
            ),
        )
        harness.adapter.select(sidecarC)
        harness.adapter.selectQuality("720p")
        harness.adapter.updateOutputRouteGeneration(9)
        harness.port.releaseRollback()
        runCurrent()

        assertTrue(invalidation.await())
        harness.assertRollbackBeforeStage(
            nextClassification = "output_route_changed",
            nextSubtitleIndex = 5,
            outputRouteGeneration = 9,
            qualityPreference = "720p",
        )
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `mutation during suspended confirm stages from confirmed B`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)
        harness.port.suspendNextConfirm()
        harness.port.suspendNextStage()

        harness.adapter.reportMountedSelection(
            identity = sidecarB,
            selected = true,
            snapshotKey = "mounted-b",
            settled = true,
        )
        runCurrent()
        harness.port.awaitConfirmStarted()

        harness.adapter.selectQuality("720p")
        harness.port.releaseConfirm()
        runCurrent()
        harness.port.awaitStageStarted()

        assertEquals(sidecarB, harness.adapter.snapshot.committedIdentity)
        assertEquals(sidecarB, harness.adapter.snapshot.pendingIdentity)
        harness.port.failNextStage = true
        harness.port.releaseStage()
        runCurrent()
        assertEquals(sidecarB, harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(sidecarB), harness.persistence.map { it.identity })
        assertEquals(listOf("session-1"), harness.persistedSessionIds)
    }

    @Test
    fun `ownerless invalidation clears latent discard before B confirms`() = runTest {
        val harness = harness(backgroundScope)

        assertTrue(harness.adapter.invalidateAndSettle())
        harness.mountUnpublished(sidecarB)
        harness.adapter.reportMountedSelection(
            identity = sidecarB,
            selected = true,
            snapshotKey = "mounted-b",
            settled = true,
        )
        runCurrent()

        assertEquals(sidecarB, harness.adapter.snapshot.committedIdentity)
        assertEquals(listOf(sidecarB), harness.persistence.map { it.identity })
    }

    @Test
    fun `reset during suspended burn in confirm cannot persist stale content`() = runTest {
        val harness = harness(backgroundScope)
        val burnIn = SubtitleIdentity.ServerBurnIn(8)
        harness.port.nextSubtitleMode = PlaybackSubtitleModeV3.BURN_IN
        harness.port.suspendNextConfirm()

        harness.adapter.select(burnIn)
        runCurrent()
        harness.port.awaitConfirmStarted()

        harness.adapter.resetContent(
            context = context(sessionId = "session-new"),
            committedIdentity = sidecarA,
        )
        harness.port.releaseConfirm()
        runCurrent()

        assertEquals(sidecarA, harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.persistence.none { it.identity == burnIn })
        harness.adapter.select(sidecarC)
        runCurrent()
        assertTrue(harness.port.requests.any { it.subtitleTrackIndex == 5 })
    }

    @Test
    fun `invalidation and new selection during burn in confirm cannot deadlock or stale persist`() =
        runTest {
            val harness = harness(backgroundScope)
            val burnIn = SubtitleIdentity.ServerBurnIn(8)
            harness.port.nextSubtitleMode = PlaybackSubtitleModeV3.BURN_IN
            harness.port.suspendNextConfirm()

            harness.adapter.select(burnIn)
            runCurrent()
            harness.port.awaitConfirmStarted()

            val invalidation = backgroundScope.async {
                harness.adapter.invalidateAndSettle()
            }
            runCurrent()
            harness.adapter.select(sidecarC)
            harness.port.releaseConfirm()
            runCurrent()

            assertTrue(invalidation.await())
            assertTrue(harness.persistence.any { it.identity == burnIn })
            assertTrue(harness.port.requests.any { it.subtitleTrackIndex == 5 })
        }

    @Test
    fun `burn in confirm failure drains reset only after authoritative rollback`() = runTest {
        val harness = harness(backgroundScope)
        val burnIn = SubtitleIdentity.ServerBurnIn(8)
        harness.port.nextSubtitleMode = PlaybackSubtitleModeV3.BURN_IN
        harness.port.failConfirm = true
        harness.port.suspendNextConfirm()
        harness.port.suspendNextRollback()

        harness.adapter.select(burnIn)
        runCurrent()
        harness.port.awaitConfirmStarted()
        harness.adapter.resetContent(
            context = context(sessionId = "session-new"),
            committedIdentity = sidecarA,
        )

        harness.port.releaseConfirm()
        runCurrent()
        harness.port.awaitRollbackStarted()
        harness.port.releaseRollback()
        runCurrent()

        assertEquals(sidecarA, harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.persistence.none { it.identity == burnIn })
        assertEquals(1, harness.events.count { it == "manager-rollback:session-1" })
    }

    @Test
    fun `session removal during suspended confirm resets to B and consumes flags`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)
        harness.port.suspendNextConfirm()

        harness.adapter.reportMountedSelection(
            identity = sidecarB,
            selected = true,
            snapshotKey = "mounted-b",
            settled = true,
        )
        runCurrent()
        harness.port.awaitConfirmStarted()
        harness.adapter.updatePlaybackContext(context(sessionId = null))
        harness.port.releaseConfirm()
        runCurrent()

        assertEquals(sidecarB, harness.adapter.snapshot.committedIdentity)
        val fresh = SubtitleIdentity.ServerSidecar(6)
        harness.adapter.select(fresh)
        runCurrent()
        assertEquals(fresh, harness.adapter.snapshot.committedIdentity)
        assertTrue(harness.persistence.any { it.identity == fresh })
    }

    @Test
    fun `failed mount compensation owns one rollback and preserves queued intent`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)
        harness.port.suspendNextRollback()

        harness.adapter.reportMountedSelection(
            identity = sidecarB,
            selected = false,
            snapshotKey = "failed-b",
            settled = true,
        )
        runCurrent()
        harness.port.awaitRollbackStarted()
        harness.adapter.selectQuality("720p")
        harness.port.releaseRollback()
        runCurrent()

        assertEquals(
            1,
            harness.events.count { it == "manager-rollback:session-1" },
        )
        assertTrue(
            harness.port.requests.any {
                it.subtitleTrackIndex == 4 && it.qualityPreference == "720p"
            },
        )
    }

    @Test
    fun `failed mount rollback failure retains B owner and never rewrites A`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)
        harness.port.failRollback = true

        harness.adapter.reportMountedSelection(
            identity = sidecarB,
            selected = false,
            snapshotKey = "failed-b",
            settled = true,
        )
        runCurrent()

        assertEquals(sidecarB, harness.adapter.snapshot.localMountIdentity)
        assertTrue(
            harness.adapter.snapshot.failureMessage
                ?.contains("roll", ignoreCase = true) == true,
        )
        assertEquals(1, harness.port.requests.size)
    }

    @Test
    fun `session replacement during suspended rollback feeds the replay and consumes flags`() =
        runTest {
            val harness = harness(backgroundScope)
            harness.mountUnpublished(sidecarB)
            harness.port.suspendNextRollback()

            harness.adapter.select(sidecarC)
            runCurrent()
            harness.port.awaitRollbackStarted()
            harness.adapter.updatePlaybackContext(context(sessionId = "session-s2"))
            harness.port.releaseRollback()
            runCurrent()

            assertFalse(harness.port.requests.any { it.subtitleTrackIndex == 5 })
            harness.adapter.select(SubtitleIdentity.ServerSidecar(6))
            runCurrent()
            assertTrue(
                harness.port.requests.any {
                    it.sessionId == "session-s2" && it.subtitleTrackIndex == 6
                },
            )
        }

    @Test
    fun `confirm failure completion waits for compensation and drains invalidation`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)
        harness.port.failConfirm = true
        harness.port.suspendNextConfirm()
        harness.port.suspendNextRollback()

        harness.adapter.reportMountedSelection(
            identity = sidecarB,
            selected = true,
            snapshotKey = "mounted-b",
            settled = true,
        )
        runCurrent()
        harness.port.awaitConfirmStarted()
        val invalidation = backgroundScope.async {
            harness.adapter.invalidateAndSettle()
        }
        runCurrent()

        harness.port.releaseConfirm()
        runCurrent()
        harness.port.awaitRollbackStarted()
        assertFalse(invalidation.isCompleted)
        harness.port.releaseRollback()
        runCurrent()

        assertTrue(invalidation.await())
        assertNull(harness.adapter.snapshot.localMountIdentity)
        assertEquals(1, harness.events.count { it == "manager-rollback:session-1" })
    }

    @Test
    fun `superseded adoption rollback failure retains B until a successful retry`() = runTest {
        val harness = harness(
            adapterScope = backgroundScope,
            forceSupersededAfterAdoption = true,
        )
        harness.port.failRollback = true

        harness.adapter.select(sidecarB)
        runCurrent()

        assertEquals(sidecarB, harness.adapter.snapshot.localMountIdentity)
        assertEquals(1, harness.port.requests.size)
        harness.adapter.select(sidecarC)
        runCurrent()
        assertTrue(harness.port.requests.any { it.subtitleTrackIndex == 5 })
    }

    @Test
    fun `failed adoption rollback failure retains B and blocks stale progression`() = runTest {
        val harness = harness(
            adapterScope = backgroundScope,
            adoptionFailure = IllegalStateException("adoption failed"),
        )
        harness.port.failRollback = true

        harness.adapter.select(sidecarB)
        runCurrent()

        assertEquals(sidecarB, harness.adapter.snapshot.localMountIdentity)
        assertEquals(1, harness.port.requests.size)
        assertTrue(
            harness.adapter.snapshot.failureMessage
                ?.contains("roll", ignoreCase = true) == true,
        )
        harness.adapter.reportMountedSelection(
            identity = sidecarB,
            selected = true,
            snapshotKey = "recovered-b",
            settled = true,
        )
        runCurrent()
        assertEquals(listOf(sidecarB), harness.persistence.map { it.identity })
    }

    @Test
    fun `reset during suspended commit retains B when manager rollback fails then retries`() =
        runTest {
            val harness = harness(backgroundScope)
            harness.port.suspendNextCommit()
            harness.port.failRollback = true

            harness.adapter.select(sidecarB)
            runCurrent()
            harness.port.awaitCommitStarted()
            harness.adapter.resetContent(
                context = context(sessionId = "session-reset"),
                committedIdentity = sidecarA,
            )
            harness.port.releaseCommit()
            runCurrent()

            assertEquals(sidecarB, harness.adapter.snapshot.pendingIdentity)
            assertNull(harness.adapter.snapshot.localMountIdentity)
            assertEquals(1, harness.port.requests.size)
            assertTrue(harness.adapter.invalidateAndSettle())
            val fresh = SubtitleIdentity.ServerSidecar(6)
            harness.adapter.select(fresh)
            runCurrent()
            assertTrue(
                harness.port.requests.any {
                    it.sessionId == "session-reset" && it.subtitleTrackIndex == 6
                },
            )
        }

    @Test
    fun `context null during suspended commit retries failed rollback before fresh intent`() =
        runTest {
            val harness = harness(backgroundScope)
            harness.port.suspendNextCommit()
            harness.port.failRollback = true

            harness.adapter.select(sidecarB)
            runCurrent()
            harness.port.awaitCommitStarted()
            harness.adapter.updatePlaybackContext(context(sessionId = null))
            harness.port.releaseCommit()
            runCurrent()

            assertEquals(sidecarB, harness.adapter.snapshot.pendingIdentity)
            assertEquals(1, harness.port.requests.size)
            assertTrue(harness.adapter.invalidateAndSettle())
            val fresh = SubtitleIdentity.ServerSidecar(6)
            harness.adapter.select(fresh)
            runCurrent()
            assertEquals(fresh, harness.adapter.snapshot.committedIdentity)
            assertTrue(harness.persistence.any { it.identity == fresh })
        }

    @Test
    fun `quality replan rolls back unpublished Off before the new quality stages`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(SubtitleIdentity.Off)

        harness.adapter.selectQuality("720p")
        runCurrent()

        harness.assertRollbackBeforeStage(
            nextClassification = "quality_changed",
            nextSubtitleIndex = -1,
            qualityPreference = "720p",
        )
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `subtitle queued during adoption rolls B back before replay can stage`() = runTest {
        val adoptionGate = CompletableDeferred<Unit>()
        val harness = harness(backgroundScope, adoptionGate = adoptionGate)

        harness.adapter.select(sidecarB)
        runCurrent()
        assertEquals("session-1", harness.lifecycle.currentSessionId)

        harness.adapter.select(sidecarC)
        adoptionGate.complete(Unit)
        runCurrent()

        harness.assertRollbackBeforeStage(
            nextClassification = "subtitle_track_changed",
            nextSubtitleIndex = 5,
        )
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `quality queued during adoption rolls B back before replay can stage`() = runTest {
        val adoptionGate = CompletableDeferred<Unit>()
        val harness = harness(backgroundScope, adoptionGate = adoptionGate)

        harness.adapter.select(sidecarB)
        runCurrent()
        harness.adapter.selectQuality("720p")
        adoptionGate.complete(Unit)
        runCurrent()

        harness.assertRollbackBeforeStage(
            nextClassification = "quality_changed",
            nextSubtitleIndex = 4,
            qualityPreference = "720p",
        )
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `subtitle then quality queued during adoption preserves explicit C and quality`() = runTest {
        verifyQueuedSubtitleAndQuality(
            mutate = { adapter ->
                adapter.select(sidecarC)
                adapter.selectQuality("720p")
            },
        )
    }

    @Test
    fun `quality then subtitle queued during adoption preserves explicit C and quality`() = runTest {
        verifyQueuedSubtitleAndQuality(
            mutate = { adapter ->
                adapter.selectQuality("720p")
                adapter.select(sidecarC)
            },
        )
    }

    @Test
    fun `route queued during adoption rolls B back before replay can stage`() = runTest {
        val adoptionGate = CompletableDeferred<Unit>()
        val harness = harness(backgroundScope, adoptionGate = adoptionGate)

        harness.adapter.select(sidecarB)
        runCurrent()
        harness.adapter.updatePlaybackContext(
            context(sessionId = "session-a", outputRouteGeneration = 9),
        )
        harness.adapter.updateOutputRouteGeneration(9)
        adoptionGate.complete(Unit)
        runCurrent()

        harness.assertRollbackBeforeStage(
            nextClassification = "output_route_changed",
            nextSubtitleIndex = 4,
            outputRouteGeneration = 9,
        )
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `content reset jointly rolls back unpublished B before clearing its owner`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)

        harness.adapter.resetContent(
            context = context(sessionId = "session-new-content"),
            committedIdentity = sidecarA,
        )
        runCurrent()

        assertEquals(
            listOf(
                "stage:subtitle_track_changed:4:0:auto",
                "commit:session-1",
                "lifecycle-adopt:session-1",
                "manager-rollback:session-1",
                "lifecycle-rollback:session-1",
            ),
            harness.events,
        )
        assertEquals("session-a", harness.lifecycle.currentSessionId)
        assertNull(harness.port.pendingPlayback)
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `new load invalidation settles B and lets the next content request proceed`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)

        harness.adapter.invalidate()
        runCurrent()
        harness.adapter.resetContent(
            context = context(sessionId = "session-a"),
            committedIdentity = sidecarA,
        )
        harness.adapter.select(sidecarC)
        runCurrent()

        harness.assertRollbackBeforeStage(
            nextClassification = "subtitle_track_changed",
            nextSubtitleIndex = 5,
        )
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `explicit exit restores A before lifecycle stop and never persists B`() = runTest {
        val harness = harness(backgroundScope)
        harness.mountUnpublished(sidecarB)

        harness.adapter.invalidate()
        runCurrent()
        harness.lifecycle.stopCurrent()

        assertEquals(
            listOf(
                "stage:subtitle_track_changed:4:0:auto",
                "commit:session-1",
                "lifecycle-adopt:session-1",
                "manager-rollback:session-1",
                "lifecycle-rollback:session-1",
                "lifecycle-stop:session-a",
            ),
            harness.events,
        )
        assertEquals(listOf("session-a"), harness.lifecycle.stoppedSessions)
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `clear contains settlement outside the cancelled adapter scope then stops A`() = runTest {
        val adapterJob = SupervisorJob()
        val adapterScope = CoroutineScope(StandardTestDispatcher(testScheduler) + adapterJob)
        val harness = harness(
            adapterScope = adapterScope,
            durableScope = backgroundScope,
        )
        harness.mountUnpublished(sidecarB)

        harness.adapter.invalidate()
        adapterScope.cancel()
        runCurrent()
        harness.lifecycle.stopCurrent()

        assertEquals(
            listOf(
                "stage:subtitle_track_changed:4:0:auto",
                "commit:session-1",
                "lifecycle-adopt:session-1",
                "manager-rollback:session-1",
                "lifecycle-rollback:session-1",
                "lifecycle-stop:session-a",
            ),
            harness.events,
        )
        assertEquals(listOf("session-a"), harness.lifecycle.stoppedSessions)
        assertTrue(harness.persistence.isEmpty())
    }

    @Test
    fun `parent cancellation during commit still settles and invokes clear callback once`() =
        runTest {
            val adapterJob = SupervisorJob()
            val adapterScope = CoroutineScope(StandardTestDispatcher(testScheduler) + adapterJob)
            val harness = harness(
                adapterScope = adapterScope,
                durableScope = backgroundScope,
            )
            harness.port.suspendNextCommit()
            var afterSettlementCalls = 0

            harness.adapter.select(sidecarB)
            runCurrent()
            harness.port.awaitCommitStarted()
            harness.adapter.invalidateAndSettleAsync(restoreUi = false) {
                afterSettlementCalls += 1
            }
            runCurrent()

            adapterScope.cancel()
            harness.port.releaseCommit()
            runCurrent()

            assertEquals(1, afterSettlementCalls)
            assertEquals(
                1,
                harness.events.count { it == "manager-rollback:session-1" },
            )
            assertNull(harness.port.pendingPlayback)
        }

    @Test
    fun `owner loss after lifecycle adoption jointly rolls manager and lifecycle back to A`() =
        runTest {
            val harness = harness(
                adapterScope = backgroundScope,
                forceSupersededAfterAdoption = true,
            )

            harness.adapter.select(sidecarB)
            runCurrent()

            assertEquals(
                listOf(
                    "stage:subtitle_track_changed:4:0:auto",
                    "commit:session-1",
                    "lifecycle-adopt:session-1",
                    "manager-rollback:session-1",
                    "lifecycle-rollback:session-1",
                ),
                harness.events,
            )
            assertEquals("session-a", harness.lifecycle.currentSessionId)
            assertNull(harness.port.pendingPlayback)
            assertTrue(harness.persistence.isEmpty())
        }

    @Test
    fun `real exit and clear wire settlement before invalidation and lifecycle stop`() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt",
        ).readText()
        val exitBody = source
            .substringAfter("suspend fun stopSessionForExit()")
            .substringBefore("fun onExit()")
        val clearBody = source
            .substringAfter("override fun onCleared()")
            .substringBefore("\n    }\n\n}")

        assertBefore(
            exitBody,
            "subtitleTransactions.invalidateAndAwaitSettlement()",
            "playbackMutationFence.invalidateAll()",
        )
        assertBefore(
            exitBody,
            "subtitleTransactions.invalidateAndAwaitSettlement()",
            "lifecycleTeardown.stopOrdered(",
        )
        // Every exit path runs prepareSessionExit, which blanks uiState.sessionId.
        // If the id is not latched BEFORE that write, each of the three stops
        // below passes null and the ownership guard they exist for never engages
        // — which is exactly how this fix shipped inert the first time.
        val prepareBody = source
            .substringAfter("private fun prepareSessionExit()")
            .substringBefore("\n    private ")
        assertBefore(
            prepareBody,
            "lastAdoptedSessionId = it",
            "sessionId = null",
        )
        assertTrue(exitBody.contains("lifecycleTeardown.stopOrdered(expectedSessionId = exitSessionId)"))
        assertBefore(
            clearBody,
            "subtitleTransactions.reserveDurableFinalPersistence()",
            "subtitleTransactions.invalidateAndSettleAsync",
        )
        assertBefore(
            clearBody,
            "subtitleTransactions.invalidateAndSettleAsync",
            "playbackMutationFence.invalidateAll()",
        )
        assertBefore(
            clearBody,
            "subtitleTransactions::requestDurableFinalPersistence",
            "playbackMutationFence.invalidateAll()",
        )
        assertBefore(
            clearBody,
            "subtitleTransactions::requestDurableFinalPersistence",
            "lifecycleTeardown.stopDetached(",
        )
        // Teardown is deferred behind settlement work, so it must name the
        // session it is ending or it lands on whatever the next screen has since
        // adopted. It also has to go through the gate: an unguarded stop here
        // bumps stopEpoch after the next episode captured its ownership epoch
        // and supersedes it, which is how auto-advance broke.
        assertTrue(clearBody.contains("lifecycleTeardown.stopDetached(expectedSessionId"))
        assertFalse(clearBody.contains("sessionLifecycle.stop"))
        val adoptionBody = source
            .substringAfter("private suspend fun adoptSubtitlePlayback(")
            .substringBefore("private suspend fun confirmSubtitlePlaybackPublication(")
        assertFalse(adoptionBody.contains("rollbackUnpublishedActiveSession("))
    }

    private suspend fun TestScope.mountUnpublished(
        harness: Harness,
        identity: SubtitleIdentity,
    ) {
        harness.adapter.select(identity)
        runCurrent()
        assertEquals(identity, harness.adapter.snapshot.localMountIdentity)
        assertEquals("session-1", harness.lifecycle.currentSessionId)
        assertEquals("session-1", harness.port.pendingPlayback?.sessionId)
    }

    private suspend fun Harness.mountUnpublished(identity: SubtitleIdentity) {
        testScope.mountUnpublished(this, identity)
    }

    private suspend fun TestScope.verifyQueuedSubtitleAndQuality(
        mutate: (TvSubtitleTransactionAdapter) -> Unit,
    ) {
        val adoptionGate = CompletableDeferred<Unit>()
        val harness = harness(backgroundScope, adoptionGate = adoptionGate)
        harness.adapter.select(sidecarB)
        runCurrent()

        mutate(harness.adapter)
        adoptionGate.complete(Unit)
        runCurrent()

        harness.assertRollbackBeforeStage(
            nextClassification = "quality_changed",
            nextSubtitleIndex = 5,
            qualityPreference = "720p",
        )
        assertTrue(harness.persistence.isEmpty())
    }

    private fun TestScope.harness(
        adapterScope: CoroutineScope,
        durableScope: CoroutineScope = adapterScope,
        forceSupersededAfterAdoption: Boolean = false,
        adoptionGate: CompletableDeferred<Unit>? = null,
        adoptionFailure: Throwable? = null,
    ): Harness {
        val events = mutableListOf<String>()
        val port = PendingPublicationPort(events)
        val lifecycle = LifecycleSettlementProbe(events)
        val persistence = mutableListOf<CommittedSubtitle>()
        val persistedSessionIds = mutableListOf<String?>()
        val adapter = TvSubtitleTransactionAdapter(
            scope = adapterScope,
            stagedPort = port,
            persistencePort = object : TvSubtitlePersistencePort {
                override suspend fun persist(
                    committed: CommittedSubtitle,
                    context: TvSubtitlePlaybackContext,
                ): Boolean {
                    events += "persist:${committed.identity}"
                    persistence += committed
                    persistedSessionIds += context.sessionId
                    return true
                }
            },
            durablePersistenceScope = durableScope,
            settlementScope = durableScope,
            isLocallyMountable = { identity -> identity == port.mountedSidecarIdentity },
            onCommittedPlayback = { adoption ->
                lifecycle.adopt(adoption.playback.sessionId)
                adoptionGate?.await()
                adoptionFailure?.let { throw it }
                if (forceSupersededAfterAdoption) {
                    TvSubtitleAdoptionResult.Superseded
                } else {
                    TvSubtitleAdoptionResult.Adopted
                }
            },
            onCommittedPlaybackConfirmed = { playback ->
                lifecycle.confirm(playback.sessionId)
            },
            onCommittedPlaybackRollback = { playback, _ ->
                lifecycle.rollback(playback.sessionId)
            },
        )
        adapter.resetContent(context(), sidecarA)
        return Harness(
            testScope = this,
            adapter = adapter,
            port = port,
            lifecycle = lifecycle,
            persistence = persistence,
            persistedSessionIds = persistedSessionIds,
            events = events,
        )
    }

    private fun context(
        sessionId: String? = "session-a",
        outputRouteGeneration: Long = 0,
    ): TvSubtitlePlaybackContext {
        val playbackContext = org.siloserver.silo.model.playback.ClientPlaybackContext(
            formFactor = "tv",
            appVersion = "test",
            output = PlaybackOutputContext(
                outputContextId = outputRouteGeneration.toString(),
            ),
        )
        return TvSubtitlePlaybackContext(
            contentId = "movie",
            mediaFileId = 11,
            versionId = "version-11",
            sessionId = sessionId,
            positionSeconds = 42.0,
            audioTrackIndex = 1,
            qualityPreference = "auto",
            subtitleTracks = emptyList(),
            outputRouteGeneration = outputRouteGeneration,
            clientPlaybackContext = playbackContext,
            writeScope = tvTestPlaybackWriteScope,
        )
    }

    private data class Harness(
        val testScope: TestScope,
        val adapter: TvSubtitleTransactionAdapter,
        val port: PendingPublicationPort,
        val lifecycle: LifecycleSettlementProbe,
        val persistence: List<CommittedSubtitle>,
        val persistedSessionIds: List<String?>,
        val events: MutableList<String>,
    ) {
        fun assertRollbackBeforeStage(
            nextClassification: String,
            nextSubtitleIndex: Int,
            outputRouteGeneration: Long = 0,
            qualityPreference: String = "auto",
        ) {
            val rollbackManager = events.indexOf("manager-rollback:session-1")
            val rollbackLifecycle = events.indexOf("lifecycle-rollback:session-1")
            val nextStage = events.indexOf(
                "stage:$nextClassification:$nextSubtitleIndex:" +
                    "$outputRouteGeneration:$qualityPreference",
            )

            assertTrue(rollbackManager >= 0, "The manager publication must be rolled back.")
            assertTrue(
                rollbackLifecycle > rollbackManager,
                "Lifecycle rollback must follow manager rollback.",
            )
            assertTrue(
                nextStage > rollbackLifecycle,
                "The next request must stage only after joint rollback. Events: $events",
            )
            assertEquals(2, port.requests.size)
        }
    }

    private class PendingPublicationPort(
        private val events: MutableList<String>,
    ) : TvSubtitleStagedReplanPort {
        val requests = mutableListOf<TvSubtitleStageRequest>()
        var pendingPlayback: TvSubtitleCommittedPlayback? = null
            private set
        var mountedSidecarIdentity: SubtitleIdentity? = null
            private set
        private var settlement = CompletableDeferred<Unit>().apply { complete(Unit) }
        private var sessionSequence = 0
        private var stageStarted: CompletableDeferred<Unit>? = null
        private var stageRelease: CompletableDeferred<Unit>? = null
        private var commitStarted: CompletableDeferred<Unit>? = null
        private var commitRelease: CompletableDeferred<Unit>? = null
        private var confirmStarted: CompletableDeferred<Unit>? = null
        private var confirmRelease: CompletableDeferred<Unit>? = null
        private var rollbackStarted: CompletableDeferred<Unit>? = null
        private var rollbackRelease: CompletableDeferred<Unit>? = null
        var nextSubtitleMode: PlaybackSubtitleModeV3? = null
        var failRollback = false
        var failConfirm = false
        var failNextStage = false

        override suspend fun stage(
            request: TvSubtitleStageRequest,
        ): ApiResult<TvStagedSubtitleCandidate> {
            settlement.await()
            stageStarted?.complete(Unit)
            stageRelease?.await()
            stageStarted = null
            stageRelease = null
            when (val validated = request.toManagerStageInput()) {
                is ApiResult.Error -> return validated
                is ApiResult.NetworkError -> return validated
                is ApiResult.Success -> Unit
            }
            if (failNextStage) {
                failNextStage = false
                return ApiResult.Error(
                    code = 500,
                    error = "stage_failed",
                    message = "stage failed",
                )
            }
            requests += request
            events += "stage:${request.classification}:${request.subtitleTrackIndex}:" +
                "${request.outputRouteGeneration}:${request.qualityPreference}"
            val sessionId = "session-${++sessionSequence}"
            val off = request.subtitleTrackIndex < 0
            val subtitleMode = nextSubtitleMode ?: if (off) {
                PlaybackSubtitleModeV3.OFF
            } else {
                PlaybackSubtitleModeV3.RENDER
            }
            nextSubtitleMode = null
            return ApiResult.Success(
                TvStagedSubtitleCandidate(
                    id = sessionId,
                    sessionId = sessionId,
                    selectedAudioIndex = request.audioTrackIndex,
                    selectedSubtitleIndex = request.subtitleTrackIndex,
                    subtitleMode = subtitleMode,
                    hasSidecar = subtitleMode != PlaybackSubtitleModeV3.OFF &&
                        subtitleMode != PlaybackSubtitleModeV3.BURN_IN,
                    subtitleTracks = emptyList(),
                    qualityPreference = request.qualityPreference,
                    outputRouteGeneration = request.outputRouteGeneration,
                ),
            )
        }

        override suspend fun commit(
            candidate: TvStagedSubtitleCandidate,
        ): ApiResult<TvSubtitleCommittedPlayback> {
            commitStarted?.complete(Unit)
            commitRelease?.await()
            commitStarted = null
            commitRelease = null
            events += "commit:${candidate.sessionId}"
            val playback = TvSubtitleCommittedPlayback(
                sessionId = candidate.sessionId,
                subtitleTracks = candidate.subtitleTracks,
                outputRouteGeneration = candidate.outputRouteGeneration,
            )
            mountedSidecarIdentity = candidate.selectedSubtitleIndex
                ?.takeIf { it >= 0 }
                ?.let(SubtitleIdentity::ServerSidecar)
            pendingPlayback = playback
            settlement = CompletableDeferred()
            return ApiResult.Success(playback)
        }

        override suspend fun discard(candidate: TvStagedSubtitleCandidate) {
            events += "discard:${candidate.sessionId}"
        }

        override suspend fun confirmCommitted(playback: TvSubtitleCommittedPlayback) {
            events += "manager-confirm:${playback.sessionId}"
            confirmStarted?.complete(Unit)
            confirmRelease?.await()
            confirmStarted = null
            confirmRelease = null
            if (failConfirm) {
                failConfirm = false
                throw IllegalStateException("confirm failed")
            }
            if (pendingPlayback?.sessionId == playback.sessionId) {
                pendingPlayback = null
                settlement.complete(Unit)
            }
        }

        override suspend fun abandonCommitted(playback: TvSubtitleCommittedPlayback) {
            events += "manager-rollback:${playback.sessionId}"
            rollbackStarted?.complete(Unit)
            rollbackRelease?.await()
            rollbackStarted = null
            rollbackRelease = null
            if (failRollback) {
                failRollback = false
                throw IllegalStateException("rollback failed")
            }
            if (pendingPlayback?.sessionId == playback.sessionId) {
                pendingPlayback = null
                settlement.complete(Unit)
            }
        }

        fun suspendNextRollback() {
            rollbackStarted = CompletableDeferred()
            rollbackRelease = CompletableDeferred()
        }

        suspend fun awaitRollbackStarted() {
            requireNotNull(rollbackStarted).await()
        }

        fun releaseRollback() {
            requireNotNull(rollbackRelease).complete(Unit)
        }

        fun suspendNextStage() {
            stageStarted = CompletableDeferred()
            stageRelease = CompletableDeferred()
        }

        suspend fun awaitStageStarted() {
            requireNotNull(stageStarted).await()
        }

        fun releaseStage() {
            requireNotNull(stageRelease).complete(Unit)
        }

        fun suspendNextConfirm() {
            confirmStarted = CompletableDeferred()
            confirmRelease = CompletableDeferred()
        }

        suspend fun awaitConfirmStarted() {
            requireNotNull(confirmStarted).await()
        }

        fun releaseConfirm() {
            requireNotNull(confirmRelease).complete(Unit)
        }

        fun suspendNextCommit() {
            commitStarted = CompletableDeferred()
            commitRelease = CompletableDeferred()
        }

        suspend fun awaitCommitStarted() {
            requireNotNull(commitStarted).await()
        }

        fun releaseCommit() {
            requireNotNull(commitRelease).complete(Unit)
        }
    }

    private class LifecycleSettlementProbe(
        private val events: MutableList<String>,
    ) {
        var currentSessionId: String? = "session-a"
            private set
        private var predecessorSessionId: String? = null
        val stoppedSessions = mutableListOf<String>()

        fun adopt(sessionId: String) {
            predecessorSessionId = currentSessionId
            currentSessionId = sessionId
            events += "lifecycle-adopt:$sessionId"
        }

        fun confirm(sessionId: String): Boolean {
            if (currentSessionId != sessionId) return false
            events += "lifecycle-confirm:$sessionId"
            predecessorSessionId = null
            return true
        }

        fun rollback(sessionId: String): Boolean {
            if (currentSessionId != sessionId) return false
            events += "lifecycle-rollback:$sessionId"
            currentSessionId = predecessorSessionId
            predecessorSessionId = null
            return true
        }

        fun stopCurrent() {
            val sessionId = currentSessionId ?: return
            events += "lifecycle-stop:$sessionId"
            stoppedSessions += sessionId
            currentSessionId = null
        }
    }

    private companion object {
        val sidecarA: SubtitleIdentity = SubtitleIdentity.ServerSidecar(3)
        val sidecarB: SubtitleIdentity = SubtitleIdentity.ServerSidecar(4)
        val sidecarC: SubtitleIdentity = SubtitleIdentity.ServerSidecar(5)

        fun assertBefore(source: String, first: String, second: String) {
            val firstIndex = source.indexOf(first)
            val secondIndex = source.indexOf(second)
            assertTrue(firstIndex >= 0, "Missing `$first`.")
            assertTrue(secondIndex >= 0, "Missing `$second`.")
            assertTrue(firstIndex < secondIndex, "`$first` must precede `$second`.")
        }
    }
}
