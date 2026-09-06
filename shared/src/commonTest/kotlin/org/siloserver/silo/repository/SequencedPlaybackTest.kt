package org.siloserver.silo.repository

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
        override suspend fun read() = entries
        override suspend fun write(entries: List<PlaybackJournalEntry>) {
            check(!fail) { "disk failed" }
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

    @Test fun absentFeaturePreservesTemporaryLegacyAndProbeErrorsNeverDowngrade() = runTest {
        val identity = Identity().apply { temporary = true }
        val store = Store()
        var status = HttpStatusCode.OK
        val c = client { reply(if (status == HttpStatusCode.OK) caps("") else "{}", status) }
        try {
            val runtime = SequencedPlayback(PlaybackV2Api(c), identity, identity, store) { stopId }
            assertNull(runtime.start(request()))
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
        val decision = """{"protocol_version":3,"server_features":["playback_plan_v3","neutral_playback_v3_contract_v1","sequenced_progress_v1"],"outcome":"playable","session_id":"session-1","playback_plan":{"plan_id":"plan","plan_attempt_key":"key","session_id":"session-1","delivery":"original_http","stream":{"url":"/stream/session-1","protocol":"http_progressive"},"decision_reason":"direct","requested_media_file_id":"42","effective_media_file_id":"42","source":{"media_file_id":"42"}}}"""
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
            val repository = PlaybackRepository(org.siloserver.silo.network.api.PlaybackApi(c), runtime)
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
            val repository = PlaybackRepository(org.siloserver.silo.network.api.PlaybackApi(c), runtime)
            assertEquals(422, assertIs<ApiResult.Error>(repository.startPlaybackV3(request())).code)
            assertEquals("playback_pending", assertIs<ApiResult.Error>(repository.startPlaybackV3(request())).error)
            assertEquals(1, starts); assertEquals(request().v2Body(installation), store.entries.single().start)
        } finally { c.close() }
    }

}
