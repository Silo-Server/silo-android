package org.siloserver.silo.tv.ui.screens.auth

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.network.SiloAuthPlugin
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.common.network.CleartextConsentStore
import org.siloserver.silo.model.auth.SetupStatusResponse
import org.siloserver.silo.model.auth.SignupStatusResponse
import org.siloserver.silo.repository.AuthRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TvServerSetupPersistenceTest {
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
    fun failedProbeDoesNotReplacePreviouslyActiveServerUrl() = runTest(dispatcher) {
        val tokenManager = RecordingTokenManager(serverUrl = "https://old.silo")
        val repository = AuthRepository(
            authApi = AuthApi(
                HttpClient(MockEngine) {
                    engine {
                        addHandler {
                            throw IOException("offline")
                        }
                    }
                    install(ContentNegotiation) { json(SiloJson) }
                    install(SiloAuthPlugin) { this.tokenManager = tokenManager }
                }, ApiV2Gate.Unrestricted),
            tokenManager = tokenManager,
        )
        val viewModel = track(TvServerSetupViewModel(repository, FakeCleartextConsentStore()))

        viewModel.onServerUrlChanged("bad.silo")
        viewModel.onConnectClick()
        advanceUntilIdle()

        assertEquals("https://old.silo", tokenManager.getServerUrl())
        assertEquals(
            emptyList(),
            tokenManager.serverUrlWrites,
            "Failed probes must not persist candidate URLs.",
        )
    }

    @Test
    fun successfulHttpFallbackStopsForConfirmationBeforePersistence() = runTest(dispatcher) {
        val tokenManager = RecordingTokenManager()
        val viewModel = track(viewModelFor(tokenManager))

        viewModel.onServerUrlChanged("silo.lan")
        viewModel.onConnectClick()
        awaitSettled(viewModel)

        assertEquals("http://silo.lan", viewModel.uiState.value.pendingCleartextUrl)
        assertEquals(emptyList(), tokenManager.serverUrlWrites)
        assertNull(viewModel.uiState.value.navigateTo)

        viewModel.confirmCleartextConnection()
        awaitSettled(viewModel)

        assertEquals(listOf("http://silo.lan"), tokenManager.serverUrlWrites)
        assertEquals(TvServerSetupDestination.Login(signupEnabled = true), viewModel.uiState.value.navigateTo)
    }

    @Test
    fun cancelCleartextConnectionClearsPendingWithoutPersistence() = runTest(dispatcher) {
        val tokenManager = RecordingTokenManager()
        val viewModel = track(viewModelFor(tokenManager))

        viewModel.onServerUrlChanged("silo.lan")
        viewModel.onConnectClick()
        awaitSettled(viewModel)
        viewModel.cancelCleartextConnection()

        assertNull(viewModel.uiState.value.pendingCleartextUrl)
        assertEquals(emptyList(), tokenManager.serverUrlWrites)
        assertNull(viewModel.uiState.value.navigateTo)
    }

    @Test
    fun cleartextApprovalIsOriginSpecific() = runTest(dispatcher) {
        val tokenManager = RecordingTokenManager()
        val approvals = FakeCleartextConsentStore().apply { approve("http://other.lan") }
        val viewModel = track(viewModelFor(tokenManager, approvals))

        viewModel.onServerUrlChanged("silo.lan")
        viewModel.onConnectClick()
        awaitSettled(viewModel)

        assertEquals("http://silo.lan", viewModel.uiState.value.pendingCleartextUrl)
        assertEquals(emptyList(), tokenManager.serverUrlWrites)
    }

    @Test
    fun priorOriginApprovalAndHttpsBypassConfirmation() = runTest(dispatcher) {
        val approvedTokens = RecordingTokenManager()
        val approvals = FakeCleartextConsentStore().apply { approve("http://silo.lan") }
        val approvedViewModel = track(viewModelFor(approvedTokens, approvals))

        approvedViewModel.onServerUrlChanged("silo.lan")
        approvedViewModel.onConnectClick()
        awaitSettled(approvedViewModel)

        assertNull(approvedViewModel.uiState.value.pendingCleartextUrl)
        assertEquals(listOf("http://silo.lan"), approvedTokens.serverUrlWrites)

        val httpsTokens = RecordingTokenManager()
        val httpsViewModel = track(viewModelFor(httpsTokens, httpsSucceeds = true))
        httpsViewModel.onServerUrlChanged("secure.silo")
        httpsViewModel.onConnectClick()
        awaitSettled(httpsViewModel)

        assertNull(httpsViewModel.uiState.value.pendingCleartextUrl)
        assertEquals(listOf("https://secure.silo"), httpsTokens.serverUrlWrites)
    }

    @Test
    fun cancelWinsRaceWithInFlightCleartextApproval() = runTest(dispatcher) {
        val tokenManager = RecordingTokenManager()
        val approvals = BlockingCleartextConsentStore()
        val viewModel = track(viewModelFor(tokenManager, approvals))
        viewModel.onServerUrlChanged("silo.lan")
        viewModel.onConnectClick()
        awaitSettled(viewModel)

        viewModel.confirmCleartextConnection()
        runCurrent()
        approvals.approveStarted.await()
        viewModel.cancelCleartextConnection()
        approvals.releaseApproval.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingCleartextUrl)
        assertNull(viewModel.uiState.value.navigateTo)
        assertEquals(emptyList(), tokenManager.serverUrlWrites)
    }

    @Test
    fun successfulUnnormalizableHttpCandidateFailsClosed() = runTest(dispatcher) {
        val tokenManager = RecordingTokenManager()
        val viewModel = track(viewModelFor(tokenManager))

        viewModel.onServerUrlChanged("http://silo_lan")
        viewModel.onConnectClick()
        awaitSettled(viewModel)

        assertEquals(emptyList(), tokenManager.serverUrlWrites)
        assertNull(viewModel.uiState.value.navigateTo)
        assertEquals("Could not safely identify this HTTP server.", viewModel.uiState.value.error)
    }

    private fun viewModelFor(
        tokenManager: RecordingTokenManager,
        approvals: FakeCleartextConsentStore = FakeCleartextConsentStore(),
        httpsSucceeds: Boolean = false,
    ) = TvServerSetupViewModel(
        authRepository = repositoryFor(tokenManager),
        cleartextConsentStore = approvals,
        getSetupStatus = { candidate ->
            if (candidate.startsWith("https://") && !httpsSucceeds) {
                ApiResult.NetworkError(IOException("TLS unavailable"))
            } else {
                ApiResult.Success(SetupStatusResponse(needsSetup = false))
            }
        },
        getSignupStatus = {
            ApiResult.Success(SignupStatusResponse(enabled = true))
        },
    )

    private fun repositoryFor(
        tokenManager: RecordingTokenManager,
    ): AuthRepository = AuthRepository(
        authApi = AuthApi(
            HttpClient(MockEngine { throw IOException("Unexpected network request") }) {
                install(ContentNegotiation) { json(SiloJson) }
                install(SiloAuthPlugin) { this.tokenManager = tokenManager }
            }, ApiV2Gate.Unrestricted),
        tokenManager = tokenManager,
    )

    private suspend fun TestScope.awaitSettled(viewModel: TvServerSetupViewModel) {
        runCurrent()
        withTimeout(5_000) {
            viewModel.uiState.first { !it.isLoading }
        }
    }
}

