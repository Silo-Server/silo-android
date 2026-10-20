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
import org.siloserver.silo.network.localroute.LocalRouteHeaders
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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
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

    @Test fun admissionTakesTheLocalRouteOnlyWhenItsMediaMay() = runTest {
        val marks = mutableListOf<String?>()
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> {
                marks += req.headers[LocalRouteHeaders.SAVED_ADDRESS]
                reply(decision("session-${marks.size}"), HttpStatusCode.Created)
            }
            "/api/v2/playback/session-1" -> reply("""{"outcome":"stopped","stop_id":"$stopId"}""")
            else -> error("Unexpected request ${req.url}")
        } }
        try {
            val identity = Identity()
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, Store()) { stopId }
            // The server caps a session for the network its admission arrived through.
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request()))
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(
                runtime.start(request().copy(playbackAttemptId = "attempt-2"), allowsLocalRoute = true))
            assertEquals(listOf("1", null), marks)
        } finally { c.close() }
    }

    @Test fun laterStartsRetryALostStopWithExactBody() = runTest {
        val store = Store().apply { entries = listOf(entry()) }
        val deletes = mutableListOf<String>()
        var starts = 0
        val c = client { req ->
            when (req.url.encodedPath) {
                "/api/v2/playback/capabilities" -> reply(caps())
                "/api/v2/account/me" -> reply(account)
                "/api/v2/playback/start" -> { starts++; reply(decision("session-${starts + 1}"), HttpStatusCode.Created) }
                "/api/v2/playback/session-1" -> {
                    assertEquals(HttpMethod.Delete, req.method)
                    assertTrue(req.attributes[SingleAttemptAttributeKey])
                    deletes += req.body.toByteArray().decodeToString()
                    assertEquals(store.entries.single { it.attemptId == "attempt-1" }.stop,
                        SiloJson.decodeFromString<PlaybackStopV2>(deletes.last()))
                    if (deletes.size == 1) throw IllegalStateException("lost stop reply")
                    reply("""{"outcome":"stopped","stop_id":"$stopId"}""")
                }
                else -> error("Unexpected request ${req.url}")
            }
        }
        try {
            val identity = Identity()
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            // One attempt per start: the lost reply leaves the stop for the next start.
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request().copy(playbackAttemptId = "attempt-2")))
            assertEquals(1, deletes.size); assertFalse(store.entries.single { it.attemptId == "attempt-1" }.terminal)
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request().copy(playbackAttemptId = "attempt-3")))
            assertEquals(2, deletes.size); assertEquals(deletes.first(), deletes.last())
            assertTrue(store.entries.single { it.attemptId == "attempt-1" }.terminal)
        } finally { c.close() }
    }

    @Test fun unavailableStopNeverBlocksStartAndIdentityReplacementCannotReplay() = runTest {
        val store = Store().apply { entries = listOf(entry(), entry().copy(attemptId = "attempt-0", sessionId = "session-0")) }
        val identity = Identity()
        var deletes = 0; var starts = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> { starts++; reply(decision("session-${starts + 1}"), HttpStatusCode.Created) }
            else -> { deletes++; reply("""{"code":"authority_unavailable","detail":"pending"}""", HttpStatusCode.ServiceUnavailable) }
        } }
        fun earlier() = store.entries.single { it.attemptId == "attempt-1" }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            // The first failed stop ends the pass: one request, and the second stop waits.
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request().copy(playbackAttemptId = "attempt-2")))
            assertEquals(1, deletes); assertFalse(earlier().terminal)
            assertNull(store.entries.single { it.attemptId == "attempt-0" }.stop)
            val body = earlier().stop
            identity.login = "login-2"
            val restarted = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { error("Must retain StopID") }
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(restarted.start(request().copy(playbackAttemptId = "attempt-3")))
            assertEquals(1, deletes); assertEquals(body, earlier().stop); assertEquals(2, starts)
        } finally { c.close() }
    }

    @Test fun absentFeatureReportsUnavailableAndProbeErrorsNeverDowngrade() = runTest {
        val identity = Identity().apply { temporary = true }
        val store = Store()
        var status = HttpStatusCode.OK
        val c = client { reply(if (status == HttpStatusCode.OK) caps("") else "{}", status) }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            assertEquals("playback_unavailable", assertIs<ApiResult.Error>(runtime.start(request())).error)
            status = HttpStatusCode.Unauthorized
            assertEquals(401, assertIs<ApiResult.Error>(runtime.start(request())).code)
            assertTrue(store.entries.isEmpty())
        } finally { c.close() }
    }

    @Test fun uncertainStartIsPersistedBeforeDispatchAndDroppedByTheNextAttempt() = runTest {
        val identity = Identity(); val store = Store(); var starts = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            else -> {
                starts++
                assertEquals(store.entries.last().start.toString(), req.body.toByteArray().decodeToString())
                assertTrue(store.entries.last().start["file_id"]!!.jsonPrimitive.isString)
                assertTrue(req.attributes[SingleAttemptAttributeKey])
                if (starts == 1) throw IllegalStateException("lost reply")
                reply(decision("session-2"), HttpStatusCode.Created)
            }
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            assertIs<ApiResult.NetworkError>(runtime.start(request()))
            // The lost start is never replayed: the server expires whatever it may have opened.
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request().copy(playbackAttemptId = "attempt-2")))
            assertEquals(2, starts); assertTrue(store.entries.single { it.attemptId == "attempt-1" }.terminal)
        } finally { c.close() }
    }

    @Test fun mismatchedAndWrongStatusStopReceiptsNeverConfirmCompletion() = runTest {
        for ((status, body) in listOf(
            HttpStatusCode.Accepted to """{"outcome":"stopped","stop_id":"$stopId"}""",
            HttpStatusCode.OK to """{"outcome":"applied","stop_id":"$stopId"}""",
            HttpStatusCode.OK to """{"outcome":"stopped","stop_id":"wrong"}""",
        )) {
            val c = client { reply(body, status) }
            try { assertFalse(PlaybackV2Api(c, ApiV2Gate.Unrestricted).stop(Identity().scope, "session-1", PlaybackStopV2(installation, stopId)) is ApiResult.Success, "$status $body") }
            finally { c.close() }
        }
        // A replayed receipt carries the winning stop's id (an earlier client stop or the server's expiry stop).
        val replayed = client { reply("""{"outcome":"replayed","stop_id":"11111111-1111-4111-8111-111111111111"}""") }
        try { assertIs<ApiResult.Success<PlaybackMutationV2>>(replayed.let { PlaybackV2Api(it, ApiV2Gate.Unrestricted).stop(Identity().scope, "session-1", PlaybackStopV2(installation, stopId)) }) }
        finally { replayed.close() }
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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            val repository = PlaybackRepository(runtime)
            assertEquals("playback_storage", assertIs<ApiResult.Error>(repository.startPlaybackV3(request())).error)
            assertEquals(0, starts)
        } finally { c.close() }
    }

    @Test fun terminalDecisionWithoutSessionSettlesTheAttemptAndSurfacesTheReason() = runTest {
        val identity = Identity(); val store = Store(); var starts = 0
        val terminal = """{"protocol_version":3,"server_features":["playback_plan_v3"],"outcome":"adaptation_unavailable","terminal":{"reason":"subtitle_unavailable_in_version","message":"The selected subtitle track is unavailable in the fallback media version.","retryable":false}}"""
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> { starts++; reply(terminal, HttpStatusCode.Created) }
            else -> error("Unexpected request ${req.url.encodedPath}")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            val first = assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request()))
            assertEquals("subtitle_unavailable_in_version", first.data.terminal?.reason)
            assertTrue(store.entries.single().terminal)
            // The refused attempt never fences the next start; a fresh attempt goes straight to the server.
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request().copy(playbackAttemptId = "attempt-2")))
            assertEquals(2, starts)
        } finally { c.close() }
    }

    @Test fun validationRejectionNeitherFallsBackNorBlocksTheNextAttempt() = runTest {
        val identity = Identity(); val store = Store(); var starts = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> { starts++; reply("""{"code":"validation_failed"}""", HttpStatusCode.UnprocessableEntity) }
            else -> error("No fallback allowed")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            val repository = PlaybackRepository(runtime)
            assertEquals(422, assertIs<ApiResult.Error>(repository.startPlaybackV3(request())).code)
            assertEquals(request().v2Body(installation), store.entries.single().start)
            // The refused attempt is dropped, not replayed; the next one goes straight to the server.
            assertEquals(422, assertIs<ApiResult.Error>(repository.startPlaybackV3(request().copy(playbackAttemptId = "attempt-2"))).code)
            assertEquals(2, starts)
        } finally { c.close() }
    }

    @Test fun stopThatCannotBeJournaledIsRetriedByALaterStart() = runTest {
        val identity = Identity()
        val store = Store().apply { entries = listOf(entry()); failStop = true }
        var starts = 0; var deletes = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> { starts++; reply(decision("session-${starts + 1}"), HttpStatusCode.Created) }
            "/api/v2/playback/session-1" -> {
                deletes++
                assertEquals(store.entries.single { it.attemptId == "attempt-1" }.stop,
                    SiloJson.decodeFromString<PlaybackStopV2>(req.body.toByteArray().decodeToString()))
                reply("""{"outcome":"stopped","stop_id":"$stopId"}""")
            }
            else -> error("Unexpected request")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            // The stop cannot be journaled, so it is never sent; the new start still goes ahead.
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request().copy(playbackAttemptId = "attempt-2")))
            assertEquals(1, starts); assertEquals(0, deletes)
            assertFalse(store.entries.single { it.attemptId == "attempt-1" }.terminal)
            store.failStop = false
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request().copy(playbackAttemptId = "attempt-3")))
            assertEquals(2, starts); assertEquals(1, deletes)
            assertTrue(store.entries.single { it.attemptId == "attempt-1" }.terminal)
        } finally { c.close() }
    }

    private fun replanRequest() = PlaybackReplanRequestV3(
        clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3, operation = "seek_reanchor",
        playbackAttemptId = "attempt-1", replanRequestId = "replan-0001", failedPlanId = "plan-0001",
        planAttemptId = "plan-attempt-0001", planAttemptKey = "plan-key-0001", attemptedPlanKeys = emptyList(),
        attemptCount = 1, positionSeconds = 42.0, selectedTracks = SelectedPlaybackTracksV3(),
        capabilities = ClientCodecCapabilities(), clientPlaybackContext = request().clientPlaybackContext,
    )
    private fun decision(session: String) = adoptedDecision.replace("session-1", session)
    private val adoptedDecision = """{"protocol_version":3,"server_features":["playback_plan_v3","neutral_playback_v3_contract_v1","sequenced_progress_v1"],"outcome":"playable","session_id":"session-1","playback_plan":{"plan_id":"plan-0001","plan_attempt_key":"plan-key-0001","session_id":"session-1","delivery":"original_http","stream":{"url":"/api/v2/stream/session-1","protocol":"http_progressive"},"decision_reason":"direct","requested_media_file_id":"42","effective_media_file_id":"42","source":{"media_file_id":"42"}}}"""

    /** Start and replan bodies as the wire saw them, for [capabilityFeatures] and [decisionFeatures]. */
    private suspend fun bufferingQualityBodies(capabilityFeatures: String, decisionFeatures: String): Triple<JsonObject, JsonObject, Set<String>> {
        val identity = Identity(); val bodies = mutableListOf<JsonObject>()
        val wire = adoptedDecision.replace("\"sequenced_progress_v1\"]", "\"sequenced_progress_v1\"$decisionFeatures]")
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps("\"sequenced_progress_v1\"$capabilityFeatures"))
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> { bodies += SiloJson.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject; reply(wire, HttpStatusCode.Created) }
            "/api/v2/playback/session-1/replan" -> { bodies += SiloJson.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject; reply(wire) }
            else -> error("Unexpected request ${req.url}")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, Store()) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.start(request().copy(learnedLimit = LEARNED_LIMIT_IGNORE)))
            assertIs<ApiResult.Success<*>>(runtime.replan("session-1", replanRequest().copy(
                operation = QUALITY_CHANGE_V3_OPERATION, bandwidthEstimateKbps = 44100,
                bandwidthEstimateSource = BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER, learnedLimit = LEARNED_LIMIT_CLEAR)))
            return Triple(bodies[0], bodies[1], runtime.serverFeatures("attempt-1"))
        } finally { c.close() }
    }
    private fun JsonObject.features() = getValue("client_features").jsonArray.map { it.jsonPrimitive.content }

    @Test fun bufferingQualityFieldsReachOnlyAServerThatAdvertisesTheFeature() = runTest {
        // An older server rejects unknown fields: strip them, keep the existing estimate field.
        val (oldStart, oldReplan, _) = bufferingQualityBodies(capabilityFeatures = "", decisionFeatures = "")
        assertNull(oldStart["learned_limit"]); assertFalse(BUFFERING_QUALITY_V1_FEATURE in oldStart.features())
        assertEquals(JsonPrimitive(44100), oldReplan["bandwidth_estimate_kbps"])
        assertNull(oldReplan["bandwidth_estimate_source"]); assertNull(oldReplan["learned_limit"])
        assertFalse(BUFFERING_QUALITY_V1_FEATURE in oldReplan.features())

        val bq = ",\"$BUFFERING_QUALITY_V1_FEATURE\""
        val (start, replan, features) = bufferingQualityBodies(capabilityFeatures = bq, decisionFeatures = "")
        assertEquals(JsonPrimitive(LEARNED_LIMIT_IGNORE), start["learned_limit"])
        assertTrue(BUFFERING_QUALITY_V1_FEATURE in start.features())
        assertEquals(JsonPrimitive(BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER), replan["bandwidth_estimate_source"])
        assertEquals(JsonPrimitive(LEARNED_LIMIT_CLEAR), replan["learned_limit"])
        assertTrue(BUFFERING_QUALITY_V1_FEATURE in replan.features())
        assertTrue(BUFFERING_QUALITY_V1_FEATURE in features)

        // A start only knows the capability; its decision's server_features also count for replans.
        val (lateStart, lateReplan, _) = bufferingQualityBodies(capabilityFeatures = "", decisionFeatures = bq)
        assertNull(lateStart["learned_limit"])
        assertEquals(JsonPrimitive(LEARNED_LIMIT_CLEAR), lateReplan["learned_limit"])
        assertTrue(BUFFERING_QUALITY_V1_FEATURE in lateReplan.features())
    }

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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.start(request()))
            assertIs<ApiResult.NetworkError>(runtime.replan("session-1", replanRequest()))
            assertEquals("replan_pending", assertIs<ApiResult.Error>(runtime.replan("session-1", replanRequest())).error)
            assertEquals("replan_conflict", assertIs<ApiResult.Error>(runtime.replan("session-1", replanRequest().copy(positionSeconds = 9.0))).error)
            assertEquals("replan_pending", assertIs<ApiResult.Error>(runtime.replan("session-1", replanRequest().copy(replanRequestId = "replan-0002"))).error)
            val restarted = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
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
            val restarted = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.start(request()))
            val event = PlaybackRouteEventV3(playbackAttemptId = "attempt-1", sessionId = "session-1", event = "first_frame")
            assertEquals("identity_changed", assertIs<ApiResult.Error>(runtime.routeEvent(event.copy(sessionId = "wrong"))).error)
            assertEquals(0, events)
            assertEquals("invalid_response", assertIs<ApiResult.Error>(runtime.routeEvent(event)).error)
            assertEquals(1, store.entries.single().routeEvents.size)
            identity.scope = identity.scope.copy(identityGeneration = 2)
            assertEquals("identity_changed", assertIs<ApiResult.Error>(runtime.routeEvent(event)).error)
            assertEquals(1, events)
        } finally { c.close() }
    }

    @Test fun settledAttemptsAreCompactedSoTheJournalStaysBounded() = runTest {
        val identity = Identity()
        // Twenty finished playbacks, each still carrying a replan decision. The
        // whole journal is rewritten on every progress sample, so retaining
        // these makes each write larger than the last, without end.
        val settled = (1..20).map {
            entry().copy(attemptId = "old-$it", sessionId = "old-session-$it", terminal = true,
                replans = listOf(PlaybackReplanIntent(replanRequest().v2Body(installation),
                    response = SiloJson.parseToJsonElement(adoptedDecision).jsonObject)))
        }
        val store = Store().apply { entries = settled }
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(adoptedDecision, HttpStatusCode.Created)
            else -> error("Unexpected request ${req.url}")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.start(request()))

            val live = store.entries.single { !it.terminal }
            assertEquals("attempt-1", live.attemptId)
            // Only the newest settled attempts survive, and as tombstones.
            val tombstones = store.entries.filter { it.terminal }
            assertEquals(8, tombstones.size)
            assertEquals((13..20).map { "old-$it" }, tombstones.map { it.attemptId })
            assertTrue(tombstones.all { it.replans.isEmpty() && it.progress == null && it.stop == null })
            // A tombstone still answers ownership for the session it settled,
            // which the player consults right after a stop.
            assertTrue(runtime.owns("old-session-20"))
        } finally { c.close() }
    }

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun summaryEvent(played: Int) = PlaybackRouteEventV3(playbackAttemptId = "attempt-1", sessionId = "session-1",
        event = "plan_summary", diagnostics = mapOf("played_ms" to "$played"))
    private val summaryId = "33333333-3333-4333-8333-333333333333"
    private fun JsonObject.eventId() = getValue("event_id").jsonPrimitive.content
    private fun JsonObject.played() = getValue("diagnostics").jsonObject.getValue("played_ms").jsonPrimitive.content

    /** An attempt an earlier process left with route events the server never accepted, journaled at those times. */
    private fun unsent(attemptId: String, vararg events: Pair<String, Long?>) = entry().copy(
        attemptId = attemptId, sessionId = "session-$attemptId",
        routeEvents = events.map { (id, _) ->
            PlaybackRouteEventV3(playbackAttemptId = attemptId, sessionId = "session-$attemptId", event = "plan_summary")
                .v2Body(installation, id)
        },
        routeEventTimes = events.mapNotNull { (id, at) -> at?.let { id to it } }.toMap(),
    )

    /** Records each route event and answers with [outcomes] in turn (null: the network fails), then accepts. */
    private fun routeEventServer(sent: MutableList<JsonObject>, outcomes: ArrayDeque<HttpStatusCode?> = ArrayDeque()) =
        client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(adoptedDecision, HttpStatusCode.Created)
            "/api/v2/playback/session-1" -> reply("""{"outcome":"stopped","stop_id":"$stopId"}""")
            "/api/v2/playback/route-events" -> {
                val body = SiloJson.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                sent += body
                val status = if (outcomes.isEmpty()) HttpStatusCode.Accepted
                    else outcomes.removeFirst() ?: throw IllegalStateException("offline")
                if (status == HttpStatusCode.Accepted) reply("""{"event_id":"${body.eventId()}","outcome":"accepted"}""", status)
                else reply("{}", status)
            }
            else -> error("Unexpected request ${req.url}")
        } }

    @Test fun aSavedSummaryIsReplacedByTheRealOneUnderItsId() = runTest {
        val identity = Identity(); val store = Store(); val sent = mutableListOf<JsonObject>()
        val c = routeEventServer(sent)
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store,
                wallClockMs = { now }) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.start(request()))
            // Saved each time the app leaves the foreground; never sent then.
            assertIs<ApiResult.Success<Unit>>(runtime.saveRouteEvent(summaryEvent(1), summaryId))
            assertIs<ApiResult.Success<Unit>>(runtime.saveRouteEvent(summaryEvent(2), summaryId))
            assertEquals(listOf("2"), store.entries.single().routeEvents.map { it.played() })
            assertEquals(mapOf(summaryId to now), store.entries.single().routeEventTimes)
            // This process's own attempt waits: its plan may still end here.
            runtime.replayRouteEvents()
            assertTrue(sent.isEmpty())
            // The plan ends: the real summary takes the saved one's place, goes out once, and leaves the journal.
            assertIs<ApiResult.Success<Unit>>(runtime.routeEvent(summaryEvent(3), summaryId))
            assertEquals(listOf(summaryId to "3"), sent.map { it.eventId() to it.played() })
            assertTrue(store.entries.single().routeEvents.isEmpty())
            assertTrue(store.entries.single().routeEventTimes.isEmpty())
        } finally { c.close() }
    }

    @Test fun aSummarySavedBeforeTheProcessDiedSurvivesTheStopAndGoesOutAtTheNextLaunch() = runTest {
        val identity = Identity(); val store = Store(); val sent = mutableListOf<JsonObject>()
        val c = routeEventServer(sent)
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store,
                wallClockMs = { now }) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.start(request()))
            assertIs<ApiResult.Success<Unit>>(runtime.saveRouteEvent(summaryEvent(1), summaryId))
            // Settling the attempt keeps the unsent summary on its tombstone.
            assertIs<ApiResult.Success<Unit>>(runtime.stop("session-1"))
            assertTrue(store.entries.single().terminal)
            assertEquals(listOf(summaryId), store.entries.single().routeEvents.map { it.eventId() })
            // The next launch, under the same login, sends it.
            SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store,
                wallClockMs = { now + hour }) { stopId }.replayRouteEvents()
            assertEquals(listOf(summaryId to "1"), sent.map { it.eventId() to it.played() })
            assertTrue(store.entries.single().routeEvents.isEmpty())
        } finally { c.close() }
    }

    @Test fun replaySendsOnlyTheLiveLoginsEventsFromTheLastDay() = runTest {
        val store = Store().apply { entries = listOf(
            unsent("mine", "a" to now - hour, "stale" to now - 25 * hour, "legacy" to null),
            unsent("other-login", "b" to now - hour).copy(loginId = "login-2"),
            unsent("other-profile", "c" to now - hour).copy(profileId = "profile-2"),
            unsent("other-installation", "d" to now - hour).copy(installationId = "44444444-4444-4444-8444-444444444444"),
        ) }
        val sent = mutableListOf<JsonObject>()
        val c = routeEventServer(sent)
        fun pending(attemptId: String) = store.entries.single { it.attemptId == attemptId }.routeEvents.map { it.eventId() }
        try {
            val identity = Identity()
            SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store,
                wallClockMs = { now }) { stopId }.replayRouteEvents()
            assertEquals(listOf("a"), sent.map { it.eventId() })
            // Sent, over a day old, untimed, or never sendable from this installation: gone.
            assertEquals(emptyList(), pending("mine"))
            assertTrue(store.entries.single { it.attemptId == "mine" }.routeEventTimes.isEmpty())
            assertEquals(emptyList(), pending("other-installation"))
            // Another login's or profile's events wait for it.
            assertEquals(listOf("b"), pending("other-login"))
            assertEquals(listOf("c"), pending("other-profile"))
        } finally { c.close() }
    }

    @Test fun replayKeepsWhatTheNetworkLostAndDropsWhatTheServerAcceptedOrRefused() = runTest {
        val store = Store().apply { entries = listOf(unsent("earlier", "a" to now - hour, "b" to now - hour, "c" to now - hour)) }
        val sent = mutableListOf<JsonObject>()
        val c = routeEventServer(sent, ArrayDeque(listOf(null, HttpStatusCode.Accepted, HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.Forbidden, HttpStatusCode.Accepted)))
        fun pending() = store.entries.single().routeEvents.map { it.eventId() }
        val identity = Identity()
        suspend fun relaunch() = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store,
            wallClockMs = { now }) { stopId }.replayRouteEvents()
        try {
            // The network fails: the event stays, and the pass ends there.
            relaunch()
            assertEquals(listOf("a"), sent.map { it.eventId() }); assertEquals(listOf("a", "b", "c"), pending())
            // Accepted leaves; a server error stays and ends the pass.
            relaunch()
            assertEquals(listOf("a", "a", "b"), sent.map { it.eventId() }); assertEquals(listOf("b", "c"), pending())
            // A refusal the server would repeat leaves too.
            relaunch()
            assertEquals(listOf("a", "a", "b", "b", "c"), sent.map { it.eventId() }); assertEquals(emptyList(), pending())
        } finally { c.close() }
    }

    @Test fun aTombstoneWithAnUnsentEventOutlivesTheTombstoneLimit() = runTest {
        val identity = Identity()
        val settled = (1..20).map { entry().copy(attemptId = "old-$it", sessionId = "old-session-$it", terminal = true) }
        val store = Store().apply { entries = settled.mapIndexed { i, entry ->
            when (i) {
                0 -> unsent(entry.attemptId, "fresh" to now - hour).copy(sessionId = entry.sessionId, terminal = true)
                1 -> unsent(entry.attemptId, "stale" to now - 25 * hour).copy(sessionId = entry.sessionId, terminal = true)
                else -> entry
            }
        } }
        val c = routeEventServer(mutableListOf())
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store,
                wallClockMs = { now }) { stopId }
            assertIs<ApiResult.Success<*>>(runtime.start(request()))
            // The newest eight, plus the oldest while its event can still be sent.
            assertEquals(listOf("old-1") + (13..20).map { "old-$it" }, store.entries.filter { it.terminal }.map { it.attemptId })
            assertEquals(listOf("fresh"), store.entries.first().routeEvents.map { it.eventId() })
        } finally { c.close() }
    }

    @Test fun v2DecisionCannotSelectImplicitLegacyStreamMount() {
        val body = SiloJson.parseToJsonElement(adoptedDecision.replace("/api/v2/stream/", "/stream/")).jsonObject
        assertFailsWith<IllegalArgumentException> { decodePlaybackDecisionV2(body) }
    }

    /** A title a phone launches on the TV plays under process-only remote-playback credentials. */
    private fun remotePlaybackIdentity() = Identity().apply {
        temporary = true
        scope = scope.copy(credentialGenerationId = "remote-1")
    }

    @Test fun remotePlaybackIdentityPlaysWithoutTheDurableJournal() = runTest {
        val identity = remotePlaybackIdentity()
        val store = Store().apply { fail = true } // Any journal write would fail the call.
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(adoptedDecision, HttpStatusCode.Created)
            "/api/v2/playback/session-1/progress" -> {
                val sample = SiloJson.decodeFromString<PlaybackProgressV2>(req.body.toByteArray().decodeToString())
                reply("""{"outcome":"applied","accepted":{"sequence":${sample.sequence},"position":${sample.position},"is_paused":${sample.isPaused}}}""")
            }
            "/api/v2/playback/session-1" -> reply("""{"outcome":"stopped","stop_id":"$stopId"}""")
            else -> error("Unexpected request ${req.url}")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request()))
            assertIs<ApiResult.Success<Unit>>(runtime.progress("session-1", 12.0, false))
            assertIs<ApiResult.Success<Unit>>(runtime.stop("session-1"))
            assertTrue(store.entries.isEmpty())
        } finally { c.close() }
    }

    @Test fun uncertainRemotePlaybackStartNeverBlocksTheNextOne() = runTest {
        val identity = remotePlaybackIdentity()
        val starts = mutableListOf<String>()
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> {
                val attempt = SiloJson.parseToJsonElement(req.body.toByteArray().decodeToString())
                    .jsonObject["playback_attempt_id"]!!.jsonPrimitive.content
                starts += attempt
                // The first start's reply is lost; the next start succeeds.
                if (starts.size == 1) throw IllegalStateException("lost reply")
                reply(decision("session-$attempt"), HttpStatusCode.Created)
            }
            else -> error("Unexpected request ${req.url}")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, Store()) { stopId }
            assertIs<ApiResult.NetworkError>(runtime.start(request()))
            // The phone sends another title: the lost start is dropped, not replayed.
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request().copy(playbackAttemptId = "attempt-2")))
            assertEquals(listOf("attempt-1", "attempt-2"), starts)
        } finally { c.close() }
    }

    @Test fun endedRemotePlaybackAttemptsAreDroppedOnTheNextSave() = runTest {
        val identity = remotePlaybackIdentity()
        var starts = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> { starts++; reply(adoptedDecision.replace("session-1", "session-$starts"), HttpStatusCode.Created) }
            else -> error("Unexpected request ${req.url}")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, Store()) { stopId }
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request()))
            assertTrue(runtime.owns("session-1"))
            // The TV restores its own login before the phone's title stopped.
            identity.temporary = false
            identity.scope = identity.scope.copy(credentialGenerationId = null, identityGeneration = 2)
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request().copy(playbackAttemptId = "attempt-2")))
            assertFalse(runtime.owns("session-1"))
            assertTrue(runtime.owns("session-2"))
        } finally { c.close() }
    }

    @Test fun remotePlaybackIdentityEndingDuringAProgressRetryIsAnIdentityChange() = runTest {
        val identity = remotePlaybackIdentity()
        var progressCalls = 0
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(adoptedDecision, HttpStatusCode.Created)
            "/api/v2/playback/session-1/progress" -> {
                progressCalls++
                // The first sample's reply is lost, so the next call retries it first.
                if (progressCalls == 1) throw IllegalStateException("lost reply")
                // The TV restores its own login while the retry is in flight.
                identity.temporary = false
                identity.scope = identity.scope.copy(credentialGenerationId = null, identityGeneration = 2)
                reply("""{"outcome":"applied","accepted":{"sequence":1,"position":12.0,"is_paused":false}}""")
            }
            else -> error("Unexpected request ${req.url}")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, Store()) { stopId }
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request()))
            assertIs<ApiResult.NetworkError>(runtime.progress("session-1", 12.0, false))
            assertEquals("identity_changed", assertIs<ApiResult.Error>(runtime.progress("session-1", 13.0, false)).error)
            assertEquals(2, progressCalls)
        } finally { c.close() }
    }

    @Test fun endedRemotePlaybackIdentityCannotActForItsAttempt() = runTest {
        val identity = remotePlaybackIdentity()
        val store = Store()
        val c = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps())
            "/api/v2/account/me" -> reply(account)
            "/api/v2/playback/start" -> reply(adoptedDecision, HttpStatusCode.Created)
            else -> error("Must not act for an ended identity: ${req.url}")
        } }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request()))
            // The TV restores its own saved login.
            identity.temporary = false
            identity.scope = identity.scope.copy(credentialGenerationId = null, identityGeneration = 2)
            assertEquals("identity_changed", assertIs<ApiResult.Error>(runtime.progress("session-1", 12.0, false)).error)
            assertEquals("identity_changed", assertIs<ApiResult.Error>(runtime.stop("session-1")).error)
            // Nothing to recover later: the credentials are gone for good.
            assertTrue(store.entries.isEmpty())
        } finally { c.close() }
    }

}
