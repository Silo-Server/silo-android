package org.siloserver.silo.network

import kotlin.test.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RealtimeRecoveryTest {
    @Test fun opensDoNotResetOpaqueOrOutageBackoff() {
        var now = 0L
        val recovery = RealtimeBackoff({ now }, { 0 })
        assertEquals(1_000L, recovery.disconnected())
        recovery.useful() // a new open/initial snapshot supplies no 30-second recovery
        assertEquals(2_000L, recovery.disconnected())
        assertEquals(2_000L, recovery.refused("realtime_unavailable", 250))
        recovery.useful()
        assertEquals(4_000L, recovery.refused("realtime_unavailable", 250))
        assertEquals(8_000L, recovery.refused("realtime_unavailable", 250))
        assertEquals(16_000L, recovery.refused("realtime_unavailable", 250))
        assertEquals(30_000L, recovery.refused("realtime_unavailable", 250))
        recovery.useful(); now = 30_000; recovery.useful()
        assertFalse(recovery.overloaded)
        assertEquals(2_000L, recovery.refused("realtime_unavailable", 250))
    }

    @Test fun onlyTheNamed1008UsesInputShieldDelaysAndJitterNeverShortensMinimum() {
        assertFalse(RealtimeSocketClosed(1008, "subscribe required").inputShield)
        assertFalse(RealtimeSocketClosed(1000, "realtime input shield").inputShield)
        assertTrue(RealtimeSocketClosed(1008, "realtime input shield").inputShield)
        val recovery = RealtimeBackoff({ 0 }, { 250 })
        assertEquals(listOf(5_250L, 10_250L, 20_250L, 30_250L), List(4) { recovery.disconnected(inputShield = true) })
        assertEquals(60_250L, recovery.refused("realtime_rate_limited", 60_000))
    }

    @Test fun incompleteOrDownstreamErrorsNeverProveNoWork() {
        assertFalse(RealtimeRefusal("realtime_rate_limited", requestId = "a", retryAfterMs = 250).preWork)
        assertFalse(RealtimeRefusal("downstream", requestId = "a", retryAfterMs = 250, workStarted = false).preWork)
        assertFalse(RealtimeRefusal("realtime_unavailable", requestId = "a", retryAfterMs = 250, workStarted = true).preWork)
        assertTrue(RealtimeRefusal("realtime_unavailable", requestId = "a", retryAfterMs = 2000, workStarted = false).preWork)
    }
    @Test fun admissionOwnerDoesNotRequireASelectedProfileOrChangeOnRefresh() = kotlinx.coroutines.test.runTest {
        var scope = AuthScopeSnapshot("server", null, "https://example.invalid", null, identityGeneration = 1)
        val tokens = object : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
            override suspend fun snapshotCurrentScope() = scope
            override suspend fun snapshotDurableLoginAuthority(): DurableLoginAuthority? = null
            override suspend fun loginSessionId(serverId: String) = "stable-login"
        }
        val owner = tokens.realtimeAdmissionOwner(scope)
        assertEquals(RealtimeAdmissionOwner("server", "https://example.invalid", "stable-login"), owner)
        scope = scope.copy(profileId = "profile", profileToken = "synthetic-proof", identityGeneration = 2, credentialEpoch = 5)
        assertEquals(owner, tokens.realtimeAdmissionOwner(scope))
    }

    @Test fun changingAdmissionOwnerResetsOnlyAnUnrelatedLoginRecovery() {
        val recovery = RealtimeBackoff({ 0 }, { 0 })
        val first = RealtimeAdmissionOwner("server", "https://example.invalid", "login-a")
        recovery.bindAdmissionOwner(first)
        recovery.refused("realtime_rate_limited", 45_000, retainForAdmission = true)
        assertEquals(45_000L, recovery.disconnected(inputShield = true))
        recovery.bindAdmissionOwner(first.copy())
        assertEquals(45_000L, recovery.remainingAdmissionMs())
        assertTrue(recovery.overloaded)
        recovery.bindAdmissionOwner(first.copy(loginId = "login-b"))
        assertEquals(0L, recovery.remainingAdmissionMs()); assertFalse(recovery.overloaded)
        assertEquals(5_000L, recovery.disconnected(inputShield = true))
    }

    @Test fun quietAcceptedHealthResetsAtDisconnectWithoutAnotherFrame() {
        var now = 0L
        val recovery = RealtimeBackoff({ now }, { 0 })
        assertEquals(1_000L, recovery.disconnected())
        assertEquals(2_000L, recovery.disconnected())
        recovery.useful()
        now = 30_000
        assertEquals(1_000L, recovery.disconnected())
        recovery.useful()
        now = 60_000
        assertEquals(1_000L, recovery.disconnected())
    }

    @Test fun quietAcceptedHealthResetsReasonCountersButRetainsAdmissionFloor() {
        var now = 0L
        val recovery = RealtimeBackoff({ now }, { 0 })
        assertEquals(5_000L, recovery.disconnected(inputShield = true))
        assertEquals(2_000L, recovery.refused("realtime_unavailable", 250))
        recovery.refused("realtime_rate_limited", 60_000, retainForAdmission = true)
        recovery.useful()
        now = 30_000
        assertEquals(30_000L, recovery.disconnected())
        assertFalse(recovery.overloaded)
        assertEquals(30_000L, recovery.remainingAdmissionMs())
        now = 60_000
        assertEquals(5_000L, recovery.disconnected(inputShield = true))
        assertEquals(2_000L, recovery.disconnected(unavailableFailure = true))
    }

    @Test fun shortOpenAndRefusedTimeNeverQualifyAsAcceptedHealth() {
        var now = 0L
        val recovery = RealtimeBackoff({ now }, { 0 })
        recovery.disconnected(inputShield = true)
        recovery.useful()
        now = 29_999
        assertEquals(10_000L, recovery.disconnected(inputShield = true))
        now = 90_000 // opening and pongs do not call useful()
        assertEquals(20_000L, recovery.disconnected(inputShield = true))
        recovery.useful()
        now += 1_000
        recovery.refused("realtime_unavailable", 250)
        now += 60_000 // time since refusal is not useful health
        assertEquals(30_000L, recovery.disconnected(inputShield = true))
        assertEquals(4_000L, recovery.disconnected(unavailableFailure = true))
        assertTrue(recovery.overloaded)
    }

    @Test fun quietNotificationClientCompletionUsesAcceptedSelectionHealth() = kotlinx.coroutines.test.runTest {
        val recovery = RealtimeBackoff({ testScheduler.currentTime }, { 0 })
        recovery.disconnected() // prior short failure
        var transmissions = 0
        val client = DefaultNotificationsRealtimeClient(frames = { channels, retained ->
            var requestId: String? = null
            var received = false
            org.siloserver.silo.network.apiv2.EventSelectionSession(retained, true, { testScheduler.currentTime }).frames(
                channels,
                receive = {
                    if (!received) {
                        received = true
                        """{"type":"subscribed","request_id":"$requestId","channels":["notifications"]}"""
                    } else { kotlinx.coroutines.delay(30_000); null }
                },
                transmit = { raw ->
                    transmissions++
                    val obj = SiloJson.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
                    requestId = (obj["request_id"] as kotlinx.serialization.json.JsonPrimitive).content
                },
                current = { true },
            )
        }, recovery = recovery)
        repeat(2) {
            val events = mutableListOf<NotificationRealtimeEvent>()
            client.connect().collect { events += it }
            assertEquals(1_000L, (events.last() as NotificationRealtimeEvent.Closed).retryAfterMs)
        }
        assertEquals(2, transmissions)
        assertEquals(60_000L, testScheduler.currentTime)
    }

    @Test fun quietTimeAfterEventShieldNoticeDoesNotResetReasonBackoff() = kotlinx.coroutines.test.runTest {
        val recovery = RealtimeBackoff({ testScheduler.currentTime }, { 0 })
        recovery.disconnected(inputShield = true)
        var frame = 0
        val client = DefaultNotificationsRealtimeClient(frames = { channels, retained ->
            org.siloserver.silo.network.apiv2.EventSelectionSession(retained, true, { testScheduler.currentTime }).frames(
                channels,
                receive = {
                    when (frame++) {
                        0 -> """{"type":"subscribed","request_id":"events-1","channels":["notifications"]}"""
                        1 -> """{"type":"error","code":"realtime_input_limited"}"""
                        else -> { kotlinx.coroutines.delay(30_000); throw RealtimeSocketClosed(1008, "realtime input shield") }
                    }
                }, transmit = { }, current = { true },
            )
        }, recovery = recovery)
        var closed: NotificationRealtimeEvent.Closed? = null
        client.connect().collect { if (it is NotificationRealtimeEvent.Closed) closed = it }
        assertEquals(10_000L, closed?.retryAfterMs)
    }

    @Test fun cancelledEventAttemptDoesNotCountOfflineTimeAsAcceptedHealth() = kotlinx.coroutines.test.runTest {
        val recovery = RealtimeBackoff({ testScheduler.currentTime }, { 0 })
        recovery.disconnected(inputShield = true)
        val accepted = kotlinx.coroutines.CompletableDeferred<Unit>()
        var attempt = 0
        val client = DefaultNotificationsRealtimeClient(frames = { _, retained -> kotlinx.coroutines.flow.flow {
            if (attempt++ == 0) {
                retained.useful(); accepted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            } else throw RealtimeSocketClosed(1008, "realtime input shield")
        } }, recovery = recovery)
        val job = backgroundScope.launch { client.connect().collect { } }
        accepted.await()
        kotlinx.coroutines.delay(1_000); job.cancel(); job.join()
        kotlinx.coroutines.delay(60_000)
        var closed: NotificationRealtimeEvent.Closed? = null
        client.connect().collect { if (it is NotificationRealtimeEvent.Closed) closed = it }
        assertEquals(10_000L, closed?.retryAfterMs)
    }

    @Test fun quietAcceptedHealthResetsBeforeRefusalAtThirtySecondBoundary() {
        for (elapsed in listOf(29_999L, 30_000L)) {
            var now = 0L
            val recovery = RealtimeBackoff({ now }, { 0 })
            repeat(5) { recovery.refused("realtime_unavailable", 250) }
            repeat(2) { recovery.disconnected() }
            repeat(3) { recovery.disconnected(inputShield = true) }
            recovery.useful() // accepted subscription; no second useful frame
            now = elapsed
            val healthy = elapsed >= 30_000
            assertEquals(if (healthy) 2_000L else 30_000L, recovery.refused("realtime_unavailable", 250), "$elapsed outage")
            assertEquals(if (healthy) 5_000L else 30_000L, recovery.disconnected(inputShield = true), "$elapsed shield")
            assertEquals(if (healthy) 1_000L else 4_000L, recovery.disconnected(), "$elapsed opaque")
        }
    }

}