private class RecordingTokenManager(
    private var serverUrl: String = "",
) : TokenManager {
    val serverUrlWrites = mutableListOf<String>()
    override val sessionExpired = MutableSharedFlow<Unit>()
    override suspend fun getAccessToken(): String? = null
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) = Unit
    override suspend fun clearTokens() = Unit
    override suspend fun invalidateSession() = Unit
    override suspend fun getProfileId(): String? = null
    override suspend fun setProfileId(profileId: String?) = Unit
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) = Unit
    override suspend fun getServerUrl(): String = serverUrl
    override suspend fun setServerUrl(url: String) {
        serverUrl = url
        serverUrlWrites += url
    }
    override suspend fun getCurrentServerId(): String? = null
    override suspend fun switchActiveServer(serverId: String?) = Unit
    override suspend fun signOutCurrentServer() = Unit
}

private open class FakeCleartextConsentStore : CleartextConsentStore {
    private val approved = mutableSetOf<String>()

    override suspend fun isApproved(origin: String): Boolean = origin in approved

    override suspend fun approve(origin: String) {
        approved += origin
    }
}

private class BlockingCleartextConsentStore : FakeCleartextConsentStore() {
    val approveStarted = CompletableDeferred<Unit>()
    val releaseApproval = CompletableDeferred<Unit>()

    override suspend fun approve(origin: String) {
        approveStarted.complete(Unit)
        releaseApproval.await()
        super.approve(origin)
    }
}
