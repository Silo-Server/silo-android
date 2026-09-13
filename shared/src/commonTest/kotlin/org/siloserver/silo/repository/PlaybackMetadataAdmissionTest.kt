package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.siloserver.silo.model.playback.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.*
import kotlin.test.*

class PlaybackMetadataAdmissionTest {
    private class Identity : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
        var owner = AuthScopeSnapshot("server", "profile", "https://example.invalid", "pin", identityGeneration = 1, isIdentityGenerationStamped = true, credentialEpoch = 1)
        var snapshotHook: suspend () -> Unit = {}
        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot { snapshotHook(); return owner }
        override suspend fun snapshotDurableLoginAuthority() = owner.takeIf { it.credentialGenerationId == null }?.let { DurableLoginAuthority("login", it) }
    }
    private class Store : PlaybackJournalStore {
        var rows = emptyList<PlaybackJournalEntry>()
        var writeHook: suspend () -> Unit = {}
        override suspend fun read() = rows
        override suspend fun write(entries: List<PlaybackJournalEntry>) { rows = entries; writeHook() }
    }
    private fun request(id: String = "attempt") = PlaybackStartRequestV3(clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
        fileId = 42, profileId = "profile", playbackAttemptId = id, subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
        capabilities = ClientCodecCapabilities(), clientPlaybackContext = ClientPlaybackContext(formFactor = "mobile", appVersion = "test"))
    private val caps = """{"installation_id":"11111111-1111-4111-8111-111111111111","revision":"1","state":"available","allowed":true,"protocol_versions":[3],"features":["sequenced_progress_v1"],"deliveries":["direct"]}"""
    private val decision = """{"protocol_version":3,"server_features":["playback_plan_v3","neutral_playback_v3_contract_v1","sequenced_progress_v1"],"outcome":"playable","session_id":"session-1","playback_plan":{"plan_id":"plan","plan_attempt_key":"key","session_id":"session-1","delivery":"original_http","stream":{"url":"/api/v2/stream/session-1","protocol":"http_progressive"},"decision_reason":"direct","requested_media_file_id":"42","effective_media_file_id":"42","source":{"media_file_id":"42"}}}"""
    private val account = """{"id":"account","username":"test","email":"","role":"user"}"""
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) })) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test fun initialProfilePinEpochAndTemporaryReplacementRefuseWithoutNetworkOrJournal() = runTest {
        val identity = Identity(); val original = identity.owner; val store = Store(); var calls = 0
        val client = client { calls++; reply("{}") }
        try {
            val sequenced = SequencedPlayback(PlaybackV2Api(client, ApiV2Gate.Unrestricted), identity, identity, store) { "stop" }
            for (changed in listOf(original.copy(profileId = "other"), original.copy(profileToken = "new"), original.copy(identityGeneration = 3), original.copy(credentialEpoch = 2), original.copy(credentialGenerationId = "temporary"))) {
                identity.owner = changed
                assertEquals("identity_changed", assertIs<ApiResult.Error>(sequenced.start(request(), original)).error)
            }
            identity.owner = original
            assertIs<ApiResult.Error>(sequenced.start(request().copy(profileId = "other"), original))
            assertEquals(0, calls); assertTrue(store.rows.isEmpty())
        } finally { client.close() }
    }
    @Test fun waitingForSequencedMutexCannotRecaptureReplacementOwner() = runTest {
        val identity = Identity(); val original = identity.owner; val store = Store()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val client = client { calls++; entered.complete(Unit); release.await(); reply(caps) }
        try {
            val sequenced = SequencedPlayback(PlaybackV2Api(client, ApiV2Gate.Unrestricted), identity, identity, store) { "stop" }
            val first = async { sequenced.start(request("first"), original) }; entered.await()
            val second = async { sequenced.start(request("second"), original) }
            yield(); identity.owner = original.copy(identityGeneration = 3); release.complete(Unit)
            assertIs<ApiResult.Error>(first.await()); assertIs<ApiResult.Error>(second.await())
            assertEquals(1, calls); assertTrue(store.rows.isEmpty())
        } finally { client.close() }
    }
    @Test fun capabilityAndAccountReplacementCannotAuthorAttemptOrFallBack() = runTest {
        for (replaceAt in listOf("/api/v2/playback/capabilities", "/api/v2/account/me")) {
            val identity = Identity(); val original = identity.owner; val store = Store(); var starts = 0
            val client = client { req ->
                if (req.url.encodedPath == replaceAt) identity.owner = original.copy(profileToken = "new")
                when (req.url.encodedPath) {
                    "/api/v2/playback/capabilities" -> reply(caps)
                    "/api/v2/account/me" -> reply(account)
                    else -> { starts++; reply("{}") }
                }
            }
            try {
                val repo = PlaybackRepository(SequencedPlayback(PlaybackV2Api(client, ApiV2Gate.Unrestricted), identity, identity, store) { "stop" })
                assertIs<ApiResult.Error>(repo.startPlaybackV3(request(), original))
                assertEquals(0, starts); assertTrue(store.rows.isEmpty())
            } finally { client.close() }
        }
    }
    @Test fun temporaryAbsentFeatureNeverAdmitsLegacyAndHandoffReplacementCannotSend() = runTest {
        val identity = Identity(); identity.owner = identity.owner.copy(credentialGenerationId = "temporary")
        val original = identity.owner; val store = Store(); var legacy = 0; var afterAbsence = false; var replace = false
        val client = client { req ->
            assertEquals(original, req.attributes[AuthScopeAttributeKey])
            if (req.url.encodedPath.endsWith("capabilities")) { afterAbsence = true; reply(caps.replace("\"sequenced_progress_v1\"", "")) }
            else { legacy++; reply("{}", HttpStatusCode.ServiceUnavailable) }
        }
        try {
            val sequenced = SequencedPlayback(PlaybackV2Api(client, ApiV2Gate.Unrestricted), identity, identity, store) { "stop" }
            val repo = PlaybackRepository(sequenced)
            repo.startPlaybackV3(request(), original)
            assertEquals(0, legacy); assertTrue(store.rows.isEmpty())
            // Change at the repository's post-absence authority snapshot, after
            // all sequenced checks. Count from the returning durable lookup.
            var captures = 0
            identity.snapshotHook = {
                if (afterAbsence && replace && ++captures == 5) identity.owner = original.copy(credentialGenerationId = "replacement")
            }
            afterAbsence = false; replace = true
            assertIs<ApiResult.Error>(repo.startPlaybackV3(request("second"), original))
            assertEquals(0, legacy); assertTrue(store.rows.isEmpty())
        } finally { client.close() }
    }
    @Test fun changeDuringSaveRetainsOriginalUnsentAttemptAndNoLegacyReplay() = runTest {
        val identity = Identity(); val original = identity.owner; val store = Store(); var starts = 0
        store.writeHook = { identity.owner = original.copy(credentialEpoch = 2) }
        val client = client { req -> when (req.url.encodedPath) {
            "/api/v2/playback/capabilities" -> reply(caps)
            "/api/v2/account/me" -> reply(account)
            else -> { starts++; reply("{}") }
        } }
        try {
            val repo = PlaybackRepository(SequencedPlayback(PlaybackV2Api(client, ApiV2Gate.Unrestricted), identity, identity, store) { "stop" })
            assertIs<ApiResult.Error>(repo.startPlaybackV3(request(), original))
            assertEquals(0, starts); assertEquals("attempt", store.rows.single().attemptId)
            assertNull(store.rows.single().sessionId); assertFalse(store.rows.single().terminal)
        } finally { client.close() }
    }
    @Test fun unchangedOwnerAdmitsOriginalAttemptAndPinnedV2Start() = runTest {
        val identity = Identity(); val original = identity.owner; val store = Store(); var starts = 0
        val client = client { req ->
            assertEquals(original, req.attributes[AuthScopeAttributeKey])
            when (req.url.encodedPath) {
                "/api/v2/playback/capabilities" -> reply(caps)
                "/api/v2/account/me" -> reply(account)
                else -> { starts++; assertTrue(req.attributes[SingleAttemptAttributeKey]); reply(decision, HttpStatusCode.Created) }
            }
        }
        try {
            val repo = PlaybackRepository(SequencedPlayback(PlaybackV2Api(client, ApiV2Gate.Unrestricted), identity, identity, store) { "stop" })
            assertIs<ApiResult.Success<*>>(repo.startPlaybackV3(request(), original))
            assertEquals(1, starts); assertEquals("attempt", store.rows.single().attemptId)
            assertEquals("session-1", store.rows.single().sessionId)
        } finally { client.close() }
    }
    @Test fun realAuthPluginUsesCapturedProfilePinAndLiveCredentialSlotForV2() = runTest {
        run {
            val identity = Identity()
            val expected = identity.owner
            var bearer = "rotated-access"
            val tokens = object : TokenManager by identity {
                override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String {
                    assertEquals(expected, scope)
                    return bearer
                }
            }
            val urls = mutableListOf<String>()
            val client = HttpClient(MockEngine(MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { req ->
                    urls += req.url.toString()
                    assertEquals("https://example.invalid", req.url.protocol.name+"://"+req.url.host)
                    assertEquals("Bearer "+bearer, req.headers[HttpHeaders.Authorization])
                    assertEquals("profile", req.headers["X-Profile-ID"])
                    assertEquals("pin", req.headers["X-Profile-Token"])
                    when (req.url.encodedPath) {
                        "/api/v2/playback/capabilities" -> reply(caps)
                        "/api/v2/account/me" -> { bearer = "fresh-access"; reply(account) }
                        "/api/v2/playback/start" -> reply(decision, HttpStatusCode.Created)
                        else -> reply("{}", HttpStatusCode.ServiceUnavailable)
                    }
                }
            })) { install(ContentNegotiation) { json(SiloJson) }; install(SiloAuthPlugin) { tokenManager = tokens } }
            try {
                val runtime = SequencedPlayback(PlaybackV2Api(client, ApiV2Gate.Unrestricted), tokens, identity, Store()) { "stop" }
                val repo = PlaybackRepository(runtime)
                assertIs<ApiResult.Success<*>>(repo.startPlaybackV3(request(), expected))
                assertTrue(urls.last().endsWith("/api/v2/playback/start"))
            } finally { client.close() }
        }
    }
    @Test fun dispatchedReplyKeepsOriginalAttemptAndSessionWhenAuthorityChanges() = runTest {
        val identity = Identity(); val original = identity.owner; val store = Store(); var starts = 0
        val client = client { req ->
            assertEquals(original, req.attributes[AuthScopeAttributeKey])
            when (req.url.encodedPath) {
                "/api/v2/playback/capabilities" -> reply(caps)
                "/api/v2/account/me" -> reply(account)
                else -> { starts++; identity.owner = original.copy(profileToken = "new"); reply(decision, HttpStatusCode.Created) }
            }
        }
        try {
            val repo = PlaybackRepository(SequencedPlayback(PlaybackV2Api(client, ApiV2Gate.Unrestricted), identity, identity, store) { "stop" })
            assertFalse(repo.startPlaybackV3(request(), original) is ApiResult.Success)
            assertEquals(1, starts); assertEquals("attempt", store.rows.single().attemptId)
            assertEquals("session-1", store.rows.single().sessionId); assertFalse(store.rows.single().terminal)
        } finally { client.close() }
    }
}
