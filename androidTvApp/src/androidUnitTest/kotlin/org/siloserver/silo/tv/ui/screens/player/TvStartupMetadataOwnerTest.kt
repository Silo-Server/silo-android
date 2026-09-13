package org.siloserver.silo.tv.ui.screens.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.common.network.*
import org.siloserver.silo.common.player.*
import org.siloserver.silo.common.player.video.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.network.apiv2.*
import org.siloserver.silo.repository.*
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.libass.LibassBridge
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.tv.testing.FakePlayerSettingsStore

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TvStartupMetadataOwnerTest {
    @Test fun missingOwnerRefusesRequiredRead() = runTest { scenario("missing") }
    @Test fun changedWatchOwnerCannotStart() = runTest { scenario("watch") }
    @Test fun unchangedOwnerPreservesDeferredStartup() = runTest { scenario("success") }
    @Test fun finalOwnerChangeRollsBackActualAdoption() = runTest { scenario("final") }
    @Test fun finalSnapshotCancellationRollsBackActualAdoption() = runTest { scenario("cancel") }
    @Test fun finalOwnerChangeProtectsNewerAdoption() = runTest { scenario("newer") }
    @Test fun finalOwnerChangeRestoresDeferredPredecessor() = runTest { scenario("predecessor") }
    @Test fun changedOwnerDuringLocalPreparationCannotStart() = runTest { scenario("preparation") }

    private suspend fun TestScope.scenario(stage: String) {
        val tokens = FakeTokenManager()
        val original = tokens.metadataOwner!!
        if (stage == "missing") tokens.metadataOwner = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        var starts = 0
        val stops = mutableListOf<String>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { req ->
                val path = req.url.encodedPath
                var status = HttpStatusCode.OK
                val body = when {
                    path == "/api/v2/playback/capabilities" ->
                        """{"installation_id":"11111111-1111-4111-8111-111111111111","revision":"1","state":"available","allowed":true,"protocol_versions":[3],"features":["sequenced_progress_v1"],"deliveries":["original_http"]}"""
                    path == "/api/v2/account/me" -> """{"id":"account-1","username":"test","email":"","role":"user"}"""
                    path == "/api/v2/watch/item" -> {
                        reads++
                        assertEquals(original, req.attributes[AuthScopeAttributeKey])
                        if (stage == "watch") tokens.metadataOwner = original.copy(profileToken = "new")
                        """{"content_id":"item","type":"movie","title":"Title","versions":[{"file_id":"41","duration_seconds":120}]}"""
                    }
                    path == "/api/v2/playback/start" -> {
                        starts++
                        status = HttpStatusCode.Created
                        assertEquals(original, req.attributes[AuthScopeAttributeKey])
                        val session = if (stage == "predecessor" && starts == 1) "predecessor" else "allocated"
                        """{"protocol_version":3,"server_features":["playback_plan_v3","neutral_playback_v3_contract_v1","sequenced_progress_v1"],"outcome":"playable","session_id":"$session","playback_plan":{"plan_id":"plan","plan_attempt_key":"key","session_id":"$session","delivery":"original_http","stream":{"url":"https://silo.test/stream/$session","protocol":"http_progressive"},"decision_reason":"direct","requested_media_file_id":"41","effective_media_file_id":"41"}}"""
                    }
                    path == "/api/v2/playback/route-events" -> {
                        status = HttpStatusCode.Accepted
                        val sent = SiloJson.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                        """{"event_id":${sent["event_id"]},"outcome":"accepted"}"""
                    }
                    req.method == HttpMethod.Delete && path == "/api/v2/playback/allocated" -> {
                        stops += "allocated"
                        val sent = SiloJson.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                        """{"stop_id":${sent["stop_id"]},"outcome":"stopped"}"""
                    }
                    else -> error("Unexpected transport $path")
                }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val authorities = object : DurableLoginAuthorityProvider {
                override suspend fun snapshotDurableLoginAuthority() = tokens.metadataOwner?.let { DurableLoginAuthority("login-1", it) }
            }
            val sequenced = SequencedPlayback(PlaybackV2Api(client, ApiV2Gate.Unrestricted), tokens, authorities, StartupPlaybackJournal()) {
                java.util.UUID.randomUUID().toString()
            }
            val manager = PlaybackSessionManager(PlaybackRepository(sequenced), tokens)
            val lifecycle = PlaybackSessionLifecycle(manager, HealthApi(client), PersonalDataRepository(PersonalDataApi(client)), backgroundScope)
            fun field(name: String): Any? = PlaybackSessionLifecycle::class.java.getDeclaredField(name).let {
                it.isAccessible = true; it.get(lifecycle)
            }
            var held = false
            tokens.beforeSnapshot = {
                if (stage in listOf("final", "cancel", "newer", "predecessor") && !held && field("lastAdoptedSessionId") == "allocated") {
                    held = true; entered.complete(Unit); release.await()
                }
            }
            val profiles = object : ProfileRepository(ProfileApi(client, ApiV2Gate.Unrestricted), tokens) {
                override suspend fun getActiveProfileId() = "profile"
                override suspend fun listProfiles(): ApiResult<List<Profile>> = ApiResult.Success(listOf(Profile(id = "profile", name = "Profile")))
            }
            if (stage == "predecessor") {
                val response = manager.startVideoSessionV3(41, "profile",
                    org.siloserver.silo.model.playback.ClientCodecCapabilities(),
                    org.siloserver.silo.model.playback.ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
                    null, null, null, null, expectedMetadataOwner = original)
                val prior = (response as ApiResult.Success).data as VideoSessionStartV3.Ready
                lifecycle.adoptActiveSession(StartParams("previous", 41, prior.capabilities, clientPlaybackContext = prior.clientPlaybackContext), prior.session)
            }
            val localState = object : org.siloserver.silo.repository.port.UserItemStatePort by NoOpUserItemStatePort {
                override suspend fun localTrackSelection(contentId: String, fileId: Int): org.siloserver.silo.repository.port.LocalTrackSelection? {
                    if (stage == "preparation") tokens.metadataOwner = original.copy(credentialEpoch = 2)
                    return null
                }
            }
            val context = ApplicationProvider.getApplicationContext<Application>()
            val starter = TvVideoPlaybackStarter(
                CatalogRepository(CatalogApi(client, watchDetail = WatchDetailV2Api(client, tokens, ApiV2Gate.Unrestricted))), manager, profiles,
                PlaybackCapabilityDetector(context, AudioCapabilityManager(context), LibassBridge(false), SiloClientBuildIdentity(buildNumber = "5", channel = "release")),
                FakePlayerSettingsStore(), lifecycle,
                ServerReachabilityMonitor(ApiV2Probe(client)::probeFresh, backgroundScope, { null }), localState,
            )
            val start = async { starter.start(VideoPlaybackStartRequest(contentId = "item", preferredFileId = 41, roomId = null, resumePositionOverride = null)) }
            if (stage in listOf("final", "cancel", "newer", "predecessor")) {
                entered.await()
                val active = lifecycle.state.value as SessionState.Active
                val oldReporter = field("reporterJob") as Job
                if (stage == "newer") lifecycle.adoptActiveSession(field("lastStartParams") as StartParams, active.session.copy(sessionId = "newer"))
                tokens.metadataOwner = original.copy(profileToken = "new")
                if (stage == "cancel") { start.cancel(); start.join() }
                else { release.complete(Unit); assertIs<VideoPlaybackStartResult.Error>(start.await()) }
                assertFalse(oldReporter.isActive)
                if (stage == "newer") {
                    assertEquals("newer", field("lastAdoptedSessionId"))
                    assertTrue((field("reporterJob") as Job).isActive)
                } else if (stage == "predecessor") {
                    assertEquals("predecessor", field("lastAdoptedSessionId"))
                    assertEquals("predecessor", (lifecycle.state.value as SessionState.Active).session.sessionId)
                    assertTrue((field("reporterJob") as Job).isActive)
                    assertNull(field("pendingActiveSessionPublication"))
                } else {
                    assertEquals(SessionState.Idle, lifecycle.state.value)
                    for (name in listOf("reporterJob", "recoveryJob", "lastStartParams", "lastAdoptedSessionId", "pendingActiveSessionPublication")) assertNull(field(name), name)
                }
                assertEquals(listOf("allocated"), stops)
            } else {
                val result = start.await()
                if (stage == "success") {
                    assertIs<VideoPlaybackStartResult.Ready>(result)
                    assertEquals("allocated", result.sessionId)
                    assertEquals(41, result.fileId)
                    assertEquals("Title", result.title)
                    assertTrue(field("pendingActiveSessionPublication") != null)
                    assertEquals(emptyList(), stops)
                } else { assertIs<VideoPlaybackStartResult.Error>(result); assertEquals(0, starts); assertEquals(emptyList(), stops) }
            }
            assertEquals(if (stage == "missing") 0 else 1, reads)
        } finally { client.close() }
    }
}

