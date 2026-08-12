package org.siloserver.silo.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityTransitionBarrierTest {
    @Test
    fun gateRunsInlineBeforeMutationAndDidChangeRunsAfterward() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val timeline = mutableListOf<String>()
        barrier.installGate { transition -> timeline += "gate:${transition.phase}:${transition.generation}" }

        val result = barrier.changing(IdentityTransitionKind.SIGN_OUT) {
            timeline += "mutation:${barrier.generation.value}"
            "done"
        }

        assertEquals("done", result)
        assertEquals(
            listOf(
                "gate:WILL_CHANGE:1",
                "mutation:1",
            ),
            timeline,
        )
        assertEquals(1, barrier.generation.value)
    }

    @Test
    fun allInstalledPrivacyGatesRunBeforeMutation() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val timeline = mutableListOf<String>()
        barrier.installGate { timeline += "diagnostics" }
        barrier.installGate { timeline += "room" }

        barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            timeline += "mutation"
        }

        assertEquals(listOf("diagnostics", "room", "mutation"), timeline)
    }

    @Test
    fun didChangeIsEmittedWhenMutationFails() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val observed = mutableListOf<IdentityTransition>()
        barrier.installObserverForTests(observed::add)

        assertFailsWith<IllegalStateException> {
            barrier.changing(IdentityTransitionKind.SERVER_REMOVE) {
                error("failed")
            }
        }

        assertEquals(
            listOf(IdentityTransitionPhase.WILL_CHANGE, IdentityTransitionPhase.DID_CHANGE),
            observed.map(IdentityTransition::phase),
        )
        assertEquals(listOf(1L, 1L), observed.map(IdentityTransition::generation))
    }

    @Test
    fun targetMetadataIsResolvedOnceAndCarriedAcrossBothTransitionPhases() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val observed = mutableListOf<IdentityTransition>()
        var targetReads = 0
        barrier.installObserverForTests(observed::add)

        barrier.changing(
            kind = IdentityTransitionKind.SERVER_REMOVE,
            target = {
                targetReads += 1
                IdentityTransitionTarget(
                    serverId = "server-b",
                    affectsCurrentIdentity = false,
                )
            },
        ) { }

        assertEquals(1, targetReads)
        assertEquals(listOf("server-b", "server-b"), observed.map(IdentityTransition::targetServerId))
        assertEquals(listOf(false, false), observed.map(IdentityTransition::affectsCurrentIdentity))
    }

    @Test
    fun currentGenerationFenceSerializesTheGuardedBoundaryWithMutation() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val guardedStarted = CompletableDeferred<Unit>()
        val releaseGuard = CompletableDeferred<Unit>()
        val mutationRequested = CompletableDeferred<Unit>()
        val mutationStarted = CompletableDeferred<Unit>()

        val guarded = async {
            barrier.withCurrentGeneration(0) {
                guardedStarted.complete(Unit)
                releaseGuard.await()
                "sent"
            }
        }
        guardedStarted.await()
        val mutation = async {
            mutationRequested.complete(Unit)
            barrier.changing(IdentityTransitionKind.SIGN_OUT) {
                mutationStarted.complete(Unit)
            }
        }
        mutationRequested.await()
        runCurrent()
        assertFalse(mutationStarted.isCompleted)

        releaseGuard.complete(Unit)
        assertEquals("sent", guarded.await())
        mutation.await()
        assertEquals(1, barrier.generation.value)
        assertEquals(null, barrier.withCurrentGeneration(0) { "must-not-run" })
    }

    @Test
    fun tokenMutationsAreWrappedExactlyOnceWithTheExpectedKind() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val observed = mutableListOf<IdentityTransition>()
        barrier.installObserverForTests(observed::add)
        val tokens = TokenManagerImpl(barrier)
        val temporary = TemporaryAuthScope(
            generationId = "cast-1",
            serverId = "server-temp",
            serverUrl = "https://temporary.example",
            accessToken = "access",
            refreshToken = "refresh",
            profileId = "profile",
            profileToken = "profile-token",
            expiresAtEpochMs = 10_000,
        )

        suspend fun assertMutation(kind: IdentityTransitionKind, mutation: suspend () -> Unit) {
            observed.clear()
            mutation()
            assertEquals(listOf(kind, kind), observed.map(IdentityTransition::kind))
            assertEquals(
                listOf(IdentityTransitionPhase.WILL_CHANGE, IdentityTransitionPhase.DID_CHANGE),
                observed.map(IdentityTransition::phase),
            )
        }

        assertMutation(IdentityTransitionKind.SIGN_IN) { tokens.saveTokens("a", "r", 60) }
        observed.clear()
        tokens.saveTokens("refreshed-a", "refreshed-r", 60)
        assertEquals(emptyList(), observed, "background token refresh must not close the identity gate")
        assertMutation(IdentityTransitionKind.SIGN_OUT) { tokens.clearTokens() }
        assertMutation(IdentityTransitionKind.SIGN_IN) { tokens.saveTokens("new-a", "new-r", 60) }
        assertMutation(IdentityTransitionKind.SIGN_OUT) { tokens.invalidateSession() }
        assertMutation(IdentityTransitionKind.SIGN_OUT) { tokens.signOutCurrentServer() }
        assertMutation(IdentityTransitionKind.TEMPORARY_SCOPE_BEGIN) { tokens.beginTemporaryScope(temporary) }
        assertMutation(IdentityTransitionKind.TEMPORARY_SCOPE_END) { tokens.endTemporaryScope() }
    }
}
