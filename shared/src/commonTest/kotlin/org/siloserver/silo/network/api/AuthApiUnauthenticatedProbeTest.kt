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

class AuthApiUnauthenticatedProbeTest {

    private data class CapturedRequest(
        val url: String,
        val authorization: String?,
        val profileId: String?,
        val profileToken: String?,
    )

    @Test
    fun candidateSetupAndSignupProbesOmitActiveAuthAndProfileHeaders() = runTest {
        val tokenManager = activeTokenManager()
        val captured = mutableListOf<CapturedRequest>()
        val api = AuthApi(probeClient(tokenManager, captured))

        assertIs<ApiResult.Success<*>>(api.getSetupStatus("https://candidate.example/"))
        assertIs<ApiResult.Success<*>>(api.getSignupStatus("https://candidate.example/"))

        assertEquals(
            listOf(
                "https://candidate.example/api/v2/system/setup",
                "https://candidate.example/api/v1/auth/signup",
            ),
            captured.map { it.url },
        )
        captured.forEach { request ->
            assertEquals(null, request.authorization, "Authorization leaked to ${request.url}")
            assertEquals(null, request.profileId, "Profile id leaked to ${request.url}")
            assertEquals(null, request.profileToken, "Profile token leaked to ${request.url}")
        }
    }

    @Test
    fun candidateProbeUnauthorizedDoesNotRefreshOrInvalidateActiveSession() = runTest {
        val tokenManager = activeTokenManager()
        val captured = mutableListOf<CapturedRequest>()
        val api = AuthApi(
            probeClient(
                tokenManager = tokenManager,
                captured = captured,
                probeStatus = HttpStatusCode.Unauthorized,
            ),
        )

        assertIs<ApiResult.Error>(api.getSetupStatus("https://candidate.example"))

        assertEquals(
            listOf("https://candidate.example/api/v2/system/setup"),
            captured.map { it.url },
        )
        assertEquals("ACTIVE", tokenManager.getAccessToken())
        assertEquals("REFRESH", tokenManager.getRefreshToken())
    }

    private suspend fun activeTokenManager(): TokenManagerImpl =
        TokenManagerImpl().apply {
            setServerUrl("https://active.example")
            saveTokens(accessToken = "ACTIVE", refreshToken = "REFRESH", expiresIn = 3600)
            setProfileId("profile-a")
            setProfileToken("profile-token-a")
        }

    private fun probeClient(
        tokenManager: TokenManagerImpl,
        captured: MutableList<CapturedRequest>,
        probeStatus: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                captured += CapturedRequest(
                    url = request.url.toString(),
                    authorization = request.headers[HttpHeaders.Authorization],
                    profileId = request.headers["X-Profile-Id"],
                    profileToken = request.headers["X-Profile-Token"],
                )

                val status = if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    HttpStatusCode.Unauthorized
                } else {
                    probeStatus
                }
                val body = when {
                    status == HttpStatusCode.Unauthorized ->
                        """{"error":"unauthorized","message":"not authorized"}"""
                    request.url.encodedPath.endsWith("/auth/signup") ->
                        """{"enabled":true}"""
                    else ->
                        """{"needs_setup":false}"""
                }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(ContentNegotiation) {
                json(SiloJson)
            }
            install(SiloAuthPlugin) {
                this.tokenManager = tokenManager
            }
        }
}
