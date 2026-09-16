package org.siloserver.silo.network.api

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.auth.LoginRequest
import org.siloserver.silo.network.*
import org.siloserver.silo.repository.DeviceLoginRepository
import kotlin.test.*

class AuthV2OperationsTest {
    // Same envelopes as real-router login_ok and poll_device_login_ok fixtures.
    private val pair = """{"access_token":"acc","refresh_token":"ref","expires_in":3600,"user":{"id":"1","username":"user","email":"u@example.test","role":"user","permissions":[],"download_allowed":true}}"""
    private val start = """{"device_code":"dev","user_code":"ABCD","match_code":"42","verification_uri":"https://example.test/link","verification_uri_complete":"https://example.test/link?code=ABCD","expires_at":"2026-01-02T03:14:05.678Z","expires_in":600,"interval":1,"device_name":"TV","device_platform":"android-tv","client_purpose":"device_login","temporary":false}"""
    private fun poll(status: String, tokens: String = "") = """{"status":"$status","poll_after":1,"profile_id":"","profile_token":"","temporary":false$tokens}"""

    @Test fun publicNonretryableOperationsSendOnceOn401WithoutCurrentCredentials() = runTest {
        val tokens = TokenManagerImpl().apply { setServerUrl("https://example.test"); saveTokens("old", "refresh", 3600) }
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertNull(request.headers[HttpHeaders.Authorization])
            assertFalse((request.body as TextContent).text.contains(":null"))
            assertEquals(true, request.attributes.getOrNull(SingleAttemptAttributeKey))
            respond("""{"code":"invalid_token","detail":"Rejected"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/problem+json"))
        }) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = tokens }
        }
        try {
            assertEquals(401, assertIs<ApiResult.Error>(AuthApi(client, ApiV2Gate.Unrestricted).login(LoginRequest("u", "p"))).code)
            val api = DefaultDeviceLoginApi(client, ApiV2Gate.Unrestricted)
            assertEquals(401, assertIs<ApiResult.Error>(api.startDeviceLogin(null, null)).code)
            assertEquals(401, assertIs<ApiResult.Error>(api.pollDeviceLogin("dev")).code)
            assertEquals(listOf("/api/v2/auth/login", "/api/v2/auth/device/start", "/api/v2/auth/device/poll"), paths)
        } finally { client.close() }
    }

    @Test fun loginRequiresStringIdAndExact200() = runTest {
        for ((body, status, succeeds) in listOf(Triple(pair, HttpStatusCode.OK, true),
            Triple(pair.replace("\"id\":\"1\"", "\"id\":1"), HttpStatusCode.OK, false), Triple(pair, HttpStatusCode.Created, false))) {
            val client = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }) {
                install(ContentNegotiation) { json(SiloJson) }
            }
            try {
                assertEquals(succeeds, AuthApi(client, ApiV2Gate.Unrestricted).login(LoginRequest("u", "p")) is ApiResult.Success)
            } finally { client.close() }
        }
    }

    @Test fun actualRepositoryCollectsNestedTokensAfterSuccessfulPendingPoll() = runTest {
        var polls = 0
        val client = HttpClient(MockEngine { request ->
            val isStart = request.url.encodedPath.endsWith("/start")
            val body = if (isStart) start else if (++polls == 1) poll("pending") else poll("approved", ",\"tokens\":$pair")
            respond(body, if (isStart) HttpStatusCode.Created else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val repo = DeviceLoginRepository(DefaultDeviceLoginApi(client, ApiV2Gate.Unrestricted))
            repo.beginAt("https://example.test", null, null)
            val approved = assertIs<DeviceLoginRepository.DeviceLoginState.Approved>(repo.state.value)
            assertEquals("acc", approved.response.accessToken); assertEquals("1", approved.response.user?.id)
            assertEquals(2, polls)
        } finally { client.close() }
    }

    @Test fun transientPollFailureKeepsPollingUntilApproved() = runTest {
        var polls = 0
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/start")) respond(start, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
            else if (++polls == 1) respond("{}", HttpStatusCode.ServiceUnavailable)
            else respond(poll("approved", ",\"tokens\":$pair"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val repo = DeviceLoginRepository(DefaultDeviceLoginApi(client, ApiV2Gate.Unrestricted))
            repo.beginAt("https://example.test", null, null)
            assertIs<DeviceLoginRepository.DeviceLoginState.Approved>(repo.state.value)
            assertEquals(2, polls)
        } finally { client.close() }
    }

    @Test fun tokenPairPreservesOpaqueAccountAndImpersonatorIds() = runTest {
        val body = pair.replace("\"id\":\"1\"", "\"id\":\"account-opaque\"")
            .replace("\"permissions\":[]", "\"permissions\":[],\"impersonation\":{\"active\":true,\"impersonator_user_id\":\"actor-opaque\",\"impersonator_username\":\"actor\"}")
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        try {
            val response = assertIs<ApiResult.Success<org.siloserver.silo.model.auth.LoginResponse>>(AuthApi(client, ApiV2Gate.Unrestricted).login(LoginRequest("u", "p")))
            assertEquals("account-opaque", response.data.user.id)
            assertEquals("actor-opaque", response.data.user.impersonation?.impersonatorUserId)
        } finally { client.close() }
    }
}
