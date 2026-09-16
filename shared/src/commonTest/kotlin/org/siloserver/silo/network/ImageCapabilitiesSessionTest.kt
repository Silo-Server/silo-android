package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngineConfig
import kotlinx.coroutines.test.StandardTestDispatcher
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.api.ImageCapabilities
import org.siloserver.silo.network.api.ImageCapabilitiesApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.ImageCapabilitiesSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImageCapabilitiesSessionTest {
    @Test
    fun optionalAndFutureCapabilityFieldsDecode() {
        assertEquals(ImageCapabilities(), SiloJson.decodeFromString<ImageCapabilities>("{}"))
        assertEquals(ImageCapabilities(), SiloJson.decodeFromString<ImageCapabilities>("""{"storage_backend":null,"delivery":null,"sizes":["large"]}"""))
        assertEquals(ImageCapabilities("local", "server"), SiloJson.decodeFromString<ImageCapabilities>("""{"storage_backend":"local","delivery":"server"}"""))
        assertEquals(ImageCapabilities("s3", "direct"), SiloJson.decodeFromString<ImageCapabilities>("""{"storage_backend":"s3","delivery":"direct"}"""))
        assertEquals(ImageCapabilities("future", "future"), SiloJson.decodeFromString<ImageCapabilities>("""{"storage_backend":"future","delivery":"future"}"""))
    }

    @Test
    fun startupSwitchAndSignOutKeepProbeInItsSession() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ImageTokens()
        val requested = mutableListOf<String>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requested += request.url.host
                assertEquals("GET", request.method.value)
                assertEquals("/api/v2/images/capabilities", request.url.encodedPath)
                assertEquals("Bearer test-access", request.headers[HttpHeaders.Authorization])
                respond(if (request.url.host == "a.test") """{"storage_backend":"local","delivery":"server"}""" else "{}",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(SiloAuthPlugin) { tokenManager = tokens } }
        val session = ImageCapabilitiesSession(ImageCapabilitiesApi(client, tokens, ApiV2Gate.Unrestricted), tokens, barrier)
        val job = session.start(backgroundScope)
        try {
            runCurrent()
            assertEquals(ImageCapabilities("local", "server"), session.capabilities.value)
            barrier.changing(IdentityTransitionKind.SERVER_SWITCH) { tokens.owner = tokens.owner!!.copy(serverId = "b", serverUrl = "https://b.test/base", identityGeneration = 1) }
            runCurrent()
            assertEquals(ImageCapabilities(), session.capabilities.value)
            barrier.changing(IdentityTransitionKind.SIGN_OUT) { tokens.owner = null }
            runCurrent()
            assertNull(session.capabilities.value)
            assertEquals(listOf("a.test", "b.test"), requested)
        } finally { job.cancel(); client.close() }
    }

    @Test
    fun lateProbeAndUnavailableEndpointCannotCarryCapabilitiesAcrossServers() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ImageTokens()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                if (request.url.host == "a.test") {
                    started.complete(Unit)
                    release.await()
                    respond("""{"storage_backend":"local","delivery":"server"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                } else respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/problem+json"))
            }
        })) { install(SiloAuthPlugin) { tokenManager = tokens } }
        val session = ImageCapabilitiesSession(ImageCapabilitiesApi(client, tokens, ApiV2Gate.Unrestricted), tokens, barrier)
        val job = session.start(backgroundScope)
        try {
            started.await()
            barrier.changing(IdentityTransitionKind.SERVER_SWITCH) { tokens.owner = tokens.owner!!.copy(serverId = "b", serverUrl = "https://b.test", identityGeneration = 1) }
            release.complete(Unit)
            runCurrent()
            assertNull(session.capabilities.value)
        } finally { job.cancel(); client.close() }
    }
}

private class ImageTokens : TokenManager {
    var owner: AuthScopeSnapshot? = AuthScopeSnapshot("a", null, "https://a.test/base", null, isIdentityGenerationStamped = true)
    override suspend fun snapshotCurrentScope() = owner
    override suspend fun getAccessToken(): String? = owner?.let { "test-access" }
    override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? = if (scope.isSameIdentityAs(owner)) "test-access" else null
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) = Unit
    override suspend fun clearTokens() = Unit
    override suspend fun invalidateSession() = Unit
    override suspend fun getProfileId(): String? = null
    override suspend fun setProfileId(profileId: String?) = Unit
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) = Unit
    override suspend fun getServerUrl(): String = owner?.serverUrl.orEmpty()
    override suspend fun setServerUrl(url: String) = Unit
    override suspend fun getCurrentServerId(): String? = owner?.serverId
    override suspend fun switchActiveServer(serverId: String?) = Unit
    override suspend fun signOutCurrentServer() = Unit
    override val sessionExpired = MutableSharedFlow<Unit>()
}
