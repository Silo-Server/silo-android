package org.siloserver.silo.common.player

import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.canonicalHttpOrigin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MediaAuthInterceptorTest {
    @Test
    fun `redirect from approved origin to unapproved cleartext is blocked before downstream`() {
        val origin = MockWebServer()
        val downstream = MockWebServer()
        origin.start()
        downstream.start()
        try {
            origin.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", downstream.url("/asset")),
            )
            val approvedOrigin = canonicalHttpOrigin(origin.url("/").toString())
            val consent = object : CleartextOriginConsent {
                override suspend fun isApproved(origin: String): Boolean =
                    canonicalHttpOrigin(origin) == approvedOrigin
            }
            val client = buildPlayerOkHttpClient(consent)
            val request = Request.Builder()
                .url(origin.url("/redirect"))
                .header("X-Stream-Signature", "secret-plan-header")
                .build()

            assertFailsWith<CleartextMediaOriginNotApprovedException> {
                client.newCall(request).execute().close()
            }
            assertEquals(1, origin.requestCount)
            assertEquals(0, downstream.requestCount)
        } finally {
            origin.shutdown()
            downstream.shutdown()
        }
    }

    @Test
    fun `async cleartext redirect rejection reaches the failure callback`() {
        val origin = MockWebServer()
        val downstream = MockWebServer()
        val uncaught = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cleartext-callback-test").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                    uncaught.set(error)
                }
            }
        }
        origin.start()
        downstream.start()
        try {
            origin.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", downstream.url("/asset")),
            )
            val approvedOrigin = canonicalHttpOrigin(origin.url("/").toString())
            val consent = object : CleartextOriginConsent {
                override suspend fun isApproved(origin: String): Boolean =
                    canonicalHttpOrigin(origin) == approvedOrigin
            }
            val client = buildPlayerOkHttpClient(consent).newBuilder()
                .dispatcher(Dispatcher(executor))
                .build()
            val completed = CountDownLatch(1)
            var failure: IOException? = null

            client.newCall(Request.Builder().url(origin.url("/redirect")).build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        failure = error
                        completed.countDown()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                        completed.countDown()
                    }
                })

            assertTrue(
                completed.await(2, TimeUnit.SECONDS),
                "unchecked interceptor failure escaped the OkHttp dispatcher",
            )
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertNull(uncaught.get(), "interceptor failure escaped the OkHttp dispatcher")
            assertTrue(failure is CleartextMediaOriginNotApprovedException)
            assertEquals("Cleartext media origin is not approved", failure?.message)
            assertEquals(1, origin.requestCount)
            assertEquals(0, downstream.requestCount)
        } finally {
            executor.shutdownNow()
            origin.shutdown()
            downstream.shutdown()
        }
    }

    @Test
    fun `adds auth and active profile headers to media and reader requests`() {
        val tokenManager = TokenManagerImpl()
        runBlocking {
            tokenManager.setServerUrl("https://lib.strm.cafe")
            tokenManager.saveTokens("access-token", "refresh-token", expiresIn = 3600)
            tokenManager.setProfileId("profile-1")
            tokenManager.setProfileToken("profile-token")
        }

        listOf(
            "https://lib.strm.cafe/api/v1/media/movies/1/stream",
            "https://lib.strm.cafe/api/v1/ebooks/book/files/7/read",
        ).forEach { url ->
            val chain = CapturingChain(
                Request.Builder()
                    .url(url)
                    .build(),
            )

            MediaAuthInterceptor(tokenManager, refreshClient = OkHttpClient()).intercept(chain)

            val request = chain.capturedRequest ?: error("request was not captured")
            assertEquals("Bearer access-token", request.header("Authorization"))
            assertEquals("profile-1", request.header("X-Profile-Id"))
            assertEquals("profile-token", request.header("X-Profile-Token"))
        }
    }

    @Test
    fun `foreign origins receive no auth or profile headers`() {
        val tokenManager = TokenManagerImpl()
        runBlocking {
            tokenManager.setServerUrl("https://lib.strm.cafe")
            tokenManager.saveTokens("access-token", "refresh-token", expiresIn = 3600)
            tokenManager.setProfileId("profile-1")
            tokenManager.setProfileToken("profile-token")
        }

        listOf(
            "https://cdn.lib.strm.cafe/video",
            "https://lib.strm.cafe:444/video",
            "http://lib.strm.cafe/video",
        ).forEach { url ->
            val chain = CapturingChain(
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Signed explicit-credential")
                    .header("X-Profile-Id", "explicit-profile")
                    .header("X-Profile-Token", "explicit-profile-token")
                    .build(),
            )

            MediaAuthInterceptor(tokenManager, refreshClient = OkHttpClient()).intercept(chain)

            val request = chain.capturedRequest ?: error("request was not captured")
            assertNull(request.header("Authorization"), "Authorization leaked to $url")
            assertNull(request.header("X-Profile-Id"), "X-Profile-Id leaked to $url")
            assertNull(request.header("X-Profile-Token"), "X-Profile-Token leaked to $url")
        }
    }

    @Test
    fun `foreign unauthorized response causes no refresh attempt`() {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-access",
            refreshToken = "refresh-token",
            serverUrl = "https://lib.strm.cafe",
            serverId = "server-a",
        )
        var refreshRequests = 0
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                refreshRequests += 1
                responseFor(chain.request(), code = 500)
            }
            .build()
        val chain = SequenceChain(
            Request.Builder()
                .url("https://cdn.example/video")
                .build(),
            responseCodes = listOf(401, 401),
        )

        MediaAuthInterceptor(tokenManager, refreshClient = refreshClient).intercept(chain).close()

        assertEquals(0, refreshRequests)
        assertEquals(1, chain.proceedCalls)
        assertFalse(tokenManager.invalidatedSession)
    }

    @Test
    fun `server switch while snapshot is read cannot pair old credentials with new origin`() {
        val tokenManager = FakeTokenManager(
            accessToken = "server-a-access",
            refreshToken = "server-a-refresh",
            serverUrl = "https://server-a.example",
            serverId = "server-a",
        ).apply {
            runBlocking {
                setProfileId("server-a-profile")
                setProfileToken("server-a-profile-token")
            }
            onGetServerUrl = {
                serverId = "server-b"
                serverUrl = "https://server-b.example"
            }
        }
        val chain = CapturingChain(
            Request.Builder()
                .url("https://server-b.example/video")
                .build(),
        )

        MediaAuthInterceptor(tokenManager, refreshClient = OkHttpClient()).intercept(chain)

        val request = chain.capturedRequest ?: error("request was not captured")
        assertNull(request.header("Authorization"))
        assertNull(request.header("X-Profile-Id"))
        assertNull(request.header("X-Profile-Token"))
    }

    @Test
    fun `cross origin redirect strips silo and explicit authorization headers`() {
        val origin = MockWebServer()
        val downstream = MockWebServer()
        origin.start()
        downstream.start()
        try {
            origin.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", downstream.url("/asset")),
            )
            downstream.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            val tokenManager = FakeTokenManager(
                accessToken = null,
                refreshToken = "refresh-token",
                serverUrl = origin.url("/").toString(),
                serverId = "server-a",
            ).apply {
                runBlocking {
                    setProfileId("profile-1")
                    setProfileToken("profile-token")
                }
            }
            val client = buildPlayerOkHttpClient()
                .newBuilder()
                .addInterceptor(MediaAuthInterceptor(tokenManager, refreshClient = OkHttpClient()))
                .build()
            val request = Request.Builder()
                .url(origin.url("/redirect"))
                .header("Authorization", "Signed explicit-target-credential")
                .header("X-Silo-Device-Id", "device-1")
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }

            val received = downstream.takeRequest()
            assertNull(received.getHeader("Authorization"))
            assertNull(received.getHeader("X-Profile-Id"))
            assertNull(received.getHeader("X-Profile-Token"))
            assertNull(received.getHeader("X-Silo-Device-Id"))
        } finally {
            origin.shutdown()
            downstream.shutdown()
        }
    }

    @Test
    fun `retry never applies credentials from a newly active foreign server`() {
        val tokenManager = FakeTokenManager(
            accessToken = "server-a-expired",
            refreshToken = "server-a-refresh",
            serverUrl = "https://server-a.example",
            serverId = "server-a",
        )
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                responseFor(
                    chain.request(),
                    code = 200,
                    body = """
                        {
                          "access_token": "server-a-fresh",
                          "refresh_token": "server-a-fresh-refresh",
                          "expires_in": 3600
                        }
                    """.trimIndent(),
                )
            }
            .build()
        tokenManager.onSaveTokens = {
            tokenManager.switchScope(
                accessToken = "server-b-access",
                refreshToken = "server-b-refresh",
                serverUrl = "https://server-b.example",
                serverId = "server-b",
            )
        }
        val chain = SequenceChain(
            Request.Builder()
                .url("https://server-a.example/video")
                .build(),
            responseCodes = listOf(401, 401),
        )

        MediaAuthInterceptor(tokenManager, refreshClient = refreshClient).intercept(chain).close()

        val retry = chain.capturedRequests.last()
        assertNull(retry.header("Authorization"))
        assertNull(retry.header("X-Profile-Id"))
        assertNull(retry.header("X-Profile-Token"))
    }

    @Test
    fun `failed refresh invalidates session`() {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-access",
            refreshToken = "refresh-token",
            serverUrl = "https://lib.strm.cafe",
            serverId = "server-a",
        )
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                responseFor(chain.request(), code = 401, body = """{"error":"expired"}""")
            }
            .build()
        val chain = SequenceChain(
            Request.Builder()
                .url("https://lib.strm.cafe/api/v1/ebooks/book/files/7/read")
                .build(),
            responseCodes = listOf(401, 401),
        )

        MediaAuthInterceptor(tokenManager, refreshClient = refreshClient).intercept(chain).close()

        assertTrue(tokenManager.invalidatedSession)
        assertNull(runBlocking { tokenManager.getAccessToken() })
    }

    @Test
    fun `gateway refresh failure keeps active session`() {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-access",
            refreshToken = "refresh-token",
            serverUrl = "https://lib.strm.cafe",
            serverId = "server-a",
        )
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                responseFor(
                    chain.request(),
                    code = 503,
                    body = """{"error":"unavailable","message":"origin down"}""",
                )
            }
            .build()
        val chain = SequenceChain(
            Request.Builder()
                .url("https://lib.strm.cafe/api/v1/ebooks/book/files/7/read")
                .build(),
            responseCodes = listOf(401, 401),
        )

        MediaAuthInterceptor(tokenManager, refreshClient = refreshClient).intercept(chain).close()

        assertFalse(tokenManager.invalidatedSession)
        assertEquals("expired-access", runBlocking { tokenManager.getAccessToken() })
        assertEquals("refresh-token", runBlocking { tokenManager.getRefreshToken() })
    }

    @Test
    fun `new token from a different server does not satisfy a failed request`() {
        val tokenManager = FakeTokenManager(
            accessToken = "server-a-access",
            refreshToken = "server-a-refresh",
            serverUrl = "https://server-a.example",
            serverId = "server-a",
        )
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor { error("A cross-server credential change must not trigger refresh") }
            .build()
        val session = MediaAuthSession(tokenManager, refreshClient)
        val failedSnapshot = runBlocking { session.snapshot() }

        tokenManager.serverId = "server-b"
        runBlocking {
            tokenManager.saveTokens(
                accessToken = "server-b-access",
                refreshToken = "server-b-refresh",
                expiresIn = 3600,
            )
        }

        assertFalse(runBlocking { session.refreshIfStale(failedSnapshot) })
    }

    @Test
    fun `refresh is not posted when active server changes while credentials are read`() {
        val tokenManager = FakeTokenManager(
            accessToken = "server-a-access",
            refreshToken = "server-a-refresh",
            serverUrl = "https://server-a.example",
            serverId = "server-a",
        )
        var refreshRequests = 0
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                refreshRequests += 1
                responseFor(chain.request(), code = 500)
            }
            .build()
        val session = MediaAuthSession(tokenManager, refreshClient)
        val failedSnapshot = runBlocking { session.snapshot() }
        tokenManager.onGetServerUrl = {
            tokenManager.serverId = "server-b"
            tokenManager.serverUrl = "https://server-b.example"
        }

        assertFalse(runBlocking { session.refreshIfStale(failedSnapshot) })
        assertEquals(0, refreshRequests)
    }

    @Test
    fun `refresh response is discarded when active server changes mid refresh`() {
        val tokenManager = FakeTokenManager(
            accessToken = "expired-access",
            refreshToken = "refresh-token",
            serverUrl = "https://lib.strm.cafe",
            serverId = "server-a",
        )
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                tokenManager.serverId = "server-b"
                responseFor(
                    chain.request(),
                    code = 200,
                    body = """
                        {
                          "access_token": "server-a-new-access",
                          "refresh_token": "server-a-new-refresh",
                          "expires_in": 3600
                        }
                    """.trimIndent(),
                )
            }
            .build()
        val chain = SequenceChain(
            Request.Builder()
                .url("https://lib.strm.cafe/api/v1/ebooks/book/files/7/read")
                .build(),
            responseCodes = listOf(401, 401),
        )

        MediaAuthInterceptor(tokenManager, refreshClient = refreshClient).intercept(chain).close()

        assertEquals("expired-access", runBlocking { tokenManager.getAccessToken() })
        assertEquals("refresh-token", runBlocking { tokenManager.getRefreshToken() })
        assertFalse(tokenManager.savedTokens)
    }

    @Test
    fun `refresh response is discarded when signed out mid refresh`() {
        // Logout revokes the access token server-side, so media requests 401
        // exactly during sign-out; the refresh completing AFTER clearTokens
        // must not re-persist a session (CodeRabbit PR#44 — mirrors the
        // SiloAuthPlugin race test).
        val tokenManager = FakeTokenManager(
            accessToken = "expired-access",
            refreshToken = "refresh-token",
            serverUrl = "https://lib.strm.cafe",
            serverId = "server-a",
        )
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // Sign-out lands while the refresh round-trip is in flight.
                runBlocking { tokenManager.clearTokens() }
                responseFor(
                    chain.request(),
                    code = 200,
                    body = """
                        {
                          "access_token": "fresh-access",
                          "refresh_token": "fresh-refresh",
                          "expires_in": 3600
                        }
                    """.trimIndent(),
                )
            }
            .build()
        val chain = SequenceChain(
            Request.Builder()
                .url("https://lib.strm.cafe/api/v1/ebooks/book/files/7/read")
                .build(),
            responseCodes = listOf(401, 401),
        )

        MediaAuthInterceptor(tokenManager, refreshClient = refreshClient).intercept(chain).close()

        assertEquals(null, runBlocking { tokenManager.getAccessToken() })
        assertEquals(null, runBlocking { tokenManager.getRefreshToken() })
        assertFalse(tokenManager.savedTokens)
    }

    private class CapturingChain(
        private val request: Request,
    ) : Interceptor.Chain {
        var capturedRequest: Request? = null

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            capturedRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ByteArray(0).toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun readTimeoutMillis(): Int = 0
        override fun writeTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    }

    private class SequenceChain(
        private val request: Request,
        private val responseCodes: List<Int>,
    ) : Interceptor.Chain {
        private var calls = 0
        val proceedCalls: Int get() = calls
        val capturedRequests = mutableListOf<Request>()

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            capturedRequests += request
            val code = responseCodes.getOrElse(calls) { responseCodes.last() }
            calls += 1
            return mediaAuthTestResponseFor(request, code)
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun readTimeoutMillis(): Int = 0
        override fun writeTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private class FakeTokenManager(
        private var accessToken: String?,
        private var refreshToken: String?,
        var serverUrl: String,
        var serverId: String?,
    ) : TokenManager {
        var onGetServerUrl: (() -> Unit)? = null
        var onSaveTokens: (() -> Unit)? = null
        var invalidatedSession = false
            private set
        var savedTokens = false
            private set

        private val _sessionExpired = MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

        override suspend fun getAccessToken(): String? = accessToken
        override suspend fun getRefreshToken(): String? = refreshToken
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
            savedTokens = true
            this.accessToken = accessToken
            this.refreshToken = refreshToken
            onSaveTokens?.invoke()
        }

        override suspend fun clearTokens() {
            accessToken = null
            refreshToken = null
            profileId = null
            profileToken = null
        }

        override suspend fun invalidateSession() {
            invalidatedSession = true
            clearTokens()
            _sessionExpired.tryEmit(Unit)
        }

        private var profileId: String? = null
        private var profileToken: String? = null

        override suspend fun getProfileId(): String? = profileId
        override suspend fun setProfileId(profileId: String?) {
            this.profileId = profileId
        }

        override suspend fun getProfileToken(): String? = profileToken
        override suspend fun setProfileToken(token: String?) {
            profileToken = token
        }

        override suspend fun getServerUrl(): String {
            onGetServerUrl?.invoke()
            return serverUrl
        }
        override suspend fun setServerUrl(url: String) {
            serverUrl = url
        }

        override suspend fun getCurrentServerId(): String? = serverId
        override suspend fun switchActiveServer(serverId: String?) {
            this.serverId = serverId
        }

        override suspend fun signOutCurrentServer() {
            clearTokens()
        }

        fun switchScope(
            accessToken: String?,
            refreshToken: String?,
            serverUrl: String,
            serverId: String?,
        ) {
            this.accessToken = accessToken
            this.refreshToken = refreshToken
            this.serverUrl = serverUrl
            this.serverId = serverId
        }
    }

    private fun responseFor(
        request: Request,
        code: Int,
        body: String = "",
    ): Response =
        mediaAuthTestResponseFor(request, code, body)
}

private fun mediaAuthTestResponseFor(
    request: Request,
    code: Int,
    body: String = "",
): Response =
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code in 200..299) "OK" else "Error")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
