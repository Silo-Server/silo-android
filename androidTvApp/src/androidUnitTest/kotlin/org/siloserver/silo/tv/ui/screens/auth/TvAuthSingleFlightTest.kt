package org.siloserver.silo.tv.ui.screens.auth

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloAuthPlugin
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.DeviceLoginApi
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.DeviceLoginRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TvAuthSingleFlightTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        Dispatchers.resetMain()
    }

    // Cancel viewModelScope coroutines BEFORE resetting Main: a coroutine
    // still parked on Dispatchers.Main when a later test class calls
    // setMain throws IllegalStateException from TestMainDispatcher — the
    // CI-only cross-class flake that failed the v0.3.4 tag build.
    private val createdViewModels = mutableListOf<androidx.lifecycle.ViewModel>()

    private fun <T : androidx.lifecycle.ViewModel> track(viewModel: T): T {
        createdViewModels += viewModel
        return viewModel
    }


    @Test
    fun loginSubmitIgnoresSecondClickWhileLoading() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val recorder = AuthRequestRecorder("/api/v2/auth/login", release)
        val tokenManager = SingleFlightTokenManager()
        val viewModel = track(TvLoginViewModel(
            authRepository = AuthRepository(AuthApi(recorder.client(tokenManager), ApiV2Gate.Unrestricted), tokenManager),
            tokenManager = tokenManager,
            deviceLogin = DeviceLoginRepository(NeverCompletingDeviceLoginApi),
        ))
        advanceUntilIdle()

        viewModel.onUsernameChanged("jim")
        viewModel.onPasswordChanged("Amsterdam123!")
        viewModel.onLoginClick()
        viewModel.onLoginClick()
        advanceUntilIdle()

        recorder.awaitFirstRequestStarted()
        assertEquals(1, recorder.matchingRequestCount)
        release.complete(Unit)
    }

    @Test
    fun setupSubmitIgnoresSecondClickWhileLoading() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val recorder = AuthRequestRecorder("/api/v2/auth/setup", release)
        val viewModel = track(TvSetupViewModel(
            AuthRepository(AuthApi(recorder.client(SingleFlightTokenManager()), ApiV2Gate.Unrestricted), SingleFlightTokenManager()),
        ))

        viewModel.onUsernameChanged("jim")
        viewModel.onEmailChanged("jim@example.com")
        viewModel.onPasswordChanged("Amsterdam123!")
        viewModel.onCreateAccountClick()
        viewModel.onCreateAccountClick()
        advanceUntilIdle()

        recorder.awaitFirstRequestStarted()
        assertEquals(1, recorder.matchingRequestCount)
        release.complete(Unit)
    }

    @Test
    fun signupSubmitIgnoresSecondClickWhileLoading() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val recorder = AuthRequestRecorder("/api/v2/auth/signup", release)
        val viewModel = track(TvSignupViewModel(
            AuthRepository(AuthApi(recorder.client(SingleFlightTokenManager()), ApiV2Gate.Unrestricted), SingleFlightTokenManager()),
        ))

        viewModel.onUsernameChanged("jim")
        viewModel.onEmailChanged("jim@example.com")
        viewModel.onPasswordChanged("Amsterdam123!")
        viewModel.onInviteCodeChanged("invite")
        viewModel.onSignupClick()
        viewModel.onSignupClick()
        advanceUntilIdle()

        recorder.awaitFirstRequestStarted()
        assertEquals(1, recorder.matchingRequestCount)
        release.complete(Unit)
    }
}

private class AuthRequestRecorder(
    private val matchingPath: String,
    private val release: CompletableDeferred<Unit>,
) {
    var matchingRequestCount = 0
        private set
    val firstRequestStarted = CompletableDeferred<Unit>()

    fun client(tokenManager: TokenManager) = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                if (request.url.encodedPath != matchingPath) {
                    return@addHandler respond(
                        content = """{"error":"unexpected_request","message":"Unexpected request"}""",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                matchingRequestCount += 1
                firstRequestStarted.complete(Unit)
                release.await()
                respond(
                    content = singleFlightLoginJson("access-$matchingRequestCount", "refresh-$matchingRequestCount"),
                    status = if (matchingPath.endsWith("/login")) HttpStatusCode.OK else HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        install(ContentNegotiation) { json(SiloJson) }
        install(SiloAuthPlugin) { this.tokenManager = tokenManager }
    }
}

private suspend fun AuthRequestRecorder.awaitFirstRequestStarted() {
    withContext(Dispatchers.Default) {
        withTimeout(1_000) { firstRequestStarted.await() }
    }
}

private fun singleFlightLoginJson(accessToken: String, refreshToken: String): String = """
    {
      "access_token": "$accessToken",
      "refresh_token": "$refreshToken",
      "expires_in": 3600,
      "user": {
        "id": "1",
        "username": "jim",
        "email": "jim@example.com",
        "role": "user",
        "download_allowed": true
      }
    }
""".trimIndent()

private object NeverCompletingDeviceLoginApi : DeviceLoginApi {
    override suspend fun startDeviceLogin(
        deviceName: String?,
        devicePlatform: String?,
    ): ApiResult<DeviceLoginStartResponse> = ApiResult.NetworkError(IllegalStateException("disabled in test"))

    override suspend fun pollDeviceLogin(deviceCode: String): ApiResult<DeviceLoginPollResponse> =
        error("Not used")

    override suspend fun lookupDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginLookupResponse> =
        error("Not used")

    override suspend fun approveDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginDecisionResponse> =
        error("Not used")

    override suspend fun denyDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginDecisionResponse> =
        error("Not used")
}

private class SingleFlightTokenManager : TokenManager {
    private var accountGeneration = 0L
    override suspend fun captureAccountSessionExpectation() = org.siloserver.silo.network.AccountSessionExpectation(accountGeneration, null, getServerUrl())
    override suspend fun replaceAccountSession(serverId: String?, serverUrl: String?, accessToken: String, refreshToken: String,
        expiresIn: Long, profileId: String?, profileToken: String?, expectedIdentity: org.siloserver.silo.network.AccountSessionExpectation?) {
        check(expectedIdentity == null || expectedIdentity.generation == accountGeneration)
        accountGeneration++
        saveTokens(accessToken, refreshToken, expiresIn)
    }

    private var accessToken: String? = null
    private var refreshToken: String? = null
    override val sessionExpired = MutableSharedFlow<Unit>()
    override suspend fun getAccessToken(): String? = accessToken
    override suspend fun getRefreshToken(): String? = refreshToken
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
    }
    override suspend fun clearTokens() {
        accessToken = null
        refreshToken = null
    }
    override suspend fun invalidateSession() = Unit
    override suspend fun getProfileId(): String? = null
    override suspend fun setProfileId(profileId: String?) = Unit
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) = Unit
    override suspend fun getServerUrl(): String = "https://silo.test"
    override suspend fun setServerUrl(url: String) = Unit
    override suspend fun getCurrentServerId(): String? = null
    override suspend fun switchActiveServer(serverId: String?) = Unit
    override suspend fun signOutCurrentServer() = Unit
}
