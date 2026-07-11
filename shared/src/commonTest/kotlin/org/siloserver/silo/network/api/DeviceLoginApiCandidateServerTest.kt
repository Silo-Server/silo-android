package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloAuthPlugin
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DeviceLoginApiCandidateServerTest {
    private data class CapturedRequest(
        val url: String,
        val authorization: String?,
        val profileId: String?,
        val profileToken: String?,
    )

    @Test
    fun candidateStartAndPollUseExplicitUrlWithoutActiveAuthHeaders() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://active.example")
            saveTokens("active-access", "active-refresh", 3600)
            setProfileId("active-profile")
            setProfileToken("active-profile-token")
        }
        val captured = mutableListOf<CapturedRequest>()
        val api = DefaultDeviceLoginApi(candidateClient(tokenManager, captured))

        assertIs<ApiResult.Success<*>>(
            api.startDeviceLoginAt("https://candidate.example/", "Shield", "Android TV"),
        )
        assertIs<ApiResult.Success<*>>(
            api.pollDeviceLoginAt("https://candidate.example/", "device-code"),
        )

        assertEquals(
            listOf(
                "https://candidate.example/api/v1/auth/device/start",
                "https://candidate.example/api/v1/auth/device/poll",
            ),
            captured.map { it.url },
        )
        captured.forEach { request ->
            assertNull(request.authorization)
            assertNull(request.profileId)
            assertNull(request.profileToken)
        }
        assertEquals("https://active.example", tokenManager.getServerUrl())
        assertEquals("active-access", tokenManager.getAccessToken())
        assertEquals("active-profile", tokenManager.getProfileId())
    }

    private fun candidateClient(
        tokenManager: TokenManagerImpl,
        captured: MutableList<CapturedRequest>,
    ): HttpClient = HttpClient(
        MockEngine { request ->
            captured += CapturedRequest(
                url = request.url.toString(),
                authorization = request.headers[HttpHeaders.Authorization],
                profileId = request.headers["X-Profile-Id"],
                profileToken = request.headers["X-Profile-Token"],
            )
            val body = if (request.url.encodedPath.endsWith("/start")) {
                """{
                    "device_code":"device-code",
                    "user_code":"USER-CODE",
                    "match_code":"MATCH-42",
                    "verification_uri":"https://candidate.example/device",
                    "verification_uri_complete":"https://candidate.example/device?code=USER-CODE",
                    "expires_at":"2099-01-01T00:00:00Z",
                    "expires_in":600,
                    "interval":5,
                    "device_name":"Shield",
                    "device_platform":"Android TV"
                }""".trimIndent()
            } else {
                """{
                    "status":"approved",
                    "access_token":"new-access",
                    "refresh_token":"new-refresh",
                    "expires_in":3600
                }""".trimIndent()
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) {
        install(ContentNegotiation) { json(SiloJson) }
        install(SiloAuthPlugin) { this.tokenManager = tokenManager }
    }
}
