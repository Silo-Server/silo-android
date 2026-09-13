package org.siloserver.silo.repository

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.model.playback.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.*
import kotlin.test.*

class SequencedPlaybackTest {
    private class Store : PlaybackJournalStore {
        var entries = emptyList<PlaybackJournalEntry>()
        var fail = false
        var failStop = false
        override suspend fun read() = entries
        override suspend fun write(entries: List<PlaybackJournalEntry>) {
            check(!fail && !(failStop && entries.any { it.stop != null })) { "disk failed" }
            this.entries = SiloJson.decodeFromString(SiloJson.encodeToString(entries))
        }
    }
    private class Identity : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
        var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof",
            identityGeneration = 1, isIdentityGenerationStamped = true, credentialEpoch = 1)
        var login = "login-1"
        var temporary = false
        override suspend fun snapshotCurrentScope() = scope
        override suspend fun snapshotDurableLoginAuthority() = if (temporary) null else DurableLoginAuthority(login, scope)
    }
    private val installation = "11111111-1111-4111-8111-111111111111"
    private val stopId = "22222222-2222-4222-8222-222222222222"
    private fun request() = PlaybackStartRequestV3(clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
        fileId = 42, profileId = "profile", playbackAttemptId = "attempt-1",
        subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
        capabilities = ClientCodecCapabilities(), clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"))
    private fun entry() = PlaybackJournalEntry("server", "https://example.invalid", "login-1", "account-1",
        "profile", installation, "attempt-1", request().v2Body(installation), sessionId = "session-1")
    private fun caps(features: String = "\"sequenced_progress_v1\"") =
        """{"installation_id":"$installation","revision":"1","state":"available","allowed":true,"protocol_versions":[3],"features":[$features],"deliveries":["direct"]}"""
    private val account = """{"id":"account-1","username":"test","email":"","role":"user"}"""
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private val manifest = PlaybackManifestV2(installation, "a".repeat(64), "book", "edition", 1000.0,
        listOf(PlaybackManifestPartV2("42", 0.0, 600.0), PlaybackManifestPartV2("43", 600.0, 400.0)))
    private fun boundRequest(file: Int, attempt: String) = request().copy(fileId = file, playbackAttemptId = attempt,
        progressPersistence = ProgressPersistenceV3.CLIENT_BOUND, timelineId = manifest.timelineId,
        clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3 + BOUND_CLIENT_TIMELINE_FEATURE, startPosition = 0.0)
    private fun boundDecision(file: Int, session: String): String {
        val base = SiloJson.parseToJsonElement(adoptedDecision.replace("session-1", session).replace("\"42\"", "\"$file\"")).jsonObject
        return JsonObject(base + ("progress_timeline" to SiloJson.encodeToJsonElement(manifest.select(file)))).toString()
    }

    private val timelineRefusal = """{"protocol_version":3,"server_features":["playback_plan_v3","neutral_playback_v3_contract_v1","sequenced_progress_v1"],"outcome":"adaptation_unavailable","terminal":{"reason":"client_timeline_changed","message":"Open the book again to use its updated timeline.","retryable":false}}"""

    @Test fun definitiveTimelineRefusalRequiresExplicitFreshDiscoveryAndNewAttempt() = runTest {
        val identity = Identity(); val store = Store(); var discoveries = 0
        val updatedManifest = manifest.copy(timelineId = "b".repeat(64))
        val bodies = mutableListOf<String>()
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps("\"sequenced_progress_v1\",\"bound_client_timeline\""))
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/timelines/43" -> {
                discoveries++
                reply(SiloJson.encodeToString(if (discoveries == 1) manifest else updatedManifest))
            }
            "/api/v2/playback/start" -> {
                bodies += req.body.toByteArray().decodeToString()
                assertEquals(identity.scope, req.attributes[AuthScopeAttributeKey])
                reply(if (bodies.size == 1) timelineRefusal else
                    boundDecision(43, "session-new").replace(manifest.timelineId, updatedManifest.timelineId), HttpStatusCode.Created)
            }
            else -> error("Unexpected transport")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.discoverTimeline(43, "book", identity.scope))
            val rejected = assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(boundRequest(43, "rejected"), identity.scope))
            assertEquals("client_timeline_changed", rejected.data.terminal?.reason)
            assertIs<PlaybackV3Validation.Terminal>(rejected.data.validateForMedia3())
            val retained = store.entries.single()
            assertTrue(retained.terminal); assertNull(retained.sessionId)
            assertEquals(SiloJson.parseToJsonElement(timelineRefusal).jsonObject, retained.rejectedStartDecision)
            assertEquals(SiloJson.parseToJsonElement(bodies.single()), retained.start)
            assertEquals("playback_rejected", assertIs<ApiResult.Error>(runtime.routeEvent(
                PlaybackRouteEventV3(playbackAttemptId = "rejected", event = "terminal"))).error)
            assertEquals(retained, store.entries.single())
            assertTrue(runtime.pending.value.isEmpty()); assertEquals(1, discoveries)
            assertEquals("timeline_unavailable", assertIs<ApiResult.Error>(runtime.start(boundRequest(43, "new-intent"), identity.scope)).error)
            assertEquals(1, bodies.size)
            assertIs<ApiResult.Success<*>>(runtime.discoverTimeline(43, "book", identity.scope))
            assertEquals("attempt_exists", assertIs<ApiResult.Error>(runtime.start(boundRequest(43, "rejected").copy(timelineId = updatedManifest.timelineId), identity.scope)).error)
            assertIs<ApiResult.Success<*>>(runtime.start(boundRequest(43, "new-intent").copy(timelineId = updatedManifest.timelineId), identity.scope))
            assertEquals(updatedManifest.timelineId, SiloJson.parseToJsonElement(bodies.last()).jsonObject["timeline_id"]?.jsonPrimitive?.content)
            assertEquals(2, discoveries); assertEquals(2, bodies.size)
            assertEquals(retained, store.entries.first())
        } finally { c.close() }
    }

    @Test fun ambiguousStatusesAndMalformedTimelineRefusalsNeverSettle() = runTest {
        val variants = listOf(
            HttpStatusCode.Conflict to timelineRefusal,
            HttpStatusCode.Conflict to """{"code":"timeline_changed"}""",
            HttpStatusCode.ServiceUnavailable to timelineRefusal,
            HttpStatusCode.OK to timelineRefusal,
            HttpStatusCode.Created to timelineRefusal.replace("\"retryable\":false", "\"retryable\":true"),
            HttpStatusCode.Created to timelineRefusal.replace(",\"retryable\":false", ""),
            HttpStatusCode.Created to timelineRefusal.replace("\"reason\":", "\"reason_code\":"),
            HttpStatusCode.Created to timelineRefusal.replace("\"outcome\":", "\"session_id\":\"unexpected\",\"outcome\":"),
            HttpStatusCode.Created to timelineRefusal.replace("\"outcome\":", "\"playback_plan\":{},\"outcome\":"),
        )
        for ((status, response) in variants) {
            val identity = Identity(); val store = Store(); var starts = 0
            val c = client { req -> when (req.url.encodedPath) {
                "/api/v2/playback/capabilities" -> reply(caps("\"sequenced_progress_v1\",\"bound_client_timeline\""))
                "/api/v2/account/me" -> reply(account)
                "/api/v2/playback/timelines/43" -> reply(SiloJson.encodeToString(manifest))
                "/api/v2/playback/start" -> { starts++; reply(response, status) }
                else -> error("Unexpected transport")
            } }
            try {
                val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
                runtime.discoverTimeline(43, "book", identity.scope)
                assertFalse(runtime.start(boundRequest(43, "original"), identity.scope) is ApiResult.Success)
                assertFalse(store.entries.single().terminal, "$status $response")
                assertNull(store.entries.single().rejectedStartDecision)
                assertEquals("playback_pending", assertIs<ApiResult.Error>(runtime.start(boundRequest(43, "replacement"), identity.scope)).error)
                assertEquals(1, starts)
            } finally { c.close() }
        }
    }

    @Test fun lostTerminalPublicationResolvesOnlyByExactReplayAfterRestart() = runTest {
        val identity = Identity(); val store = Store(); var discoveries = 0
        val bodies = mutableListOf<String>()
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps("\"sequenced_progress_v1\",\"bound_client_timeline\""))
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/timelines/43" -> { discoveries++; reply(SiloJson.encodeToString(manifest)) }
            "/api/v2/playback/start" -> {
                bodies += req.body.toByteArray().decodeToString()
                if (bodies.size == 1) throw IllegalStateException("lost terminal publication response")
                if (bodies.size == 2) reply("{}", HttpStatusCode.ServiceUnavailable)
                else reply(timelineRefusal, HttpStatusCode.Created)
            }
            else -> error("Recovery must not create a session or perform catalog lookup")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            runtime.discoverTimeline(43, "book", identity.scope)
            assertIs<ApiResult.NetworkError>(runtime.start(boundRequest(43, "original"), identity.scope))
            val retained = store.entries.single()
            val restarted = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertEquals("playback_pending", assertIs<ApiResult.Error>(restarted.start(boundRequest(43, "replacement"), identity.scope)).error)
            identity.login = "other-login"
            assertIs<ApiResult.Success<*>>(restarted.recover())
            assertEquals(1, bodies.size); assertEquals(retained, store.entries.single())
            identity.login = "login-1"
            assertEquals(503, assertIs<ApiResult.Error>(restarted.recover()).code)
            assertEquals(retained, store.entries.single())
            assertIs<ApiResult.Success<*>>(restarted.recover())
            assertEquals(List(3) { bodies.first() }, bodies)
            assertEquals(1, discoveries); assertTrue(store.entries.single().terminal)
            assertEquals(retained.start, store.entries.single().start)
            val settled = store.entries.single()
            assertIs<ApiResult.Success<*>>(SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }.recover())
            assertEquals(settled, store.entries.single()); assertEquals(3, bodies.size)
        } finally { c.close() }
    }

    @Test fun terminalRefusalCannotSettleAfterInFlightIdentityChange() = runTest {
        val identity = Identity(); val store = Store()
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps("\"sequenced_progress_v1\",\"bound_client_timeline\""))
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/timelines/43" -> reply(SiloJson.encodeToString(manifest))
            "/api/v2/playback/start" -> {
                identity.scope = identity.scope.copy(identityGeneration = 2)
                reply(timelineRefusal, HttpStatusCode.Created)
            }
            else -> error("Unexpected transport")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            runtime.discoverTimeline(43, "book", identity.scope)
            assertEquals("identity_changed", assertIs<ApiResult.Error>(runtime.start(boundRequest(43, "original"), identity.scope)).error)
            assertFalse(store.entries.single().terminal); assertNull(store.entries.single().rejectedStartDecision)
        } finally { c.close() }
    }

    @Test fun proxyAuxiliaryUsesActualRequestHeadersOnlyEphemerallyAndFencesIdentity() = runTest {
        val identity = Identity(); val store = Store()
        val wire = adoptedDecision.replace("/api/v2/stream/session-1", "https://proxy.example/stream/direct/opaque-signed-reference")
            .replace("\"decision_reason\":", "\"subtitle\":{\"mode\":\"render\",\"artifact\":{\"url\":\"https://proxy.example/stream/v3/session-1/subtitles/0.ass?file_id=42&embedded_stream_index=0\",\"mime_type\":\"text/x-ssa\",\"format\":\"ass\"}},\"decision_reason\":")
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(wire, HttpStatusCode.Created)
            "/api/v2/playback/session-1/replan" -> reply(wire)
            else -> error("Unexpected request")
        } }.config { defaultRequest { header("Authorization", "Bearer captured-request"); header("X-Profile-Id", "profile") } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            val decision = assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request(), identity.scope)).data
            val headers = assertIs<ProxyAuxiliaryRequestHeaders>(decision.playbackPlan!!.stream.effectiveRequestHeaders)
            assertEquals("Bearer captured-request", headers["Authorization"])
            assertTrue(headers.isCurrent())
            assertTrue(decision.playbackPlan!!.stream.headers.isEmpty())
            assertFalse(SiloJson.encodeToString(decision).contains("captured-request"))
            assertFalse(SiloJson.encodeToString(store.entries).contains("captured-request"))
            val replacement = assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.replan("session-1", replanRequest())).data
            val replacementHeaders = assertIs<ProxyAuxiliaryRequestHeaders>(replacement.playbackPlan!!.stream.effectiveRequestHeaders)
            assertFalse(headers.isCurrent())
            assertTrue(replacementHeaders.isCurrent())
            val repeated = assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.replan("session-1", replanRequest())).data
            assertSame(replacementHeaders, repeated.playbackPlan!!.stream.effectiveRequestHeaders)
            assertFalse(SiloJson.encodeToString(store.entries).contains("captured-request"))
            assertFalse(SiloJson.encodeToString(replacement).contains("captured-request"))
            identity.scope = identity.scope.copy(profileId = "new-profile", identityGeneration = 2)
            assertFalse(replacementHeaders.isCurrent())
            assertEquals("profile", headers["X-Profile-Id"])
        } finally { c.close() }
    }

    @Test fun unboundJournalAndCommandsDoNotGainTimelineFields() {
        val legacy = entry().copy(progress = PlaybackProgressV2(installation, 1, 30.0, false),
            stop = PlaybackStopV2(installation, stopId))
        val encoded = SiloJson.encodeToString(legacy)
        assertFalse(encoded.contains("timeline")); assertFalse(encoded.contains("manifest"))
        assertFalse(encoded.contains("acceptedBoundSample"))
        assertEquals(legacy, SiloJson.decodeFromString<PlaybackJournalEntry>(encoded))
    }

    @Test fun boundPartCannotStartUntilOldTerminalReceiptAndStopKeepsTimelineWithoutFinalSample() = runTest {
        val identity = Identity(); val store = Store(); var starts = 0; var allowStop = false
        val stops = mutableListOf<String>()
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps("\"sequenced_progress_v1\",\"bound_client_timeline\""))
            "/api/v2/playback/timelines/43" -> {
                assertEquals(installation, req.url.parameters["installation_id"])
                assertEquals(identity.scope, req.attributes[AuthScopeAttributeKey])
                reply(SiloJson.encodeToString(manifest))
            }
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> {
                starts++
                val body = SiloJson.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                assertEquals("client_bound", body.getValue("progress_persistence").jsonPrimitive.content)
                assertEquals(manifest.timelineId, body.getValue("timeline_id").jsonPrimitive.content)
                reply(boundDecision(body.getValue("file_id").jsonPrimitive.content.toInt(), "session-$starts"), HttpStatusCode.Created)
            }
            "/api/v2/playback/session-1/progress" -> {
                val sent = SiloJson.decodeFromString<PlaybackProgressV2>(req.body.toByteArray().decodeToString())
                assertEquals(30.0, sent.position); assertEquals(manifest.timelineId, sent.timelineId)
                reply(SiloJson.encodeToString(PlaybackMutationV2("applied", PlaybackSampleV2(sent.sequence, 30.0, false, manifest.timelineId, 630.0))))
            }
            "/api/v2/playback/session-1" -> {
                stops += req.body.toByteArray().decodeToString()
                val sent = SiloJson.decodeFromString<PlaybackStopV2>(stops.last())
                assertNull(sent.position); assertEquals(manifest.timelineId, sent.timelineId)
                if (!allowStop) throw IllegalStateException("lost stop")
                reply(SiloJson.encodeToString(PlaybackMutationV2("stopped", stopId = sent.stopId)))
            }
            else -> error("Unexpected transport")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            val captured = assertIs<ApiResult.Success<CapturedPlaybackManifest>>(runtime.discoverTimeline(43, "book", identity.scope)).data
            assertIs<ApiResult.Success<*>>(runtime.start(boundRequest(43, "part-two"), identity.scope))
            assertIs<ApiResult.Success<*>>(runtime.progress("session-1", 30.0, false))
            assertEquals(630.0, runtime.boundResume(captured))
            assertEquals("stop_pending", assertIs<ApiResult.Error>(runtime.stop("session-1")).error)
            assertEquals("playback_pending", assertIs<ApiResult.Error>(runtime.start(boundRequest(42, "cross-part"), identity.scope)).error)
            assertEquals(1, starts)
            allowStop = true
            assertIs<ApiResult.Success<*>>(runtime.stop("session-1"))
            assertEquals(1, stops.distinct().size)
            assertIs<ApiResult.Success<*>>(runtime.start(boundRequest(42, "cross-part"), identity.scope))
            assertEquals(2, starts)
            val restarted = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertEquals(630.0, restarted.boundResume(captured))
            identity.login = "new-login"
            assertNull(restarted.boundResume(captured))
        } finally { c.close() }
    }

    @Test fun boundLostProgressReplaysExactLocalCommandAndRejectsWrongGlobalReceipt() = runTest {
        val identity = Identity(); val store = Store(); val bodies = mutableListOf<String>()
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps("\"sequenced_progress_v1\",\"bound_client_timeline\""))
            "/api/v2/playback/timelines/43" -> reply(SiloJson.encodeToString(manifest))
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(boundDecision(43, "session-1"), HttpStatusCode.Created)
            "/api/v2/playback/session-1/progress" -> {
                bodies += req.body.toByteArray().decodeToString()
                if (bodies.size == 1) throw IllegalStateException("lost reply")
                val sent = SiloJson.decodeFromString<PlaybackProgressV2>(bodies.last())
                reply(SiloJson.encodeToString(PlaybackMutationV2("applied", PlaybackSampleV2(sent.sequence, sent.position, false, manifest.timelineId, sent.position))))
            }
            else -> error("Unexpected transport")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.discoverTimeline(43, "book", identity.scope))
            assertIs<ApiResult.Success<*>>(runtime.start(boundRequest(43, "part-two"), identity.scope))
            assertIs<ApiResult.NetworkError>(runtime.progress("session-1", 30.0, false))
            assertEquals("invalid_progress_receipt", assertIs<ApiResult.Error>(runtime.progress("session-1", 80.0, false)).error)
            assertEquals(2, bodies.size); assertEquals(bodies[0], bodies[1])
            assertEquals(30.0, store.entries.single().progress?.position)
            assertNull(store.entries.single().acceptedBoundSample)
        } finally { c.close() }
    }

    @Test fun boundFinalStopRequiresTheExactLocalSampleAndPersistsAcceptedGlobalResume() = runTest {
        val identity = Identity(); val store = Store(); var validReceipt = false; val stops = mutableListOf<String>()
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps("\"sequenced_progress_v1\",\"bound_client_timeline\""))
            "/api/v2/playback/timelines/43" -> reply(SiloJson.encodeToString(manifest))
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(boundDecision(43, "session-1"), HttpStatusCode.Created)
            "/api/v2/playback/session-1/progress" -> throw IllegalStateException("lost progress")
            "/api/v2/playback/session-1" -> {
                stops += req.body.toByteArray().decodeToString()
                val sent = SiloJson.decodeFromString<PlaybackStopV2>(stops.last())
                assertEquals(30.0, sent.position); assertEquals(manifest.timelineId, sent.timelineId)
                val local = if (validReceipt) 30.0 else 31.0
                reply(SiloJson.encodeToString(PlaybackMutationV2("stopped",
                    PlaybackSampleV2(requireNotNull(sent.sequence), local, false, manifest.timelineId, 600.0 + local), sent.stopId)))
            }
            else -> error("Unexpected transport")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            val captured = assertIs<ApiResult.Success<CapturedPlaybackManifest>>(runtime.discoverTimeline(43, "book", identity.scope)).data
            assertIs<ApiResult.Success<*>>(runtime.start(boundRequest(43, "part-two"), identity.scope))
            assertIs<ApiResult.NetworkError>(runtime.progress("session-1", 30.0, false))
            assertEquals("invalid_stop_receipt", assertIs<ApiResult.Error>(runtime.stop("session-1")).error)
            assertFalse(store.entries.single().terminal)
            assertNull(runtime.boundResume(captured))
            validReceipt = true
            assertIs<ApiResult.Success<*>>(runtime.stop("session-1"))
            assertEquals(stops[0], stops[1])
            assertTrue(store.entries.single().terminal)
            assertEquals(630.0, runtime.boundResume(captured))
        } finally { c.close() }
    }

    @Test fun boundStartConflictNeverRefreshesManifestOrRebasesTheAttempt() = runTest {
        val identity = Identity(); val store = Store(); var starts = 0; var discoveries = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps("\"sequenced_progress_v1\",\"bound_client_timeline\""))
            "/api/v2/playback/timelines/43" -> { discoveries++; reply(SiloJson.encodeToString(manifest)) }
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> { starts++; reply("{}", HttpStatusCode.Conflict) }
            else -> error("Unexpected transport")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.discoverTimeline(43, "book", identity.scope))
            assertEquals(409, assertIs<ApiResult.Error>(runtime.start(boundRequest(43, "part-two"), identity.scope)).code)
            val retained = store.entries.single()
            assertIs<ApiResult.Error>(runtime.start(boundRequest(42, "fresh-attempt"), identity.scope))
            assertEquals(1, starts); assertEquals(1, discoveries); assertEquals(retained, store.entries.single())
        } finally { c.close() }
    }

    @Test fun recoveryRetriesDrainingWithExactStopAndNoAutoplay() = runTest {
        val store = Store().apply { entries = listOf(entry()) }
        val deletes = mutableListOf<String>()
        val c = client { req ->
            when (req.url.encodedPath) {
                "/api/v2/playback/capabilities" -> reply(caps())
                "/api/v2/account/me" -> reply(account)
                "/api/v2/playback/session-1" -> {
                    assertEquals(HttpMethod.Delete, req.method)
                    assertTrue(req.attributes[SingleAttemptAttributeKey])
                    deletes += req.body.toByteArray().decodeToString()
                    assertEquals(store.entries.single().stop, SiloJson.decodeFromString<PlaybackStopV2>(deletes.last()))
                    reply("""{"outcome":"${if (deletes.size == 1) "draining" else "stopped"}","stop_id":"$stopId"}""",
                        if (deletes.size == 1) HttpStatusCode.Accepted else HttpStatusCode.OK)
                }
                else -> error("Unexpected request ${req.url}")
            }
        }
        try {
            val identity = Identity()
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<Unit>>(runtime.recover())
            assertEquals(2, deletes.size); assertEquals(deletes.first(), deletes.last())
            assertTrue(store.entries.single().terminal)
        } finally { c.close() }
    }

    @Test fun unavailableStopSurvivesRestartAndIdentityReplacementCannotReplay() = runTest {
        val store = Store().apply { entries = listOf(entry()) }
        val identity = Identity()
        var deletes = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            else -> { deletes++; reply("""{"code":"authority_unavailable","detail":"pending"}""", HttpStatusCode.ServiceUnavailable) }
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertEquals("stop_pending", assertIs<ApiResult.Error>(runtime.recover()).error)
            assertEquals(3, deletes); assertFalse(store.entries.single().terminal)
            val body = store.entries.single().stop
            identity.login = "login-2"
            val restarted = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { error("Must retain StopID") }
            restarted.recover()
            assertEquals(3, deletes); assertEquals(body, store.entries.single().stop)
        } finally { c.close() }
    }

    @Test fun absentFeatureReportsUnavailableAndProbeErrorsNeverDowngrade() = runTest {
        val identity = Identity().apply { temporary = true }
        val store = Store()
        var status = HttpStatusCode.OK
        val c = client { reply(if (status == HttpStatusCode.OK) caps("") else "{}", status) }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertEquals("playback_unavailable", assertIs<ApiResult.Error>(runtime.start(request())).error)
            status = HttpStatusCode.Unauthorized
            assertEquals(401, assertIs<ApiResult.Error>(runtime.start(request())).code)
            assertTrue(store.entries.isEmpty())
        } finally { c.close() }
    }

    @Test fun uncertainStartIsPersistedBeforeDispatchAndBlocksAnotherAttempt() = runTest {
        val identity = Identity(); val store = Store(); var starts = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            else -> {
                starts++
                assertEquals(store.entries.single().start.toString(), req.body.toByteArray().decodeToString())
                assertTrue(store.entries.single().start["file_id"]!!.jsonPrimitive.isString)
                assertTrue(req.attributes[SingleAttemptAttributeKey])
                throw IllegalStateException("lost reply")
            }
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertIs<ApiResult.NetworkError>(runtime.start(request()))
            assertEquals("playback_pending", assertIs<ApiResult.Error>(runtime.start(request().copy(playbackAttemptId = "attempt-2"))).error)
            assertEquals(1, starts); assertEquals(listOf("attempt-1"), runtime.pending.value)
        } finally { c.close() }
    }

    @Test fun mismatchedAndWrongStatusStopReceiptsNeverConfirmCompletion() = runTest {
        for ((status, body) in listOf(
            HttpStatusCode.Accepted to """{"outcome":"stopped","stop_id":"$stopId"}""",
            HttpStatusCode.OK to """{"outcome":"draining","stop_id":"$stopId"}""",
            HttpStatusCode.OK to """{"outcome":"stopped","stop_id":"wrong"}""",
        )) {
            val c = client { reply(body, status) }
            try { assertIs<ApiResult.Error>(PlaybackV2Api(c).stop(Identity().scope, "session-1", PlaybackStopV2(installation, stopId))) }
            finally { c.close() }
        }
    }
    @Test fun lostProgressReplyRetriesExactSampleThenBackwardPositionUsesHigherSequence() = runTest {
        val identity = Identity(); val store = Store(); val samples = mutableListOf<PlaybackProgressV2>()
        val decision = """{"protocol_version":3,"server_features":["playback_plan_v3","neutral_playback_v3_contract_v1","sequenced_progress_v1"],"outcome":"playable","session_id":"session-1","playback_plan":{"plan_id":"plan","plan_attempt_key":"key","session_id":"session-1","delivery":"original_http","stream":{"url":"/api/v2/stream/session-1","protocol":"http_progressive"},"decision_reason":"direct","requested_media_file_id":"42","effective_media_file_id":"42","source":{"media_file_id":"42"}}}"""
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(decision, HttpStatusCode.Created)
            "/api/v2/playback/session-1/progress" -> {
                val sample = SiloJson.decodeFromString<PlaybackProgressV2>(req.body.toByteArray().decodeToString())
                assertEquals(sample, store.entries.single().progress)
                samples += sample
                if (samples.size == 1) throw IllegalStateException("lost progress response")
                reply("""{"outcome":"applied","accepted":{"sequence":${sample.sequence},"position":${sample.position},"is_paused":${sample.isPaused}}}""")
            }
            else -> error("Unexpected route")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            val start = assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request()))
            assertEquals(42, start.data.playbackPlan?.effectiveMediaFileId)
            assertIs<ApiResult.NetworkError>(runtime.progress("session-1", 120.0, false))
            assertIs<ApiResult.Success<Unit>>(runtime.progress("session-1", 30.0, true))
            assertEquals(3, samples.size)
            assertEquals(samples[0], samples[1]); assertEquals(2, samples[2].sequence)
            assertEquals(30.0, samples[2].position)
            identity.scope = identity.scope.copy(identityGeneration = 2, profileId = "other")
            assertEquals("identity_changed", assertIs<ApiResult.Error>(runtime.progress("session-1", 40.0, false)).error)
            assertEquals(3, samples.size)
        } finally { c.close() }
    }

    @Test fun failedJournalWritePreventsStartDispatch() = runTest {
        val identity = Identity(); val store = Store().apply { fail = true }; var starts = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            else -> { starts++; error("Must not dispatch") }
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            val repository = PlaybackRepository(runtime)
            assertEquals("playback_storage", assertIs<ApiResult.Error>(repository.startPlaybackV3(request())).error)
            assertEquals(0, starts)
        } finally { c.close() }
    }

    @Test fun validationRejectionRetainsExactAttemptWithoutLegacyFallback() = runTest {
        val identity = Identity(); val store = Store(); var starts = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> { starts++; reply("""{"code":"validation_failed"}""", HttpStatusCode.UnprocessableEntity) }
            else -> error("No fallback allowed")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            val repository = PlaybackRepository(runtime)
            assertEquals(422, assertIs<ApiResult.Error>(repository.startPlaybackV3(request())).code)
            assertEquals("playback_pending", assertIs<ApiResult.Error>(repository.startPlaybackV3(request())).error)
            assertEquals(1, starts); assertEquals(request().v2Body(installation), store.entries.single().start)
        } finally { c.close() }
    }

    @Test fun recoveryReplayStaysPendingWhenFirstStopJournalWriteFails() = runTest {
        val identity = Identity()
        val store = Store().apply { entries = listOf(entry().copy(sessionId = null)); failStop = true }
        var starts = 0; var deletes = 0
        val decision = """{"protocol_version":3,"server_features":["playback_plan_v3","neutral_playback_v3_contract_v1","sequenced_progress_v1"],"outcome":"playable","session_id":"session-1","playback_plan":{"plan_id":"plan","session_id":"session-1","delivery":"original_http","stream":{"url":"/api/v2/stream/session-1","protocol":"http_progressive"},"decision_reason":"direct"}}"""
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> { starts++; reply(decision, HttpStatusCode.Created) }
            "/api/v2/playback/session-1" -> {
                deletes++
                assertEquals(store.entries.single().stop, SiloJson.decodeFromString<PlaybackStopV2>(req.body.toByteArray().decodeToString()))
                reply("""{"outcome":"stopped","stop_id":"$stopId"}""")
            }
            else -> error("Unexpected request")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            val repository = PlaybackRepository(runtime)
            assertEquals("playback_storage", assertIs<ApiResult.Error>(repository.recoverPlayback()).error)
            assertEquals(1, starts); assertEquals(0, deletes)
            assertEquals(listOf("attempt-1"), runtime.pending.value)
            assertEquals("session-1", store.entries.single().sessionId)
            assertEquals("playback_pending", assertIs<ApiResult.Error>(runtime.start(request().copy(playbackAttemptId = "another"))).error)
            store.failStop = false
            assertIs<ApiResult.Success<Unit>>(repository.recoverPlayback())
            assertEquals(1, starts); assertEquals(1, deletes)
            assertTrue(store.entries.single().terminal); assertTrue(runtime.pending.value.isEmpty())
        } finally { c.close() }
    }

    private fun replanRequest() = PlaybackReplanRequestV3(
        clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3, operation = "seek_reanchor",
        playbackAttemptId = "attempt-1", replanRequestId = "replan-0001", failedPlanId = "plan-0001",
        planAttemptId = "plan-attempt-0001", planAttemptKey = "plan-key-0001", attemptedPlanKeys = emptyList(),
        attemptCount = 1, positionSeconds = 42.0, selectedTracks = SelectedPlaybackTracksV3(),
        capabilities = ClientCodecCapabilities(), clientPlaybackContext = request().clientPlaybackContext,
    )
    private val adoptedDecision = """{"protocol_version":3,"server_features":["playback_plan_v3","neutral_playback_v3_contract_v1","sequenced_progress_v1"],"outcome":"playable","session_id":"session-1","playback_plan":{"plan_id":"plan-0001","plan_attempt_key":"plan-key-0001","session_id":"session-1","delivery":"original_http","stream":{"url":"/api/v2/stream/session-1","protocol":"http_progressive"},"decision_reason":"direct","requested_media_file_id":"42","effective_media_file_id":"42","source":{"media_file_id":"42"}}}"""

    @Test fun uncertainReplanIsDurableAndCannotReplayOrRebase() = runTest {
        val identity = Identity(); val store = Store(); var replans = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(adoptedDecision, HttpStatusCode.Created)
            "/api/v2/playback/session-1/replan" -> {
                replans++
                assertTrue(req.attributes[SingleAttemptAttributeKey])
                assertEquals(store.entries.single().replans.single().body.toString(), req.body.toByteArray().decodeToString())
                throw IllegalStateException("lost response")
            }
            else -> error("Unexpected transport")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.start(request()))
            assertIs<ApiResult.NetworkError>(runtime.replan("session-1", replanRequest()))
            assertEquals("replan_pending", assertIs<ApiResult.Error>(runtime.replan("session-1", replanRequest())).error)
            assertEquals("replan_conflict", assertIs<ApiResult.Error>(runtime.replan("session-1", replanRequest().copy(positionSeconds = 9.0))).error)
            assertEquals("replan_pending", assertIs<ApiResult.Error>(runtime.replan("session-1", replanRequest().copy(replanRequestId = "replan-0002"))).error)
            val restarted = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertEquals("identity_changed", assertIs<ApiResult.Error>(restarted.replan("session-1", replanRequest())).error)
            assertEquals(1, replans)
            assertNull(store.entries.single().replans.single().response)
        } finally { c.close() }
    }

    @Test fun settledReanchorsAndUnsupportedRequestsDoNotExhaustAdmission() = runTest {
        val identity = Identity(); val store = Store(); var replans = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(adoptedDecision, HttpStatusCode.Created)
            "/api/v2/playback/session-1/replan" -> {
                replans++
                assertTrue(req.attributes[SingleAttemptAttributeKey])
                assertEquals(store.entries.single().replans.last().body.toString(), req.body.toByteArray().decodeToString())
                when (replans) {
                    5 -> reply("""{"code":"unsupported","detail":"Replan is unavailable."}""", HttpStatusCode.NotImplemented)
                    13 -> throw IllegalStateException("lost response")
                    else -> reply(adoptedDecision)
                }
            }
            else -> error("Unexpected transport")
        } }
        fun reanchor(index: Int) = replanRequest().copy(replanRequestId = "reanchor-$index", positionSeconds = index.toDouble())
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.start(request()))
            for (index in 1..12) {
                val result = runtime.replan("session-1", reanchor(index))
                if (index == 5) assertEquals(501, assertIs<ApiResult.Error>(result).code)
                else assertIs<ApiResult.Success<*>>(result)
            }
            assertEquals(12, replans)
            assertEquals(12, store.entries.single().replans.size)
            assertEquals(501, assertIs<ApiResult.Error>(runtime.replan("session-1", reanchor(5))).code)
            assertEquals("stale_replan", assertIs<ApiResult.Error>(runtime.replan("session-1", reanchor(1))).error)
            assertIs<ApiResult.Success<*>>(runtime.replan("session-1", reanchor(12)))
            assertEquals(12, replans)

            assertIs<ApiResult.NetworkError>(runtime.replan("session-1", reanchor(13)))
            val retained = store.entries.single()
            assertNull(retained.replans.last().response)
            assertNull(retained.replans.last().rejectedCode)
            assertEquals("replan_pending", assertIs<ApiResult.Error>(runtime.replan("session-1", reanchor(13))).error)
            assertEquals("replan_pending", assertIs<ApiResult.Error>(runtime.replan("session-1", reanchor(14))).error)
            assertEquals("replan_conflict", assertIs<ApiResult.Error>(runtime.replan("session-1", reanchor(13).copy(positionSeconds = 99.0))).error)
            val restarted = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertEquals("identity_changed", assertIs<ApiResult.Error>(restarted.replan("session-1", reanchor(14))).error)
            identity.scope = identity.scope.copy(identityGeneration = 2)
            assertEquals("identity_changed", assertIs<ApiResult.Error>(runtime.replan("session-1", reanchor(14))).error)
            assertEquals(13, replans)
            assertEquals(retained, store.entries.single())
        } finally { c.close() }
    }

    @Test fun replanCachesExactDecisionAndRejectsNewBodyAndChangedOwner() = runTest {
        val identity = Identity(); val store = Store(); var replans = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(adoptedDecision, HttpStatusCode.Created)
            else -> { replans++; reply(adoptedDecision) }
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.start(request()))
            assertIs<ApiResult.Success<*>>(runtime.replan("session-1", replanRequest()))
            assertIs<ApiResult.Success<*>>(runtime.replan("session-1", replanRequest()))
            assertEquals(1, replans)
            assertEquals("replan_conflict", assertIs<ApiResult.Error>(runtime.replan("session-1", replanRequest().copy(positionSeconds = 7.0))).error)
            identity.scope = identity.scope.copy(identityGeneration = 2)
            assertNull(runtime.controlOwner("session-1"))
            assertEquals("identity_changed", assertIs<ApiResult.Error>(runtime.replan("session-1", replanRequest())).error)
            assertEquals(1, replans)
        } finally { c.close() }
    }

    @Test fun routeEventPersistsBeforeSendAndRejectsWrongSessionAndReceipt() = runTest {
        val identity = Identity(); val store = Store(); var events = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(adoptedDecision, HttpStatusCode.Created)
            "/api/v2/playback/route-events" -> {
                events++
                assertTrue(req.attributes[SingleAttemptAttributeKey])
                assertEquals(store.entries.single().routeEvents.single().toString(), req.body.toByteArray().decodeToString())
                reply("""{"event_id":"wrong","outcome":"accepted"}""", HttpStatusCode.Accepted)
            }
            else -> error("Unexpected transport")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.start(request()))
            val event = PlaybackRouteEventV3(playbackAttemptId = "attempt-1", sessionId = "session-1", event = "first_frame")
            assertEquals("identity_changed", assertIs<ApiResult.Error>(runtime.routeEvent(event.copy(sessionId = "wrong"))).error)
            assertEquals(0, events)
            assertEquals("invalid_receipt", assertIs<ApiResult.Error>(runtime.routeEvent(event)).error)
            assertEquals(1, store.entries.single().routeEvents.size)
            identity.scope = identity.scope.copy(identityGeneration = 2)
            assertEquals("identity_changed", assertIs<ApiResult.Error>(runtime.routeEvent(event)).error)
            assertEquals(1, events)
        } finally { c.close() }
    }

    @Test fun v2DecisionCannotSelectImplicitLegacyStreamMount() {
        val body = SiloJson.parseToJsonElement(adoptedDecision.replace("/api/v2/stream/", "/stream/")).jsonObject
        assertFailsWith<IllegalArgumentException> { decodePlaybackDecisionV2(body) }
    }

}
