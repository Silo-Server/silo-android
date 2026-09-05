package org.siloserver.silo.android.ui.screens.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.SiloAuthPlugin
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.apiv2.ApiV2ProbeResult
import org.siloserver.silo.common.network.CleartextConsentStore
import org.siloserver.silo.model.auth.SetupStatusResponse
import org.siloserver.silo.model.auth.SignupStatusResponse
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.repository.AuthRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSetupPersistenceTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
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
                },
            ),
            tokenManager = tokenManager,
        )
        val viewModel = ServerSetupViewModel(repository, FakeCleartextConsentStore())

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
        val viewModel = viewModelFor(tokenManager)

        viewModel.onServerUrlChanged("silo.lan")
        viewModel.onConnectClick()
        awaitSettled(viewModel)

        assertEquals(
            "http://silo.lan",
            viewModel.uiState.value.pendingCleartextUrl,
            viewModel.uiState.value.toString(),
        )
        assertEquals(emptyList(), tokenManager.serverUrlWrites)
        assertNull(viewModel.uiState.value.navigateTo)

        viewModel.confirmCleartextConnection()
        awaitSettled(viewModel)

        assertEquals(listOf("http://silo.lan"), tokenManager.serverUrlWrites)
        assertEquals(ServerSetupDestination.Login(signupEnabled = true), viewModel.uiState.value.navigateTo)
    }

    @Test
    fun cancelCleartextConnectionClearsPendingWithoutPersistence() = runTest(dispatcher) {
        val tokenManager = RecordingTokenManager()
        val viewModel = viewModelFor(tokenManager)

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
        val viewModel = viewModelFor(tokenManager, approvals)

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
        val approvedViewModel = viewModelFor(approvedTokens, approvals)

        approvedViewModel.onServerUrlChanged("silo.lan")
        approvedViewModel.onConnectClick()
        awaitSettled(approvedViewModel)

        assertNull(approvedViewModel.uiState.value.pendingCleartextUrl)
        assertEquals(listOf("http://silo.lan"), approvedTokens.serverUrlWrites)

        val httpsTokens = RecordingTokenManager()
        val httpsViewModel = viewModelFor(httpsTokens, httpsSucceeds = true)
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
        val viewModel = viewModelFor(tokenManager, approvals)
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
        val viewModel = ServerSetupViewModel(
            authRepository = repositoryFor(tokenManager),
            cleartextConsentStore = FakeCleartextConsentStore(),
            candidateUrls = { listOf("http://silo_lan") },
            getSetupStatus = { ApiResult.Success(SetupStatusResponse(needsSetup = false)) },
            getSignupStatus = { ApiResult.Success(SignupStatusResponse(enabled = true)) },
        )

        viewModel.onServerUrlChanged("silo_lan")
        viewModel.onConnectClick()
        awaitSettled(viewModel)

        assertEquals(emptyList(), tokenManager.serverUrlWrites)
        assertNull(viewModel.uiState.value.navigateTo)
        assertEquals("Could not safely identify this HTTP server.", viewModel.uiState.value.error)
    }

    @Test
    fun failedProbeWithSuccessfulSetupRecordsV2OverAStaleVerdict() = runTest(dispatcher) {
        // The candidate probe fails transiently (or times out) but the v2
        // setup call succeeds: that answer is proof of v2, so the registry
        // entry's stale UPDATE_REQUIRED must be replaced with V2 rather than
        // left alone behind a non-null UNKNOWN.
        val tokenManager = RecordingTokenManager()
        val registry = StaleContractRegistry("https://secure.silo", ServerContract.UPDATE_REQUIRED)
        val viewModel = ServerSetupViewModel(
            authRepository = AuthRepository(
                authApi = AuthApi(
                    HttpClient(MockEngine { throw IOException("Unexpected network request") }) {
                        install(ContentNegotiation) { json(SiloJson) }
                        install(SiloAuthPlugin) { this.tokenManager = tokenManager }
                    },
                ),
                tokenManager = tokenManager,
                serverRegistry = registry,
            ),
            cleartextConsentStore = FakeCleartextConsentStore(),
            getSetupStatus = { ApiResult.Success(SetupStatusResponse(needsSetup = false)) },
            getSignupStatus = { ApiResult.Success(SignupStatusResponse(enabled = true)) },
            probeContract = { ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.TIMEOUT) },
        )

        viewModel.onServerUrlChanged("https://secure.silo")
        viewModel.onConnectClick()
        awaitSettled(viewModel)

        assertEquals(listOf("https://secure.silo"), registry.switchedTo)
        assertEquals(ServerContract.V2, registry.activeEntry.value?.contract)
    }

    private fun viewModelFor(
        tokenManager: RecordingTokenManager,
        approvals: FakeCleartextConsentStore = FakeCleartextConsentStore(),
        httpsSucceeds: Boolean = false,
    ) = ServerSetupViewModel(
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
            },
        ),
        tokenManager = tokenManager,
    )

    private suspend fun TestScope.awaitSettled(viewModel: ServerSetupViewModel) {
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

/** One saved server, [url], whose stored contract starts at [initial]; records switches and contract writes. */
private class StaleContractRegistry(url: String, initial: ServerContract) : ServerRegistry {
    private val entry = MutableStateFlow(ServerEntry(id = "a", url = url, contract = initial))
    val switchedTo = mutableListOf<String>()
    override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(listOf(entry.value))
    override val activeServerId = MutableStateFlow<String?>(null)
    override val activeEntry: StateFlow<ServerEntry?> = entry
    override suspend fun addOrUpdate(url: String, fetchedName: String?): String {
        check(url == entry.value.url) { "unexpected server $url" }
        return entry.value.id
    }
    override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
    override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
    override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
    override suspend fun setContract(serverId: String, contract: ServerContract) {
        entry.update { it.copy(contract = contract) }
    }
    override suspend fun remove(serverId: String) = Unit
    override suspend fun signOut(serverId: String) = Unit
    override suspend fun switchTo(serverId: String) {
        switchedTo += entry.value.url
        activeServerId.value = serverId
    }
    override suspend fun touchActive() = Unit
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
