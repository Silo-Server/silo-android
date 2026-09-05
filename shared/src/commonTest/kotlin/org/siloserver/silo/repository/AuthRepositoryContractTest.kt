package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.BrandingApi
import org.siloserver.silo.network.api.BrandingStatus
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.apiv2.ApiV2Fixtures
import org.siloserver.silo.network.apiv2.ApiV2Probe
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The contract verdict is (re)established on every connect, launch restore,
 * and server switch through [AuthRepository.refreshActiveServerName]; these
 * tests pin the recording rules: verdicts stick, failures record nothing,
 * and a result for a server that is no longer active is dropped.
 */
class AuthRepositoryContractTest {
    private val infoBody = ApiV2Fixtures.body("get_system_info_ok")

    @Test
    fun `refreshActiveServerName records V2 from the probe`() = runTest {
        val registry = ContractRegistry()
        repository(registry, respondWith(HttpStatusCode.OK, infoBody, "application/json"))
            .refreshActiveServerName()

        assertEquals(mapOf("a" to ServerContract.V2), registry.contracts)
    }

    @Test
    fun `refreshActiveServerName records UPDATE_REQUIRED from a v1-only server`() = runTest {
        val registry = ContractRegistry()
        repository(registry, respondWith(HttpStatusCode.NotFound, "404 page not found\n", "text/plain"))
            .refreshActiveServerName()

        assertEquals(mapOf("a" to ServerContract.UPDATE_REQUIRED), registry.contracts)
    }

    @Test
    fun `probe failure leaves the stored contract unchanged`() = runTest {
        val registry = ContractRegistry()
        registry.setContract("a", ServerContract.V2)
        repository(registry, respondWith(HttpStatusCode.ServiceUnavailable, "", "text/plain"))
            .refreshActiveServerName()

        assertEquals(mapOf("a" to ServerContract.V2), registry.contracts)
    }

    @Test
    fun `switchToServer probes the switched-to server`() = runTest {
        val registry = ContractRegistry()
        val tokens = SwitchRecordingTokenManager()
        // Real clock: the switch bounds the probe, and virtual time would
        // skip past that bound while the engine answers.
        withContext(Dispatchers.Default) {
            repository(registry, respondWith(HttpStatusCode.OK, infoBody, "application/json"), tokens)
                .switchToServer("b")
        }

        assertEquals("b", registry.activeServerId.value)
        assertEquals(listOf<String?>("b"), tokens.switchedTo)
        assertEquals(mapOf("b" to ServerContract.V2), registry.contracts)
    }

    @Test
    fun `switchToServer returns with the verdict unchanged when the probe hangs past the bound`() = runTest {
        // A saved server that accepts the connection but never answers must
        // not hold the switch spinner for the full client timeouts.
        val registry = ContractRegistry()
        registry.setContract("b", ServerContract.V2)
        val tokens = SwitchRecordingTokenManager()
        val hanging = client { awaitCancellation() }
        repository(registry, hanging, tokens).switchToServer("b")

        assertEquals("b", registry.activeServerId.value)
        assertEquals(listOf<String?>("b"), tokens.switchedTo)
        assertEquals(mapOf("b" to ServerContract.V2), registry.contracts)
    }

    @Test
    fun `switchToServer does not await the display-name refresh`() = runTest {
        val registry = ContractRegistry()
        val brandingGate = CompletableDeferred<Unit>()
        val branding = object : BrandingApi(unusedClient()) {
            override suspend fun getBranding(): ApiResult<BrandingStatus> {
                brandingGate.await()
                return ApiResult.Success(BrandingStatus("Named"))
            }
        }
        val repository = repository(
            registry,
            respondWith(HttpStatusCode.OK, infoBody, "application/json"),
            brandingApi = branding,
            backgroundScope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        )

        withContext(Dispatchers.Default) { repository.switchToServer("b") }

        // Returned with the verdict recorded while branding is still pending.
        assertEquals(mapOf("b" to ServerContract.V2), registry.contracts)
        assertEquals(emptyMap<String, String?>(), registry.fetchedNames)

        brandingGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(mapOf<String, String?>("b" to "Named"), registry.fetchedNames)
    }

