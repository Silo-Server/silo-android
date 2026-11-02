package org.siloserver.silo.network.apiv2

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.flow.toList
import org.siloserver.silo.model.notifications.WsSubscribe
import org.siloserver.silo.network.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventSelectionRecoveryTest {
    @Test fun retriesLatestCorrelatedSelectionOncePerTimerWithoutReconnectOrStaleAck() = runTest {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val desires = MutableSharedFlow<List<String>>()
        val sent = mutableListOf<WsSubscribe>()
        val received = mutableListOf<String>()
        val session = EventSelectionSession(RealtimeBackoff({ testScheduler.currentTime }, { 0 }), true, { testScheduler.currentTime })
        val job = backgroundScope.launch {
            session.frames(listOf("catalog"), { incoming.receive() }, {
                sent += SiloJson.decodeFromString(WsSubscribe.serializer(), it)
            }, { true }, desires).collect { received += it }
        }
        runCurrent()
        assertEquals(1, sent.size)
        val oldId = sent.single().requestId!!
        incoming.send(refusal(oldId, 500))
        runCurrent(); advanceTimeBy(200)
        desires.emit(listOf("user_state")); runCurrent()
        assertEquals(1, sent.size)
        incoming.send("""{"type":"subscribed","request_id":"$oldId"}""")
        incoming.send(refusal(oldId, 250))
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(2, sent.size)
        val latest = sent.last()
        assertEquals(listOf("user_state"), latest.channels)
        assertFalse(received.any { it.contains("subscribed") })
        incoming.send(refusal(latest.requestId!!, 500))
        runCurrent(); advanceTimeBy(499); runCurrent(); assertEquals(2, sent.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, sent.size)
        assertEquals(latest, sent.last())
        incoming.send("""{"type":"subscribed","request_id":"${latest.requestId}"}""")
        runCurrent(); advanceTimeBy(600); runCurrent()
        assertEquals(3, sent.size)
        job.cancel()
    }

    @Test fun oldServersDoNotGainRefusalRetriesAndAuthorityLossCancelsTimer() = runTest {
        suspend fun exercise(enabled: Boolean, loseAuthority: Boolean): Int {
            var current = true
            val incoming = Channel<String>(Channel.UNLIMITED)
            val sent = mutableListOf<WsSubscribe>()
            val job = backgroundScope.launch {
                EventSelectionSession(RealtimeBackoff({ testScheduler.currentTime }, { 0 }), enabled, { testScheduler.currentTime })
                    .frames(listOf("notifications"), { incoming.receive() }, {
                        sent += SiloJson.decodeFromString(WsSubscribe.serializer(), it)
                    }, { current }).collect { }
            }
            runCurrent()
            incoming.send(refusal(sent.single().requestId!!, 250)); runCurrent()
            if (loseAuthority) current = false
            advanceTimeBy(300); runCurrent()
            job.cancel()
            return sent.size
        }
        assertEquals(1, exercise(false, false))
        assertEquals(1, exercise(true, true))
    }

    private fun refusal(id: String, wait: Long) =
        """{"type":"error","code":"realtime_rate_limited","request_id":"$id","retry_after_ms":$wait,"work_started":false}"""
    @Test fun refusedDeadlineSurvivesSocketCompletionAndFencesFreshTicketMint() = runTest {
        val recovery = RealtimeBackoff({ testScheduler.currentTime }, { 0 })
        val incoming = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<WsSubscribe>()
        val first = backgroundScope.launch {
            EventSelectionSession(recovery, true, { testScheduler.currentTime })
                .frames(listOf("notifications"), { incoming.receiveCatching().getOrNull() }, {
                    sent += SiloJson.decodeFromString(WsSubscribe.serializer(), it)
                }, { true }).collect { }
        }
        runCurrent(); incoming.send(refusal(sent.single().requestId!!, 60_000)); runCurrent()
        incoming.close(); runCurrent(); first.join()
        assertTrue(recovery.disconnected() >= 60_000)
        var mints = 0
        val scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "synthetic-proof", identityGeneration = 1)
        val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = UnconfinedTestDispatcher(testScheduler)
                addHandler {
                    mints++
                    respond("""{"type":"https://example.invalid/rate_limited","status":429}""",
                        HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val attempt = backgroundScope.launch { runCatching {
            EventsSocketV2Api(client, tokens, ApiV2Gate.Unrestricted).frames(listOf("notifications"), recovery).toList()
        } }
        try {
            runCurrent(); assertEquals(0, mints)
            advanceTimeBy(59_999); runCurrent(); assertEquals(0, mints)
            advanceTimeBy(1); runCurrent(); assertEquals(1, mints)
        } finally { attempt.cancel(); client.close() }
    }

    @Test fun snapshotAndAccessMetadataDoNotUseTheRefusalDecoder() = runTest {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val metadata = mutableListOf<Pair<String?, String?>>()
        val frames = mutableListOf<String>()
        val job = backgroundScope.launch {
            EventSelectionSession(RealtimeBackoff({ testScheduler.currentTime }, { 0 }), true)
                .frames(listOf("notifications"), { incoming.receiveCatching().getOrNull() }, {}, { true },
                    onEnvelope = { type, code -> metadata += type to code }).collect { frames += it }
        }
        runCurrent()
        incoming.send("""{"type":"snapshot","channel":"notifications","data":[]}""")
        incoming.send("""{"type":"access_changed"}""")
        incoming.send("""{"type":"error","code":"realtime_input_limited"}""")
        runCurrent()
        assertEquals<List<Pair<String?, String?>>>(listOf("snapshot" to null, "access_changed" to null, "error" to "realtime_input_limited"), metadata)
        assertEquals(3, frames.size)
        job.cancel(); incoming.close()
    }

    @Test fun currentAttemptShieldSurvivesMissingCloseMetadataAndTransportError() = runTest {
        for (failure in listOf(null, IllegalStateException("synthetic EOF"))) {
            val incoming = Channel<String>(Channel.UNLIMITED)
            incoming.send("""{"type":"error","code":"realtime_input_limited"}""")
            incoming.close(failure)
            val error = runCatching {
                eventAttemptFrames(RealtimeBackoff({ testScheduler.currentTime }, { 0 }), true,
                    listOf("notifications"), { incoming.receiveCatching().let { result -> result.exceptionOrNull()?.let { throw it }; result.getOrNull() } },
                    {}, { true }, { null }, {}).toList()
            }.exceptionOrNull()
            assertTrue(assertIs<RealtimeSocketClosed>(error).inputShield)
        }
    }

    @Test fun attemptAuthorityAccessChangeAndCancellationTakePrecedenceOverShield() = runTest {
        for (terminal in listOf("authority", "access", "access-close")) {
            var current = true; var accessChanges = 0; var index = 0
            val result = runCatching {
                eventAttemptFrames(RealtimeBackoff({ testScheduler.currentTime }, { 0 }), true,
                    listOf("notifications"), {
                        when (index++) {
                            0 -> """{"type":"error","code":"realtime_input_limited"}"""
                            1 -> when (terminal) {
                                "authority" -> { current = false; null }
                                "access" -> """{"type":"access_changed"}"""
                                else -> null
                            }
                            else -> null
                        }
                    }, {}, { current }, {
                        if (terminal == "access-close") io.ktor.websocket.CloseReason(4001, "access changed") else null
                    }, { accessChanges++ }).toList()
            }
            assertTrue(result.isSuccess)
            assertEquals(if (terminal.startsWith("access")) 1 else 0, accessChanges)
        }
    }

    @Test fun freshTicketAdmissionRetainsFloorAcrossProfileRefreshButPartitionsNewLogin() = runTest {
        var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof", identityGeneration = 1)
        var login = "login-a"
        val tokens = object : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
            override suspend fun snapshotCurrentScope() = scope
            override suspend fun snapshotDurableLoginAuthority() = DurableLoginAuthority(login, scope)
        }
        val recovery = RealtimeBackoff({ testScheduler.currentTime }, { 0 })
        recovery.bindAdmissionOwner(tokens.realtimeAdmissionOwner(scope)!!)
        recovery.refused("realtime_rate_limited", 45_000, retainForAdmission = true)
        var mints = 0
        val client = HttpClient(MockEngine) {
            engine { dispatcher = UnconfinedTestDispatcher(testScheduler); addHandler {
                mints++
                respond("""{"status":429}""", HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            } }
        }
        try {
            scope = scope.copy(profileId = "profile-b", profileToken = "replacement-proof", identityGeneration = 2, credentialEpoch = 9)
            val same = backgroundScope.launch { DefaultNotificationsRealtimeClient(
                EventsSocketV2Api(client, tokens, ApiV2Gate.Unrestricted), recovery = recovery).connect().toList() }
            runCurrent(); advanceTimeBy(44_999); runCurrent(); assertEquals(0, mints)
            same.cancel(); runCurrent()
            login = "login-b"; scope = scope.copy(identityGeneration = 3)
            val other = backgroundScope.launch { DefaultNotificationsRealtimeClient(
                EventsSocketV2Api(client, tokens, ApiV2Gate.Unrestricted), recovery = recovery).connect().toList() }
            runCurrent(); assertEquals(1, mints)
            other.cancel()
        } finally { client.close() }
    }

    @Test fun cancellingAttemptAfterShieldDoesNotTurnCancellationIntoRecovery() = runTest {
        val incoming = Channel<String>(Channel.UNLIMITED)
        incoming.send("""{"type":"error","code":"realtime_input_limited"}""")
        val job = backgroundScope.launch {
            eventAttemptFrames(RealtimeBackoff({ testScheduler.currentTime }, { 0 }), true,
                listOf("notifications"), { incoming.receive() }, {}, { true }, { null }, {}).toList()
        }
        runCurrent(); job.cancel(); job.join()
        assertTrue(job.isCancelled)
        incoming.close()
    }

}
