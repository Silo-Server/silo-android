package org.siloserver.silo.watchtogether

import org.siloserver.silo.network.*
import kotlin.test.*

class RoomWorkRecoveryTest {
    private var now = 0L
    private val identity = RoomWorkIdentity(1, "synthetic-room", 1, 2, 3, 4, "synthetic-session", true, "command")
    private fun controller() = RoomWorkRecovery({ now }, RealtimeBackoff({ now }, { 0 }))
    private fun request(kind: RoomWorkKind = RoomWorkKind.Transport) = RoomWorkRequest(kind, "intent-1", action = "seek", positionSeconds = 12.0)
    private fun refused(id: String = "intent-1", delay: Long = 250) = RealtimeRefusal("realtime_rate_limited", requestId = id, retryAfterMs = delay, workStarted = false)

    @Test fun oneShotOnlyRetriesExplicitCorrelatedPreWorkRefusalOnceWithinFiveSeconds() {
        val recovery = controller()
        recovery.sent(request(), identity)
        assertFalse(recovery.refused(refused("foreign")))
        now = 250
        assertTrue(recovery.due { identity }.isEmpty()) // missing ack is not a refusal
        assertTrue(recovery.refused(refused()))
        now = 499; assertTrue(recovery.due { identity }.isEmpty())
        now = 500; assertEquals(listOf(request()), recovery.due { identity })
        assertTrue(recovery.refused(refused()))
        now = 750; assertTrue(recovery.due { identity }.isEmpty())
    }

    @Test fun everyIdentityCoordinateAndAuthorityFencesRefusedRetry() {
        val changed = listOf(
            identity.copy(engagement = 2), identity.copy(roomId = "other"), identity.copy(selectionRevision = 2),
            identity.copy(generation = 3), identity.copy(connectionEpoch = 4), identity.copy(connectionId = 5),
            identity.copy(sessionId = "other"), identity.copy(authority = false), identity.copy(commandId = "other"),
        )
        for (next in changed) {
            now = 0
            val recovery = controller()
            recovery.sent(request(), identity); recovery.refused(refused())
            now = 250
            assertTrue(recovery.due { next }.isEmpty())
        }
    }

    @Test fun timeoutSendFailureDownstreamErrorAndDisconnectNeverReplay() {
        for (mode in listOf("timeout", "send", "downstream", "disconnect")) {
            now = 0
            val recovery = controller()
            recovery.sent(request(), identity)
            when (mode) {
                "send" -> recovery.failed("intent-1")
                "downstream" -> recovery.refused(refused().copy(workStarted = true))
                "disconnect" -> recovery.disconnect()
            }
            now = 5_000
            assertTrue(recovery.due { identity }.isEmpty())
            assertTrue(recovery.update.uncertain, mode)
        }
    }

    @Test fun correlatedAppliedStopsTimeoutAndLongRefusalIsDiscarded() {
        val recovery = controller()
        recovery.sent(request(), identity)
        assertFalse(recovery.applied("foreign", "transport_request"))
        assertTrue(recovery.applied("intent-1", "transport_request"))
        now = 5_000; assertTrue(recovery.due { identity }.isEmpty()); assertFalse(recovery.update.uncertain)
        recovery.sent(request(), identity); recovery.refused(refused(delay = 6_000))
        now = 11_000; assertTrue(recovery.due { identity }.isEmpty())
    }

    @Test fun desiredBufferingIsRecomputedAndCancellationRetainsDelayedClassRefusal() {
        val recovery = controller()
        recovery.sent(request(RoomWorkKind.Buffering), identity)
        assertTrue(recovery.refused(refused()))
        assertFalse(recovery.permitted(RoomWorkKind.Ready))
        assertTrue(recovery.permitted(RoomWorkKind.Transport))
        now = 250
        assertTrue(recovery.permitted(RoomWorkKind.Report))
        assertTrue(recovery.due { identity }.isEmpty()) // no stored stale buffering report
        recovery.sent(request(RoomWorkKind.Buffering), identity)
        recovery.cancel(RoomWorkKind.Buffering)
        assertTrue(recovery.refused(refused()))
    }

    @Test fun delayedRefusalOfAnEarlierReportStillDelaysItsClassWithoutQueueingAReport() {
        val recovery = controller()
        val first = request(RoomWorkKind.Report)
        recovery.sent(first, identity)
        now = 500
        recovery.sent(first.copy(requestId = "newer-report", positionSeconds = 13.0), identity)
        assertTrue(recovery.refused(refused()))
        assertFalse(recovery.permitted(RoomWorkKind.Report))
        now = 750
        assertTrue(recovery.permitted(RoomWorkKind.Report))
        assertTrue(recovery.due { identity }.isEmpty())
        now = 6_000
        assertTrue(recovery.due { identity }.isEmpty())
        assertNull(recovery.nextWakeMs())
    }

    @Test fun newUserSeekSupersedesOnlyTheOldSeekAndCannotReplayItsRefusal() {
        val recovery = controller()
        recovery.sent(request(), identity)
        recovery.refused(refused())
        val latest = request().copy(requestId = "latest", positionSeconds = 99.0)
        now = 250
        recovery.sent(latest, identity)
        assertTrue(recovery.refused(refused()))
        assertTrue(recovery.due { identity }.isEmpty())
        assertTrue(recovery.applied("latest", "transport_request"))
    }

    @Test fun playAndPauseKeepSeparateRequestIdentities() {
        val recovery = controller()
        val play = request().copy(requestId = "play", action = "play")
        val pause = request().copy(requestId = "pause", action = "pause")
        recovery.sent(play, identity); recovery.sent(pause, identity)
        assertTrue(recovery.refused(refused("play")))
        assertTrue(recovery.applied("pause", "transport_request"))
        now = 250
        assertEquals(listOf(play), recovery.due { identity })
    }