private class StartupPlaybackJournal : PlaybackJournalStore {
    var entries = emptyList<PlaybackJournalEntry>()
    override suspend fun read() = entries
    override suspend fun write(entries: List<PlaybackJournalEntry>) {
        this.entries = SiloJson.decodeFromString(SiloJson.encodeToString(entries))
    }
}

private class FakeTokenManager : TokenManager {
    var metadataOwner: org.siloserver.silo.network.AuthScopeSnapshot? = org.siloserver.silo.network.AuthScopeSnapshot("server", "profile", "https://silo.test", null, identityGeneration = 1, isIdentityGenerationStamped = true, credentialEpoch = 1)
    var beforeSnapshot: suspend () -> Unit = {}
    override suspend fun snapshotCurrentScope(): org.siloserver.silo.network.AuthScopeSnapshot? {
        beforeSnapshot()
        return metadataOwner
    }
    override val sessionExpired = MutableSharedFlow<Unit>()
    override suspend fun getAccessToken(): String = "access-token"
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) = Unit
    override suspend fun clearTokens() = Unit
    override suspend fun invalidateSession() = Unit
    override suspend fun getProfileId(): String = "profile"
    override suspend fun setProfileId(profileId: String?) = Unit
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) = Unit
    override suspend fun getServerUrl(): String = "https://silo.test"
    override suspend fun setServerUrl(url: String) = Unit
    override suspend fun getCurrentServerId(): String = "server"
    override suspend fun switchActiveServer(serverId: String?) = Unit
    override suspend fun signOutCurrentServer() = Unit
}
