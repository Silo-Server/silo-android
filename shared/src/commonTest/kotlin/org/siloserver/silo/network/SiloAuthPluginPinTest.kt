package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

/**
 * Verifies [SiloAuthPlugin] honors an [AuthScopeSnapshot] pin: a request
 * tagged with [authScope] is bound to the snapshot's server URL + profile and
 * the per-server access token, regardless of the globally-active scope. This is
 * the network-layer guarantee the Track B outbox drain relies on.
 */
class SiloAuthPluginPinTest {
    @Test
    fun unapprovedCleartextOriginIsBlockedBeforeCredentialsReachEngine() = runTest {
        var engineCalled = false
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("http://silo.lan")
            saveTokens("access", "refresh", 3600)
        }
        val client = HttpClient(
            MockEngine {
                engineCalled = true
                respond("{}", HttpStatusCode.OK)
            },
        ) {
            install(SiloAuthPlugin) {
                this.tokenManager = tokenManager
                this.cleartextOriginConsent = object : CleartextOriginConsent {
                    override suspend fun isApproved(origin: String): Boolean = false
                }
            }
        }

        assertFailsWith<CleartextOriginNotApprovedException> {
            client.get("/api/v1/items")
        }
        assertEquals(false, engineCalled)
        client.close()
    }

    @Test
    fun unapprovedCleartextWebSocketIsBlockedBeforeCredentialsReachEngine() = runTest {
        var engineCalled = false
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("http://silo.lan")
            saveTokens("access", "refresh", 3600)
            setProfileId("profile")
            setProfileToken("profile-token")
        }
        val client = HttpClient(
            MockEngine {
                engineCalled = true
                respond("{}", HttpStatusCode.OK)
            },
        ) {
            install(SiloAuthPlugin) {
                this.tokenManager = tokenManager
                this.cleartextOriginConsent = object : CleartextOriginConsent {
                    override suspend fun isApproved(origin: String): Boolean = false
                }
            }
        }

        assertFailsWith<CleartextOriginNotApprovedException> {
            client.get("ws://silo.lan/api/v1/watch-together/rooms/r/ws?room_token=secret")
        }
        assertEquals(false, engineCalled)
        client.close()
    }

    @Test
    fun unauthenticatedLoginPostStillRequiresCleartextApproval() = runTest {
        var engineCalled = false
        val tokenManager = TokenManagerImpl().apply { setServerUrl("http://silo.lan") }
        val client = HttpClient(
            MockEngine {
                engineCalled = true
                respond("{}", HttpStatusCode.OK)
            },
        ) {
            install(SiloAuthPlugin) {
                this.tokenManager = tokenManager
                this.cleartextOriginConsent = object : CleartextOriginConsent {
                    override suspend fun isApproved(origin: String): Boolean = false
                }
            }
        }

        assertFailsWith<CleartextOriginNotApprovedException> {
            client.post("/api/v1/auth/login") { skipSiloAuth() }
        }
        assertEquals(false, engineCalled)
        client.close()
    }

    @Test
    fun absoluteCandidateAuthPostsCheckTheirOwnCleartextOrigin() = runTest {
        var engineCalled = false
        val tokenManager = TokenManagerImpl().apply { setServerUrl("https://active.example") }
        val client = HttpClient(
            MockEngine {
                engineCalled = true
                respond("{}", HttpStatusCode.OK)
            },
        ) {
            install(SiloAuthPlugin) {
                this.tokenManager = tokenManager
                this.cleartextOriginConsent = object : CleartextOriginConsent {
                    override suspend fun isApproved(origin: String): Boolean = false
                }
            }
        }

        listOf(
            "/api/v1/auth/device/start",
            "/api/v1/auth/device/poll",
            "/api/v1/auth/remote-playback/start",
        ).forEach { path ->
            assertFailsWith<CleartextOriginNotApprovedException>(path) {
                client.post("http://candidate.lan$path") { skipSiloAuth() }
            }
        }
        assertEquals(false, engineCalled)
        client.close()
    }


    private class Captured {
        var url: String = ""
        var authorization: String? = null
        var profileId: String? = null
        var profileToken: String? = null
        var siloClientFamily: String? = null
        var siloClient: String? = null
        var siloClientVersion: String? = null
    }

    private fun client(
        tokenManager: TokenManager,
        captured: Captured,
        deviceMetadataProvider: DeviceMetadataProvider? = null,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                captured.url = request.url.toString()
                captured.authorization = request.headers[HttpHeaders.Authorization]
                captured.profileId = request.headers["X-Profile-Id"]
                captured.profileToken = request.headers["X-Profile-Token"]
                captured.siloClientFamily = request.headers["X-Silo-Client-Family"]
                captured.siloClient = request.headers["X-Silo-Client"]
                captured.siloClientVersion = request.headers["X-Silo-Client-Version"]
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(SiloAuthPlugin) {
                this.tokenManager = tokenManager
                this.deviceMetadataProvider = deviceMetadataProvider
            }
        }

    @Test
    fun pinnedRequestUsesSnapshotUrlProfileAndServerToken() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            saveTokens(accessToken = "ACCESS-A", refreshToken = "REFRESH-A", expiresIn = 3600)
        }
        val captured = Captured()
        val snapshot = AuthScopeSnapshot(
            serverId = "server-a",
            profileId = "profile-a",
            serverUrl = "https://a.example",
            profileToken = "ptoken-a",
        )

        client(tokenManager, captured).post("/api/v1/watched/item-1") { authScope(snapshot) }

        assertEquals("https://a.example/api/v1/watched/item-1", captured.url)
        assertEquals("Bearer ACCESS-A", captured.authorization)
        assertEquals("profile-a", captured.profileId)
        assertEquals("ptoken-a", captured.profileToken)
    }

    @Test
    fun pinnedRequestOmitsProfileTokenHeaderWhenSnapshotHasNone() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            saveTokens(accessToken = "ACCESS-A", refreshToken = "REFRESH-A", expiresIn = 3600)
        }
        val captured = Captured()
        val snapshot = AuthScopeSnapshot(
            serverId = "server-a",
            profileId = null,
            serverUrl = "https://a.example",
            profileToken = null,
        )

        client(tokenManager, captured).post("/api/v1/watched/item-1") { authScope(snapshot) }

        assertEquals(null, captured.profileToken)
        assertEquals(null, captured.profileId)
        assertEquals("Bearer ACCESS-A", captured.authorization)
    }

    @Test
    fun requestsIncludePlaybackClientHeadersWhenDeviceMetadataProvidesThem() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens(accessToken = "ACCESS-A", refreshToken = "REFRESH-A", expiresIn = 3600)
        }
        val captured = Captured()
        val provider = object : DeviceMetadataProvider {
            override suspend fun current(): SiloDeviceMetadata =
                SiloDeviceMetadata(
                    id = "device-1",
                    name = "Amazon AFTKA",
                    platform = "android-tv",
                    clientFamily = "tv",
                    clientName = "Silo Android TV",
                    clientVersion = "0.2.3",
                )
        }

        client(tokenManager, captured, provider).post("/api/v1/playback/start")

        assertEquals("tv", captured.siloClientFamily)
        assertEquals("Silo Android TV", captured.siloClient)
        assertEquals("0.2.3", captured.siloClientVersion)
    }

    @Test
    fun explicitClientFamilyHeaderIsNotOverwrittenByLiveDeviceMetadata() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens(accessToken = "ACCESS-A", refreshToken = "REFRESH-A", expiresIn = 3600)
        }
        val captured = Captured()
        val provider = object : DeviceMetadataProvider {
            override suspend fun current(): SiloDeviceMetadata =
                SiloDeviceMetadata(
                    id = "device-1",
                    name = "Foldable",
                    platform = "android",
                    clientFamily = "tablet",
                )
        }

        client(tokenManager, captured, provider).post("/api/v1/settings/values/ui.card_presentation") {
            header("X-Silo-Client-Family", "mobile")
        }

        assertEquals("mobile", captured.siloClientFamily)
    }

    @Test
    fun normalRequestsUseCredentialsOnlyOnConfiguredOrigin() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example:8443")
            setProfileId("profile-a")
            setProfileToken("profile-token-a")
            saveTokens(accessToken = "ACCESS-A", refreshToken = "REFRESH-A", expiresIn = 3600)
        }
        val captured = mutableListOf<CapturedRequest>()
        val client = capturingClient(tokenManager, captured)

        client.get("/api/v1/downloads/download-1/file")
        client.get("https://silo.example:8443/api/v1/items")
        client.get("https://cdn.silo.example:8443/video")
        client.get("https://silo.example:9443/video")
        client.get("http://silo.example:8443/video")

        assertEquals(
            listOf(
                "https://silo.example:8443/api/v1/downloads/download-1/file",
                "https://silo.example:8443/api/v1/items",
                "https://cdn.silo.example:8443/video",
                "https://silo.example:9443/video",
                "http://silo.example:8443/video",
            ),
            captured.map(CapturedRequest::url),
        )
        captured.take(2).forEach { request ->
            assertEquals("Bearer ACCESS-A", request.authorization)
            assertEquals("profile-a", request.profileId)
            assertEquals("profile-token-a", request.profileToken)
        }
        captured.drop(2).forEach { request ->
            assertNull(request.authorization, "Authorization leaked to ${request.url}")
            assertNull(request.profileId, "X-Profile-Id leaked to ${request.url}")
            assertNull(request.profileToken, "X-Profile-Token leaked to ${request.url}")
        }
    }

    @Test
    fun pinnedForeignRequestRemovesSensitiveExplicitHeaders() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://active.example")
            saveTokens(accessToken = "ACCESS-A", refreshToken = "REFRESH-A", expiresIn = 3600)
        }
        val captured = mutableListOf<CapturedRequest>()
        val snapshot = AuthScopeSnapshot(
            serverId = "server-a",
            profileId = "profile-a",
            serverUrl = "https://silo.example",
            profileToken = "profile-token-a",
        )

        capturingClient(tokenManager, captured).get("https://cdn.example/video") {
            authScope(snapshot)
            header(HttpHeaders.Authorization, "Signed explicit-cdn-credential")
            header("X-Profile-Id", "explicit-profile")
            header("X-Profile-Token", "explicit-profile-token")
        }

        val request = captured.single()
        assertNull(request.authorization)
        assertNull(request.profileId)
        assertNull(request.profileToken)
    }

    @Test
    fun foreignUnauthorizedResponseDoesNotRefresh() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            setProfileId("profile-a")
            setProfileToken("profile-token-a")
            saveTokens(accessToken = "ACCESS-A", refreshToken = "REFRESH-A", expiresIn = 3600)
        }
        val captured = mutableListOf<CapturedRequest>()
        val engine = MockEngine { request ->
            captured += request.capture()
            respond(
                content = "{}",
                status = if (request.url.host == "cdn.example") {
                    HttpStatusCode.Unauthorized
                } else {
                    HttpStatusCode.InternalServerError
                },
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }

        client.get("https://cdn.example/video")

        assertEquals(listOf("https://cdn.example/video"), captured.map(CapturedRequest::url))
        assertNull(captured.single().authorization)
        assertNull(captured.single().profileId)
        assertNull(captured.single().profileToken)
    }

    @Test
    fun crossOriginRedirectDoesNotForwardSiloOrExplicitAuthorizationHeaders() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            setProfileId("profile-a")
            setProfileToken("profile-token-a")
            saveTokens(accessToken = "ACCESS-A", refreshToken = "REFRESH-A", expiresIn = 3600)
        }
        val captured = mutableListOf<CapturedRequest>()
        val provider = object : DeviceMetadataProvider {
            override suspend fun current(): SiloDeviceMetadata =
                SiloDeviceMetadata(
                    id = "device-1",
                    name = "Phone",
                    platform = "android",
                    clientName = "Silo Android",
                    clientVersion = "1.0",
                )
        }
        val engine = MockEngine { request ->
            captured += request.capture()
            if (request.url.host == "silo.example") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://cdn.example/video"),
                )
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        val client = HttpClient(engine) {
            followRedirects = true
            install(SiloAuthPlugin) {
                this.tokenManager = tokenManager
                deviceMetadataProvider = provider
            }
        }

        client.get("https://silo.example/redirect") {
            header(HttpHeaders.Authorization, "Signed explicit-target-credential")
        }

        val downstream = captured.single { it.url == "https://cdn.example/video" }
        assertNull(downstream.authorization)
        assertNull(downstream.profileId)
        assertNull(downstream.profileToken)
        assertEquals(emptyMap(), downstream.siloHeaders)
    }

    @Test
    fun activeServerSwitchDuringCredentialReadFailsClosed() = runTest {
        val tokenManager = SwitchingTokenManager()
        val captured = mutableListOf<CapturedRequest>()

        capturingClient(tokenManager, captured).get("/api/v1/items")

        val request = captured.single()
        assertEquals("https://server-a.example/api/v1/items", request.url)
        assertNull(request.authorization)
        assertNull(request.profileId)
        assertNull(request.profileToken)
    }

    @Test
    fun foreignSkipAuthRequestDoesNotReceiveDeviceMetadata() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
        }
        val captured = mutableListOf<CapturedRequest>()
        val provider = object : DeviceMetadataProvider {
            override suspend fun current(): SiloDeviceMetadata =
                SiloDeviceMetadata(
                    id = "device-1",
                    name = "Phone",
                    platform = "android",
                    clientName = "Silo Android",
                    clientVersion = "1.0",
                )
        }

        capturingClient(
            tokenManager = tokenManager,
            captured = captured,
            deviceMetadataProvider = provider,
        ).get("https://candidate.example/api/v1/auth/device/start") {
            skipSiloAuth()
        }

        assertEquals(emptyMap(), captured.single().siloHeaders)
    }

    @Test
    fun sameOriginSkipAuthRequestKeepsDeviceMetadata() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
        }
        val captured = mutableListOf<CapturedRequest>()
        val provider = deviceMetadataProvider()

        capturingClient(
            tokenManager = tokenManager,
            captured = captured,
            deviceMetadataProvider = provider,
        ).get("https://silo.example/api/v1/auth/login") {
            skipSiloAuth()
        }

        assertEquals("device-1", captured.single().siloHeaders["X-Silo-Device-Id"])
    }

    @Test
    fun crossOriginSkipAuthRedirectRemovesCopiedDeviceMetadata() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
        }
        val captured = mutableListOf<CapturedRequest>()
        val engine = MockEngine { request ->
            captured += request.capture()
            if (request.url.host == "silo.example") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://candidate.example/device"),
                )
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        val client = HttpClient(engine) {
            followRedirects = true
            install(SiloAuthPlugin) {
                this.tokenManager = tokenManager
                deviceMetadataProvider = deviceMetadataProvider()
            }
        }

        client.get("https://silo.example/api/v1/auth/login") {
            skipSiloAuth()
        }

        assertEquals(
            emptyMap(),
            captured.single { it.url == "https://candidate.example/device" }.siloHeaders,
        )
    }

    @Test
    fun serverSwitchWhileRequestIsInFlightDoesNotRetryWithNewServerCredentials() = runTest {
        val tokenManager = InFlightSwitchingTokenManager()
        val captured = mutableListOf<CapturedRequest>()
        val engine = MockEngine { request ->
            captured += request.capture()
            if (captured.size == 1) {
                tokenManager.switchToServerB()
                respond("{}", HttpStatusCode.Unauthorized)
            } else {
                respond("{}", HttpStatusCode.OK)
            }
        }
        val client = HttpClient(engine) {
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }

        client.get("https://server-a.example/api/v1/items")

        assertEquals(1, captured.size)
        assertEquals("Bearer server-a-access", captured.single().authorization)
        assertEquals(0, tokenManager.invalidations)
    }

    @Test
    fun normalUnauthorizedRefreshUsesOnlyCapturedScopeOperations() = runTest {
        val tokenManager = ScopedRefreshTokenManager()
        val paths = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                when {
                    request.url.encodedPath.endsWith("/auth/refresh") ->
                        respond(
                            content = """
                                {
                                  "access_token": "server-a-fresh-access",
                                  "refresh_token": "server-a-fresh-refresh",
                                  "expires_in": 3600
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    paths.count { it.endsWith("/catalog/home") } == 1 ->
                        respond("{}", HttpStatusCode.Unauthorized)
                    else -> respond("{}", HttpStatusCode.OK)
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }

        client.get("https://server-a.example/api/v1/catalog/home")

        assertEquals(
            listOf(
                "/api/v1/catalog/home",
                "/api/v1/auth/refresh",
                "/api/v1/catalog/home",
            ),
            paths,
        )
        assertEquals(0, tokenManager.activeRefreshReads)
        assertEquals(0, tokenManager.activeSaves)
        assertEquals(2, tokenManager.scopedRefreshReads)
        assertEquals(1, tokenManager.scopedSaves)
    }

    @Test
    fun switchBeforeRefreshTokenReadNeverReadsNewActiveServerToken() = runTest {
        val tokenManager = ScopedRefreshTokenManager(switchBeforeRefreshTokenRead = true)
        val paths = executeRefreshScenario(tokenManager, HttpStatusCode.OK)

        assertEquals(listOf("/api/v1/catalog/home", "/api/v1/auth/refresh"), paths)
        assertEquals(0, tokenManager.activeRefreshReads)
        assertEquals(1, tokenManager.scopedRefreshReads)
        assertEquals(0, tokenManager.activeSaves)
    }

    @Test
    fun switchDuringPostResponseCredentialReadSavesOnlyCapturedScope() = runTest {
        val tokenManager = ScopedRefreshTokenManager(switchDuringPostResponseRead = true)
        executeRefreshScenario(tokenManager, HttpStatusCode.OK)

        assertEquals(0, tokenManager.activeSaves)
        assertEquals(1, tokenManager.scopedSaves)
        assertEquals("server-a-fresh-access", tokenManager.scopedAccessToken)
    }

    @Test
    fun switchAtRefreshRejectionInvalidatesOnlyCapturedScope() = runTest {
        val tokenManager = ScopedRefreshTokenManager(switchDuringInvalidation = true)
        executeRefreshScenario(tokenManager, HttpStatusCode.Unauthorized)

        assertEquals(0, tokenManager.activeInvalidations)
        assertEquals(1, tokenManager.scopedInvalidations)
        assertEquals("server-a", tokenManager.invalidatedScope?.serverId)
        assertEquals("server-b-access", tokenManager.activeAccessToken)
    }

    private suspend fun executeRefreshScenario(
        tokenManager: ScopedRefreshTokenManager,
        refreshStatus: HttpStatusCode,
    ): List<String> {
        val paths = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                when {
                    request.url.encodedPath.endsWith("/auth/refresh") ->
                        respond(
                            content = if (refreshStatus.isSuccess()) {
                                """
                                    {
                                      "access_token": "server-a-fresh-access",
                                      "refresh_token": "server-a-fresh-refresh",
                                      "expires_in": 3600
                                    }
                                """.trimIndent()
                            } else {
                                """{"error":"unauthorized","message":"expired"}"""
                            },
                            status = refreshStatus,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    paths.count { it.endsWith("/catalog/home") } == 1 ->
                        respond("{}", HttpStatusCode.Unauthorized)
                    else -> respond("{}", HttpStatusCode.OK)
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }
        client.get("https://server-a.example/api/v1/catalog/home")
        return paths
    }

    private data class CapturedRequest(
        val url: String,
        val authorization: String?,
        val profileId: String?,
        val profileToken: String?,
        val siloHeaders: Map<String, String>,
    )

    private fun io.ktor.client.request.HttpRequestData.capture(): CapturedRequest =
        CapturedRequest(
            url = url.toString(),
            authorization = headers[HttpHeaders.Authorization],
            profileId = headers["X-Profile-Id"],
            profileToken = headers["X-Profile-Token"],
            siloHeaders = headers.entries()
                .filter { (name, _) -> name.startsWith("X-Silo-", ignoreCase = true) }
                .associate { (name, values) -> name to values.joinToString(",") },
        )

    private fun capturingClient(
        tokenManager: TokenManager,
        captured: MutableList<CapturedRequest>,
        deviceMetadataProvider: DeviceMetadataProvider? = null,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                captured += request.capture()
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(SiloAuthPlugin) {
                this.tokenManager = tokenManager
                this.deviceMetadataProvider = deviceMetadataProvider
            }
        }

    private fun deviceMetadataProvider(): DeviceMetadataProvider =
        object : DeviceMetadataProvider {
            override suspend fun current(): SiloDeviceMetadata =
                SiloDeviceMetadata(
                    id = "device-1",
                    name = "Phone",
                    platform = "android",
                    clientName = "Silo Android",
                    clientVersion = "1.0",
                )
        }

    private class SwitchingTokenManager(
        private val delegate: TokenManager = TokenManagerImpl(),
    ) : TokenManager by delegate {
        private var switched = false
        private var serverUrlReads = 0

        override suspend fun getServerUrl(): String {
            serverUrlReads += 1
            if (serverUrlReads > 1) switched = false
            return if (switched) "https://server-b.example" else "https://server-a.example"
        }

        override suspend fun getCurrentServerId(): String =
            if (switched) "server-b" else "server-a"

        override suspend fun getAccessToken(): String {
            switched = true
            return "server-b-access"
        }

        override suspend fun getProfileId(): String = "server-b-profile"

        override suspend fun getProfileToken(): String = "server-b-profile-token"

        // Interface delegation forwards the DEFAULT getProfileIdentity() to the
        // delegate, silently bypassing the two overrides above — so anything
        // reading the identity as a pair would test the wrong values.
        override suspend fun getProfileIdentity(): ProfileIdentity =
            ProfileIdentity(getProfileId(), getProfileToken())
    }

    private class InFlightSwitchingTokenManager(
        private val delegate: TokenManager = TokenManagerImpl(),
    ) : TokenManager by delegate {
        private var activeServer = "server-a"
        var invalidations = 0
            private set

        fun switchToServerB() {
            activeServer = "server-b"
        }

        override suspend fun getCurrentServerId(): String = activeServer

        override suspend fun getServerUrl(): String = "https://$activeServer.example"

        override suspend fun getAccessToken(): String = "$activeServer-access"

        override suspend fun getRefreshToken(): String = "$activeServer-refresh"

        override suspend fun getProfileId(): String = "$activeServer-profile"

        override suspend fun getProfileIdentity(): ProfileIdentity =
            ProfileIdentity(getProfileId(), getProfileToken())

        override suspend fun getProfileToken(): String = "$activeServer-profile-token"

        override suspend fun invalidateSession() {
            invalidations += 1
        }
    }

    private class ScopedRefreshTokenManager(
        private val switchBeforeRefreshTokenRead: Boolean = false,
        private val switchDuringPostResponseRead: Boolean = false,
        private val switchDuringInvalidation: Boolean = false,
        private val delegate: TokenManager = TokenManagerImpl(),
    ) : TokenManager by delegate {
        private val scope = AuthScopeSnapshot(
            serverId = "server-a",
            profileId = null,
            serverUrl = "https://server-a.example",
            profileToken = null,
            identityGeneration = 7L,
        )
        private var activeServer = "server-a"
        private var accessToken = "server-a-expired-access"
        private var armSwitchAfterServerUrlRead = false
        private var activeAccessReads = 0
        var activeRefreshReads = 0
            private set
        var activeSaves = 0
            private set
        var scopedRefreshReads = 0
            private set
        var scopedSaves = 0
            private set
        var activeInvalidations = 0
            private set
        var scopedInvalidations = 0
            private set
        var invalidatedScope: AuthScopeSnapshot? = null
            private set
        val scopedAccessToken: String get() = accessToken
        val activeAccessToken: String
            get() = if (activeServer == "server-a") accessToken else "server-b-access"

        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = scope

        override suspend fun getCurrentServerId(): String = activeServer

        override suspend fun getServerUrl(): String {
            val url = "https://$activeServer.example"
            if (armSwitchAfterServerUrlRead) {
                armSwitchAfterServerUrlRead = false
                activeServer = "server-b"
            }
            return url
        }

        override suspend fun getAccessToken(): String {
            activeAccessReads += 1
            if (switchBeforeRefreshTokenRead && activeAccessReads > 1) {
                armSwitchAfterServerUrlRead = true
            }
            return activeAccessToken
        }

        override suspend fun getRefreshToken(): String {
            activeRefreshReads += 1
            val token = "$activeServer-refresh"
            if (switchDuringPostResponseRead && activeRefreshReads == 2) {
                activeServer = "server-b"
            }
            return token
        }

        override suspend fun saveTokens(
            accessToken: String,
            refreshToken: String,
            expiresIn: Long,
        ) {
            activeSaves += 1
            this.accessToken = accessToken
        }

        override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String {
            if (switchBeforeRefreshTokenRead) armSwitchAfterServerUrlRead = true
            return accessToken
        }

        override suspend fun getRefreshTokenForScope(scope: AuthScopeSnapshot): String {
            scopedRefreshReads += 1
            if (switchDuringPostResponseRead && scopedRefreshReads == 2) {
                activeServer = "server-b"
            }
            return "server-a-refresh"
        }

        override suspend fun saveTokensForScope(
            scope: AuthScopeSnapshot,
            accessToken: String,
            refreshToken: String,
            expiresIn: Long,
        ) {
            scopedSaves += 1
            this.accessToken = accessToken
        }

        override suspend fun invalidateSession() {
            if (switchDuringInvalidation) activeServer = "server-b"
            activeInvalidations += 1
        }

        override suspend fun invalidateSessionForScope(scope: AuthScopeSnapshot): Boolean {
            if (switchDuringInvalidation) activeServer = "server-b"
            scopedInvalidations += 1
            invalidatedScope = scope
            return true
        }
    }
}