    @Test fun lobbyRefusalRetriesCurrentDesireButLeavingOrReplacementCancels() {
        val recovery = controller()
        recovery.sent(request(RoomWorkKind.Lobby), identity)
        recovery.refused(refused()); now = 250
        assertEquals(listOf(request(RoomWorkKind.Lobby)), recovery.due { identity })
        recovery.refused(refused()); recovery.cancel(RoomWorkKind.Lobby)
        now = 500; assertTrue(recovery.due { identity }.isEmpty())
    }

    @Test fun lobbyResultsAndEchoesNeedTheCurrentAuthorityAndDesiredValue() {
        val recovery = controller()
        val lobby = request(RoomWorkKind.Lobby).copy(isReady = true)
        recovery.sent(lobby, identity)
        assertFalse(recovery.applied(lobby.requestId, "lobby_ready", identity.copy(role = "guest")))
        recovery.echoLobby(false, identity)
        assertEquals(5_000L, recovery.nextWakeMs())
        recovery.echoLobby(true, identity.copy(connectionEpoch = 9))
        assertEquals(5_000L, recovery.nextWakeMs())
        assertTrue(recovery.applied(lobby.requestId, "lobby_ready", identity))
        assertNull(recovery.nextWakeMs())
        recovery.sent(lobby, identity)
        recovery.echoLobby(true, identity)
        assertNull(recovery.nextWakeMs())
    }
    @Test fun refusedUndispatchedWorkIsNotUncertainOnDisconnect() {
        for (select in listOf(false, true)) {
            now = 0
            val recovery = controller()
            recovery.sent(request(), identity)
            recovery.refused(refused())
            if (select) { now = 250; assertEquals(listOf(request()), recovery.due { identity }) }
            recovery.disconnect()
            assertEquals(0L, recovery.update.uncertaintySequence)
            assertTrue(recovery.due { identity }.isEmpty())
        }
    }

    @Test fun selectedRetryExpiresBeforeDispatchAfterSuspension() {
        val recovery = controller()
        recovery.sent(request(), identity); recovery.refused(refused())
        now = 250; assertEquals(listOf(request()), recovery.due { identity })
        now = 5_001
        assertFalse(recovery.retryStillCurrent(request(), identity))
    }

    @Test fun canceledDesireRetainsOnlyItsBoundedClassCorrelation() {
        val recovery = controller()
        recovery.sent(request(RoomWorkKind.Buffering), identity)
        recovery.cancel(RoomWorkKind.Buffering)
        assertTrue(recovery.refused(refused(delay = 60_000)))
        assertFalse(recovery.permitted(RoomWorkKind.Ready))
        assertTrue(recovery.due { identity }.isEmpty())
    }

    @Test fun physicallyDispatchedRetryWithoutAcknowledgementIsStillUncertain() {
        val recovery = controller()
        recovery.sent(request(), identity); recovery.refused(refused())
        now = 250; assertEquals(listOf(request()), recovery.due { identity })
        assertTrue(recovery.retryStillCurrent(request(), identity))
        recovery.retryDispatched(request())
        recovery.disconnect()
        assertEquals(1L, recovery.update.uncertaintySequence)
    }

    @Test fun raisedClassFloorDiscardsSelectedUnsentTransportWithoutUncertainty() {
        val recovery = controller()
        val first = request().copy(action = "pause")
        val second = first.copy(requestId = "intent-2", action = "play")
        recovery.sent(first, identity); recovery.sent(second, identity)
        recovery.refused(refused())
        now = 250; assertEquals(listOf(first), recovery.due { identity })
        recovery.refused(refused("intent-2", 1_000))
        assertFalse(recovery.retryStillCurrent(first, identity))
        recovery.failed(first.requestId)
        assertEquals(1L, recovery.update.discardedTransportSequence)
        assertEquals(0L, recovery.update.uncertaintySequence)
        now = 1_249; assertTrue(recovery.due { identity }.isEmpty())
        now = 1_250; assertEquals(listOf(second), recovery.due { identity })
        assertTrue(recovery.retryStillCurrent(second, identity))
        recovery.retryDispatched(second)
        recovery.disconnect()
        assertEquals(1L, recovery.update.uncertaintySequence)
    }

    @Test fun raisedClassFloorRetainsCurrentLobbyDesireUntilEligible() {
        val recovery = controller()
        val lobby = request(RoomWorkKind.Lobby).copy(isReady = true)
        val other = request().copy(requestId = "other-action", action = "pause")
        recovery.sent(lobby, identity); recovery.sent(other, identity)
        recovery.refused(refused())
        now = 250; assertEquals(listOf(lobby), recovery.due { identity })
        recovery.refused(refused("other-action", 1_000))
        assertFalse(recovery.retryStillCurrent(lobby, identity)) // predicate owns deferral, not a failed send
        now = 1_249; assertTrue(recovery.due { identity }.isEmpty())
        now = 1_250
        assertTrue(lobby in recovery.due { identity })
        assertTrue(recovery.retryStillCurrent(lobby, identity))
        recovery.retryDispatched(lobby)
        assertTrue(recovery.applied(lobby.requestId, "lobby_ready", identity))
        assertEquals(0L, recovery.update.uncertaintySequence)
    }

    @Test fun downstreamLobbyErrorStillCancelsADeferredDesire() {
        val recovery = controller()
        val lobby = request(RoomWorkKind.Lobby).copy(isReady = true)
        recovery.sent(lobby, identity); recovery.refused(refused())
        assertFalse(recovery.refused(refused().copy(code = "operation_failed", workStarted = true)))
        now = 250
        assertTrue(recovery.due { identity }.isEmpty())
    }

}
