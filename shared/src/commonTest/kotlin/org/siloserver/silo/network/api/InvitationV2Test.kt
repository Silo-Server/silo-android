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
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.siloserver.silo.model.auth.InvitationLookupResponse
import org.siloserver.silo.repository.AuthRepository
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
        assertIs<ApiResult.Error>(AuthApi(client, ApiV2Gate.Unrestricted).acceptInvitation("https://invite.example.test", "a/b?c", "password", tokenInBody = false))
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
            assertEquals(succeeds, AuthApi(client, ApiV2Gate.Unrestricted).acceptInvitation("https://invite.example.test", "token", "password", tokenInBody = false) is ApiResult.Success)
            client.close()
        }
    }

    @Test fun bodyFormSendsTheTokenOnlyInTheBody() = runTest {
        val lookup = """{"email":"u@example.test","server_name":"S","expires_at":"2099-01-01T00:00:00Z","show_tour":true,"acceptance_available":true}"""
        val accepted = """{"status":"accepted","login_status":"sign_in_required","username":"u"}"""
        val seen = mutableListOf<Triple<HttpMethod, String, JsonObject>>()
        val client = HttpClient(MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            val body = SiloJson.parseToJsonElement((request.body as TextContent).text).jsonObject
            seen += Triple(request.method, request.url.toString(), body)
            when (request.url.encodedPath) {
                "/api/v2/invitations/lookup" -> respond(lookup, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                "/api/v2/invitations/accept" -> respond(accepted, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("", HttpStatusCode.MethodNotAllowed)
            }
        }) { install(ContentNegotiation) { json(SiloJson) } }
        val api = AuthApi(client, ApiV2Gate.Unrestricted)
        assertIs<ApiResult.Success<*>>(api.lookupInvitation("https://invite.example.test", "a/b?c", tokenInBody = true))
        assertIs<ApiResult.Success<*>>(api.acceptInvitation("https://invite.example.test", "a/b?c", "password", tokenInBody = true))
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Post), seen.map { it.first })
        assertEquals(
            listOf("https://invite.example.test/api/v2/invitations/lookup", "https://invite.example.test/api/v2/invitations/accept"),
            seen.map { it.second },
        )
        assertEquals(JsonObject(mapOf("token" to JsonPrimitive("a/b?c"))), seen[0].third)
        assertEquals(JsonObject(mapOf("password" to JsonPrimitive("password"), "token" to JsonPrimitive("a/b?c"))), seen[1].third)
        client.close()
    }

    @Test fun repositoryPicksTheLookupFormFromTheCapability() = runTest {
        val lookup = """{"email":"u@example.test","server_name":"S","expires_at":"2099-01-01T00:00:00Z","show_tour":true,"acceptance_available":true}"""
        for (tokenInBody in listOf(false, true)) {
            val capability = """{"revision":"r","state":"available","default_profile":true,"profileless":true""" +
                (if (tokenInBody) ""","token_in_body":true}""" else "}")
            val paths = mutableListOf<String>()
            val client = HttpClient(MockEngine { request ->
                paths += "${request.method.value} ${request.url.encodedPath}"
                val body = if (request.url.encodedPath.endsWith("capabilities")) capability else lookup
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { install(ContentNegotiation) { json(SiloJson) } }
            val repository = AuthRepository(AuthApi(client, ApiV2Gate.Unrestricted), TokenManagerImpl())
            val result = repository.lookupInvitation("https://invite.example.test", "tok")
            assertEquals(tokenInBody, assertIs<ApiResult.Success<InvitationLookupResponse>>(result).data.tokenInBody)
            val expected = if (tokenInBody) "POST /api/v2/invitations/lookup" else "GET /api/v2/invitations/tok"
            assertEquals(listOf("GET /api/v2/invitations/capabilities", expected), paths)
            client.close()
        }
    }
}
