package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A remote-playback overlay whose session the server ended must be reported,
 * so the TV can stop what the phone launched and restore its own profile.
 * Playback's pinned calls (progress, stop) are usually the first to notice.
 */
class SiloAuthPluginTemporaryRejectionTest {
    @Test
    fun pinnedCallReportsARejectedTemporaryGeneration() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            beginTemporaryScope(
                TemporaryAuthScope(
                    generationId = "gen-1",
                    serverId = "server-a",
                    serverUrl = "https://a.example",
                    accessToken = "ACCESS",
                    refreshToken = "REFRESH",
                    profileId = "profile-a",
                    profileToken = "ptoken-a",
                    expiresAtEpochMs = Long.MAX_VALUE,
                ),
            )
        }
        // The session was revoked: the call and its refresh are both refused.
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.Unauthorized) }) {
            install(ContentNegotiation) { json() }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }
        val scope = AuthScopeSnapshot(
            serverId = "server-a",
            profileId = "profile-a",
            serverUrl = "https://a.example",
            profileToken = "ptoken-a",
            credentialGenerationId = "gen-1",
        )

        client.post("/api/v2/playback/session-1/progress") { authScope(scope) }

        assertEquals(setOf("gen-1"), tokenManager.rejectedTemporaryGenerations.value)
    }
}
