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

    @Test fun recoveryRetriesLostStopWithExactBodyAndNoAutoplay() = runTest {
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
                    if (deletes.size == 1) throw IllegalStateException("lost stop reply")
                    reply("""{"outcome":"stopped","stop_id":"$stopId"}""")
                }
                else -> error("Unexpected request ${req.url}")
            }
        }
        try {
            val identity = Identity()
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            assertEquals("stop_pending", assertIs<ApiResult.Error>(runtime.recover()).error)
            assertEquals(3, deletes); assertFalse(store.entries.single().terminal)
            val body = store.entries.single().stop
            identity.login = "login-2"
            val restarted = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { error("Must retain StopID") }
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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            assertIs<ApiResult.NetworkError>(runtime.start(request()))
            // A later start first replays the exact retained attempt; a lost reply keeps the fence.
            assertIs<ApiResult.NetworkError>(runtime.start(request().copy(playbackAttemptId = "attempt-2")))
            assertEquals(2, starts); assertEquals(listOf("attempt-1"), runtime.pending.value)
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
            assertTrue(store.entries.single().terminal); assertTrue(runtime.pending.value.isEmpty())
            // The refused attempt never fences the next start; a fresh attempt goes straight to the server.
            assertIs<ApiResult.Success<PlaybackDecisionResponseV3>>(runtime.start(request().copy(playbackAttemptId = "attempt-2")))
            assertEquals(2, starts)
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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            val repository = PlaybackRepository(runtime)
            assertEquals(422, assertIs<ApiResult.Error>(repository.startPlaybackV3(request())).code)
            // The retained attempt is replayed byte-for-byte before a new one may start; the same refusal fences again.
            assertEquals(422, assertIs<ApiResult.Error>(repository.startPlaybackV3(request())).code)
            assertEquals(2, starts); assertEquals(request().v2Body(installation), store.entries.single().start)
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
            val runtime = SequencedPlayback(PlaybackV2Api(c, ApiV2Gate.Unrestricted), identity, identity, store) { stopId }
            val repository = PlaybackRepository(runtime)
            assertEquals("playback_storage", assertIs<ApiResult.Error>(repository.recoverPlayback()).error)
            assertEquals(1, starts); assertEquals(0, deletes)
            assertEquals(listOf("attempt-1"), runtime.pending.value)
            assertEquals("session-1", store.entries.single().sessionId)
            assertEquals("playback_storage", assertIs<ApiResult.Error>(runtime.start(request().copy(playbackAttemptId = "another"))).error)
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

    @Test fun v2DecisionCannotSelectImplicitLegacyStreamMount() {
        val body = SiloJson.parseToJsonElement(adoptedDecision.replace("/api/v2/stream/", "/stream/")).jsonObject
        assertFailsWith<IllegalArgumentException> { decodePlaybackDecisionV2(body) }
    }

}
