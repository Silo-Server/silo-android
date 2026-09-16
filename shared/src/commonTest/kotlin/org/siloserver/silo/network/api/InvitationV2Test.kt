package org.siloserver.silo.network.api

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import kotlin.test.*

class InvitationV2Test {
    @Test fun publicAcceptanceUsesExactOriginEncodedTokenAndNeverReplays401() = runTest {
        val tokens = TokenManagerImpl().apply {
            setServerUrl("https://active.example.test")
            saveTokens("existing-access", "existing-refresh", 3600)
        }
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("invite.example.test", request.url.host)
            assertEquals("/api/v2/invitations/a%2Fb%3Fc/accept", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["X-Silo-Profile-Token"])
            respond("", HttpStatusCode.Unauthorized)
        }) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = tokens }
        }
        assertIs<ApiResult.Error>(AuthApi(client, ApiV2Gate.Unrestricted).acceptInvitation("https://invite.example.test", "a/b?c", "password"))
        assertEquals(1, calls)
        assertEquals("existing-access", tokens.getAccessToken())
        client.close()
    }

    @Test fun committedOutcomeRequires201AndConsistentNestedTokens() = runTest {
        val signedIn = """{"status":"accepted","login_status":"signed_in","username":"u","tokens":{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"1","username":"u","email":"","role":"user"}}}"""
        val signInRequired = """{"status":"accepted","login_status":"sign_in_required","username":"u"}"""
        val cases = listOf(
            Triple(signedIn, HttpStatusCode.Created, true),
            Triple(signInRequired, HttpStatusCode.Created, true),
            Triple(signedIn, HttpStatusCode.OK, false),
            Triple(signedIn.replace("\"id\":\"1\"", "\"id\":1"), HttpStatusCode.Created, false),
            Triple(signInRequired.replace("sign_in_required", "signed_in"), HttpStatusCode.Created, false),
            Triple(signedIn.replace("signed_in", "sign_in_required"), HttpStatusCode.Created, false),
        )
        for ((body, status, succeeds) in cases) {
            val client = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }) {
                install(ContentNegotiation) { json(SiloJson) }
            }
            assertEquals(succeeds, AuthApi(client, ApiV2Gate.Unrestricted).acceptInvitation("https://invite.example.test", "token", "password") is ApiResult.Success)
            client.close()
        }
    }
}