    @Test
    fun `switchToServer re-probes in the background when the bounded probe times out`() = runTest {
        // A stale UPDATE_REQUIRED on a since-upgraded server that answers
        // after the bound must still be corrected for this session.
        val registry = ContractRegistry()
        registry.setContract("b", ServerContract.UPDATE_REQUIRED)
        val answerGate = CompletableDeferred<Unit>()
        val client = client {
            answerGate.await()
            respond(infoBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val background = CoroutineScope(StandardTestDispatcher(testScheduler))
        val repository = repository(registry, client, backgroundScope = background)

        // Virtual clock: the bound elapses without the engine answering.
        repository.switchToServer("b")

        // Returned at the bound with the verdict untouched.
        assertEquals("b", registry.activeServerId.value)
        assertEquals(mapOf("b" to ServerContract.UPDATE_REQUIRED), registry.contracts)

        answerGate.complete(Unit)
        background.joinChildren()
        assertEquals(mapOf("b" to ServerContract.V2), registry.contracts)
    }

    @Test
    fun `background re-probe result is dropped when the active server changed meanwhile`() = runTest {
        val registry = ContractRegistry()
        registry.setContract("b", ServerContract.UPDATE_REQUIRED)
        val answerGate = CompletableDeferred<Unit>()
        val client = client {
            answerGate.await()
            // The user switches away while the replacement probe is in flight.
            registry.switchTo("a")
            respond(infoBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val background = CoroutineScope(StandardTestDispatcher(testScheduler))
        val repository = repository(registry, client, backgroundScope = background)

        repository.switchToServer("b")
        assertEquals(mapOf("b" to ServerContract.UPDATE_REQUIRED), registry.contracts)

        answerGate.complete(Unit)
        background.joinChildren()
        assertEquals("a", registry.activeServerId.value)
        assertEquals(mapOf("b" to ServerContract.UPDATE_REQUIRED), registry.contracts)
    }

    @Test
    fun `switchToServer does not re-probe when the bounded probe answered`() = runTest {
        val registry = ContractRegistry()
        var requests = 0
        val client = client {
            requests++
            respond(infoBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val repository = repository(
            registry,
            client,
            backgroundScope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        )

        // Real clock: the switch bounds the probe, and virtual time would
        // skip past that bound while the engine answers.
        withContext(Dispatchers.Default) { repository.switchToServer("b") }
        advanceUntilIdle()

        assertEquals(mapOf("b" to ServerContract.V2), registry.contracts)
        assertEquals(1, requests)
    }

    @Test
    fun `switchToServer on the already-active id still probes`() = runTest {
        // Removing the active server promotes the next-MRU entry inside the
        // registry; the view model then switches to the id that is already
        // active, which must still move the token scope and probe.
        val registry = ContractRegistry()
        val tokens = SwitchRecordingTokenManager()
        withContext(Dispatchers.Default) {
            repository(registry, respondWith(HttpStatusCode.OK, infoBody, "application/json"), tokens)
                .switchToServer("a")
        }

        assertEquals("a", registry.activeServerId.value)
        assertEquals(listOf<String?>("a"), tokens.switchedTo)
        assertEquals(mapOf("a" to ServerContract.V2), registry.contracts)
    }

    @Test
    fun `awaitContractRefreshIfUpdateRequired replaces a stale UPDATE_REQUIRED verdict`() = runTest {
        val registry = ContractRegistry(initialContract = ServerContract.UPDATE_REQUIRED)
        // Real clock: under the test scheduler the probe's suspension on the
        // engine dispatcher lets virtual time skip straight past the timeout.
        val verdict = withContext(Dispatchers.Default) {
            repository(registry, respondWith(HttpStatusCode.OK, infoBody, "application/json"))
                .awaitContractRefreshIfUpdateRequired()
        }

        assertEquals(ServerContract.V2, verdict)
        assertEquals(mapOf("a" to ServerContract.V2), registry.contracts)
    }

    @Test
    fun `awaitContractRefreshIfUpdateRequired does not probe when the gate would pass`() = runTest {
        var requests = 0
        val client = client {
            requests += 1
            respond(infoBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val verdict = repository(ContractRegistry(), client).awaitContractRefreshIfUpdateRequired()

        assertEquals(null, verdict)
        assertEquals(0, requests)
    }

    @Test
    fun `stale probe result for a previous server is dropped`() = runTest {
        val registry = ContractRegistry()
        val client = client {
            // The user switches servers while the probe of "a" is in flight.
            registry.switchTo("b")
            respond(infoBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        repository(registry, client).refreshActiveServerName()

        assertEquals(emptyMap<String, ServerContract>(), registry.contracts)
    }

    private fun respondWith(status: HttpStatusCode, body: String, contentType: String) = client {
        respond(body, status, headersOf(HttpHeaders.ContentType, contentType))
    }

    private fun client(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = HttpClient(MockEngine { request -> handler(request) }) {
        install(ContentNegotiation) { json(SiloJson) }
    }

    private fun unusedClient() = HttpClient(MockEngine { error("Unexpected request") })

    /**
     * Waits for the fire-and-forget work launched on a background scope. The
     * mock engine answers on its own thread, so `advanceUntilIdle` alone can
     * return before the reply has been posted back to the test scheduler.
     */
    private suspend fun CoroutineScope.joinChildren() = coroutineContext.job.children.forEach { it.join() }

    private fun repository(
        registry: ContractRegistry,
        client: HttpClient,
        tokenManager: TokenManager = SwitchRecordingTokenManager(),
        brandingApi: BrandingApi? = null,
        backgroundScope: CoroutineScope? = null,
    ) = AuthRepository(
        authApi = AuthApi(client),
        tokenManager = tokenManager,
        serverRegistry = registry,
        brandingApi = brandingApi,
        apiV2Probe = ApiV2Probe(client),
        backgroundScope = backgroundScope,
    )
}

private class ContractRegistry(initialContract: ServerContract = ServerContract.UNKNOWN) : ServerRegistry {
    private val activeId = MutableStateFlow<String?>("a")
    val contracts = mutableMapOf<String, ServerContract>()
    val fetchedNames = mutableMapOf<String, String?>()

    override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(
        listOf(
            ServerEntry(id = "a", url = "https://a.example", contract = initialContract),
            ServerEntry(id = "b", url = "https://b.example"),
        ),
    )
    override val activeServerId: StateFlow<String?> = activeId
    override val activeEntry: StateFlow<ServerEntry?> = MutableStateFlow(entries.value.first())

    override suspend fun addOrUpdate(url: String, fetchedName: String?): String = "a"
    override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
    override suspend fun setFetchedName(serverId: String, fetchedName: String?) {
        fetchedNames[serverId] = fetchedName
    }
    override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
    override suspend fun setContract(serverId: String, contract: ServerContract) {
        contracts[serverId] = contract
    }
    override suspend fun remove(serverId: String) = Unit
    override suspend fun signOut(serverId: String) = Unit
    override suspend fun switchTo(serverId: String) {
        activeId.value = serverId
    }
    override suspend fun touchActive() = Unit
}

private class SwitchRecordingTokenManager : TokenManager {
    val switchedTo = mutableListOf<String?>()
    override suspend fun getAccessToken(): String? = null
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
    override suspend fun switchActiveServer(serverId: String?) {
        switchedTo += serverId
    }
    override suspend fun signOutCurrentServer() = Unit
    override val sessionExpired: SharedFlow<Unit> = MutableSharedFlow()
}
