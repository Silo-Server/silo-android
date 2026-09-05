package org.siloserver.silo.tv.ui.screens.profiles

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The admin lookup behind the TV profile grid is a gated v2 call. When the
 * launch probe outlasts its bound, routing proceeds on a stale
 * UPDATE_REQUIRED verdict that blocks the lookup without a request; when the
 * background refresh then records V2, the view model must re-run the load
 * rather than leave the admin without manage controls for its lifetime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvProfileSelectionViewModelContractTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun v2VerdictArrivingAfterUpdateRequiredRetriggersTheGatedLoad() = runTest(dispatcher) {
        val registry = MutableContractRegistry(ServerContract.UPDATE_REQUIRED)
        // The mock engine answers on its own thread, outside the test
        // scheduler, so each request is awaited rather than assumed to have
        // landed by the time an assertion runs.
        val requests = Channel<String>(Channel.UNLIMITED)
        val client = HttpClient(
            MockEngine { request ->
                requests.trySend(request.url.encodedPath)
                when (request.url.encodedPath) {
                    "/api/v1/profiles" -> respond(
                        """{"profiles":[]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> respond(
                        """{"type":"about:blank","title":"nope","status":500,"detail":"x","instance":"urn:x"}""",
                        HttpStatusCode.InternalServerError,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                }
            },
        ) { install(ContentNegotiation) { json(SiloJson) } }
        val tokens = StubTokenManager()
        val authRepository = AuthRepository(
            authApi = AuthApi(client, ApiV2Gate(registry)),
            tokenManager = tokens,
            serverRegistry = registry,
        )
        val profileRepository = ProfileRepository(ProfileApi(client), tokens, registry)

        TvProfileSelectionViewModel(profileRepository, authRepository)
        runCurrent()

        // First load: the gate rejects the v2 call without a request, so the
        // first thing to reach the network is the v1 list.
        assertEquals("/api/v1/profiles", requests.awaitNext())

        registry.setContract("a", ServerContract.V2)
        // The collector's resumption is queued on the unconfined event loop
        // rather than run inline; pump it (and the reload it launches).
        advanceUntilIdle()

        // The contract change re-ran the load, and this time the gate let
        // the v2 admin lookup through to the network, followed by the list.
        assertEquals("/api/v2/account/me", requests.awaitNext())
        assertEquals("/api/v1/profiles", requests.awaitNext())
    }

    /** Real-clock wait: under the test scheduler a virtual-time timeout would fire at once. */
    private suspend fun Channel<String>.awaitNext(): String =
        withContext(Dispatchers.Default) { withTimeout(5_000) { receive() } }
}

private class MutableContractRegistry(initial: ServerContract) : ServerRegistry {
    private val entry = MutableStateFlow(ServerEntry(id = "a", url = "https://a.example", contract = initial))
    override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(listOf(entry.value))
    override val activeServerId: StateFlow<String?> = MutableStateFlow("a")
    override val activeEntry: StateFlow<ServerEntry?> = entry
    override suspend fun addOrUpdate(url: String, fetchedName: String?): String = "a"
    override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
    override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
    override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
    override suspend fun setContract(serverId: String, contract: ServerContract) {
        entry.update { it.copy(contract = contract) }
    }
    override suspend fun remove(serverId: String) = Unit
    override suspend fun signOut(serverId: String) = Unit
    override suspend fun switchTo(serverId: String) = Unit
    override suspend fun touchActive() = Unit
}

private class StubTokenManager : TokenManager {
    override suspend fun getAccessToken(): String? = "access"
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) = Unit
    override suspend fun clearTokens() = Unit
    override suspend fun invalidateSession() = Unit
    override suspend fun getProfileId(): String? = null
    override suspend fun setProfileId(profileId: String?) = Unit
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) = Unit
    override suspend fun getServerUrl(): String = "https://a.example"
    override suspend fun setServerUrl(url: String) = Unit
    override suspend fun getCurrentServerId(): String? = "a"
    override suspend fun switchActiveServer(serverId: String?) = Unit
    override suspend fun signOutCurrentServer() = Unit
    override val sessionExpired: SharedFlow<Unit> = MutableSharedFlow()
}
