package org.siloserver.silo.common.player

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.model.playback.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.network.apiv2.*
import org.siloserver.silo.repository.*
import kotlin.test.*

/** Actual journal -> repository -> native manager -> lifecycle/ordered navigation barrier. */
class PlaybackOwnerLossTransitionTest {
    @Test fun ownerLossRetiresJournalButNeverOpensPartOrNextItemContinuation() = runTest {
        for (ordered in listOf(false, true)) for (ordinarySwitch in listOf(false, true)) {
            val identity = object : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
                val owner = AuthScopeSnapshot("server", "profile", "https://fixture.example", "proof",
                    identityGeneration = 1, isIdentityGenerationStamped = true, credentialEpoch = 1)
                override suspend fun snapshotCurrentScope() = owner
                override suspend fun snapshotDurableLoginAuthority() = DurableLoginAuthority("login", owner)
            }
            val manifest = PlaybackManifestV2("installation", "a".repeat(64), "item", "edition", 100.0,
                listOf(PlaybackManifestPartV2("41", 0.0, 48.0), PlaybackManifestPartV2("42", 48.0, 52.0)))
            val original = PlaybackJournalEntry("server", "https://fixture.example", "login", "account", "profile", "installation", "attempt",
                buildJsonObject { put("file_id", "42"); put("progress_persistence", "client_bound"); put("timeline_id", manifest.timelineId) },
                sessionId = "session", stop = PlaybackStopV2("installation", "stop", 9, 35.0, true, manifest.timelineId),
                progress = PlaybackProgressV2("installation", 9, 35.0, true, manifest.timelineId), manifest = manifest)
            val store = object : PlaybackJournalStore {
                var entries = listOf(original)
                override suspend fun read() = entries
                override suspend fun write(entries: List<PlaybackJournalEntry>) { this.entries = entries }
            }
            var terminal = false
            var mutations = 0
            val client = HttpClient(MockEngine { req ->
                val (status, body) = when (req.url.encodedPath) {
                    "/api/v2/playback/capabilities" -> HttpStatusCode.OK to """{"installation_id":"installation","revision":"1","state":"available","allowed":true,"protocol_versions":[3],"features":["sequenced_progress_v1"],"deliveries":["direct"]}"""
                    "/api/v2/account/me" -> HttpStatusCode.OK to """{"id":"account","username":"test","email":"","role":"user"}"""
                    "/api/v2/playback/session" -> {
                        mutations++
                        assertEquals(HttpMethod.Delete, req.method)
                        assertEquals(SiloJson.encodeToString(original.stop), req.body.toByteArray().decodeToString())
                        val state = if (terminal) "aborted" else "draining"
                        if (terminal && ordinarySwitch) HttpStatusCode.OK to
                            """{"outcome":"replayed","stop_id":"stop","accepted":{"sequence":9,"position":35.0,"is_paused":true,"timeline_id":"${manifest.timelineId}","item_position":83.0}}"""
                        else (if (terminal) HttpStatusCode.OK else HttpStatusCode.Accepted) to
                            """{"outcome":"$state","recovery":{"recovery_id":"44444444-4444-4444-8444-444444444444","playback_attempt_id":"attempt","session_id":"session","state":"$state","reason":"owner_lost"}}"""
                    }
                    else -> error("Unexpected start/progress/legacy transport: ${req.url.encodedPath}")
                }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { install(ContentNegotiation) { json(SiloJson) } }
            try {
                val journal = SequencedPlayback(PlaybackV2Api(client), identity, identity, store) { "unused" }
                assertIs<ApiResult.Error>(journal.recover()) // Captured explicit recovery; no player/media rights.
                val draining = store.entries.single()
                val repository = PlaybackRepository(sequenced = journal)
                val manager = PlaybackSessionManager(repository, identity)
                val lifecycle = PlaybackSessionLifecycle(manager, HealthApi(client), PersonalDataRepository(PersonalDataApi(client)), backgroundScope)
                lifecycle.adoptActiveSession(StartParams(contentId = "item", fileId = 42, capabilities = ClientCodecCapabilities(),
                    clientPlaybackContext = ClientPlaybackContext(formFactor = "phone", appVersion = "test"), audioTrackIndex = null, qualityPreference = null, startPosition = 9.0),
                    PlaybackSessionResponse(sessionId = "session", userId = 1, profileId = "profile", mediaFileId = 42, playMethod = PlayMethod.DIRECT, position = 9.0, isPaused = false, streamUrl = "https://fixture.example/issued"), manageProgress = false)
                terminal = true
                val gate = PlaybackTeardownGate(lifecycle)
                var nextStarts = 0
                suspend fun transition() {
                    // Same production barriers used by audiobook retireActiveSession and ordered TV navigation.
                    val stopped = if (ordered) gate.stopOrdered("session") else lifecycle.stop("session")
                    if (stopped) nextStarts++
                }
                transition(); transition()
                assertEquals(0, nextStarts)
                if (ordinarySwitch) {
                    assertFalse(lifecycle.wasAbandoned("session"))
                    assertEquals(draining, store.entries.single())
                    assertEquals(1, journal.pendingForCurrentViewer())
                    assertEquals("Playback stop is pending. Retry from playback recovery.", assertIs<SessionState.Failed>(lifecycle.state.value).message)
                } else {
                    assertTrue(lifecycle.wasAbandoned("session"))
                    assertEquals(PLAYBACK_OWNER_LOST_MESSAGE, assertIs<SessionState.Failed>(lifecycle.state.value).message)
                    assertEquals(4, mutations) // Three bounded drains, then one terminal abort; no orphan retry.
                    assertEquals("aborted", store.entries.single().ownerLoss?.state)
                    assertTrue(store.entries.single().terminal)
                    assertEquals(original.stop, store.entries.single().stop)
                    assertEquals(original.progress, store.entries.single().progress)
                    assertNull(store.entries.single().acceptedBoundSample)
                    assertEquals(0, journal.pendingForCurrentViewer())
                    assertIs<ApiResult.Success<*>>(journal.recover())
                    assertEquals(PLAYBACK_OWNER_LOST, assertIs<ApiResult.Error>(manager.stopSession("session")).error)
                    assertEquals(4, mutations)
                }

            } finally { client.close() }
        }
    }
}
