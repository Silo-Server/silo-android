package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.auth.SetupStatusResponse
import org.siloserver.silo.model.profile.UpdateProfileRequest
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.DefaultDeviceLoginApi
import org.siloserver.silo.network.api.ProfileApi

/** A failed v2 mutation is never replayed against another API major, and the update-server state blocks it outright. */
class ApiV2NoFallbackTest {

    private fun client(recorded: MutableList<String>, status: HttpStatusCode, body: String) =
        HttpClient(
            MockEngine { request ->
                recorded += "${request.method.value} ${request.url.encodedPath}"
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/problem+json"))
            },
        ) { install(ContentNegotiation) { json(SiloJson) } }

    @Test
    fun failedUpdateProfileIsNotRetriedOnV1() = runTest {
        val recorded = mutableListOf<String>()
        val api = ProfileApi(client(recorded, HttpStatusCode.InternalServerError, """{"type":"https://siloserver.org/docs/api/v2/problems/internal","title":"Internal","status":500,"detail":"boom","instance":"urn:x"}"""), ApiV2Gate.Unrestricted)

        val result = api.updateProfile("p-owner", UpdateProfileRequest(name = "Laura"))

        val error = assertIs<ApiResult.Error>(result)
        assertEquals(500, error.code)
        assertEquals("internal", error.error)
        assertEquals(listOf("PATCH /api/v2/profiles/p-owner"), recorded)
        assertTrue(recorded.none { "/api/v1" in it }, recorded.toString())
    }

    @Test
    fun validationProblemSurfacesCodeAndDetailWithoutRetry() = runTest {
        val recorded = mutableListOf<String>()
        val api = ProfileApi(client(recorded, HttpStatusCode.UnprocessableEntity, ApiV2Fixtures.body("update_profile_null_not_clearable")), ApiV2Gate.Unrestricted)

        val error = assertIs<ApiResult.Error>(api.updateProfile("p-owner", UpdateProfileRequest(name = "Laura")))

        assertEquals(422, error.code)
        assertEquals("validation_failed", error.error)
        assertEquals(1, recorded.size)
    }

    @Test
    fun updateRequiredStateBlocksTheCallBeforeAnyRequest() = runTest {
        val recorded = mutableListOf<String>()
        val registry = ContractRegistry(ServerContract.UPDATE_REQUIRED)
        val api = ProfileApi(client(recorded, HttpStatusCode.OK, "{}"), ApiV2Gate(registry))

        val error = assertIs<ApiResult.Error>(api.updateProfile("p-owner", UpdateProfileRequest(name = "Laura")))

        assertEquals(ApiV2Gate.UPDATE_REQUIRED_ERROR, error.error)
        assertEquals(ServerContract.UPDATE_REQUIRED_MESSAGE, error.message)
        assertEquals(emptyList(), recorded)
    }

    @Test
    fun explicitServerSetupStatusBypassesTheActiveServersUpdateRequiredVerdict() = runTest {
        val recorded = mutableListOf<String>()
        val gate = ApiV2Gate(ContractRegistry(ServerContract.UPDATE_REQUIRED))
        val api = AuthApi(client(recorded, HttpStatusCode.OK, """{"needs_setup":false}"""), gate)

        // Candidate (absolute URL): the active entry's verdict says nothing about it.
        val candidate = assertIs<ApiResult.Success<SetupStatusResponse>>(api.getSetupStatus("https://other.example/"))
        assertEquals(false, candidate.data.needsSetup)
        assertEquals(listOf("GET /api/v2/system/setup"), recorded)

        // Relative form targets the active server and stays blocked.
        val error = assertIs<ApiResult.Error>(api.getSetupStatus())
        assertEquals(ApiV2Gate.UPDATE_REQUIRED_ERROR, error.error)
        assertEquals(1, recorded.size)
    }

    @Test
    fun membershipGateBlocksEveryOperationWithoutFallback() = runTest {
        val recorded = mutableListOf<String>()
        val api = MembershipV2Api(client(recorded, HttpStatusCode.OK, "{}"), ApiV2Gate(ContractRegistry(ServerContract.UPDATE_REQUIRED)))
        val results = listOf(api.favorite("item"), api.watchlist("item"), api.addFavorite("item"),
            api.removeFavorite("item"), api.addToWatchlist("item"), api.removeFromWatchlist("item"))
        results.forEach { assertEquals(ApiV2Gate.UPDATE_REQUIRED_ERROR, assertIs<ApiResult.Error>(it).error) }
        assertEquals(emptyList(), recorded)
    }

    @Test
    fun aCallScopedToAnotherSavedServerIsGatedOnThatServersVerdict() = runTest {
        val cabinCurrent = ServerEntry(id = "cabin", url = "https://cabin.example", contract = ServerContract.V2)
        val staleActive = ApiV2Gate(ContractRegistry(ServerContract.UPDATE_REQUIRED, listOf(cabinCurrent)))
        assertEquals(null, staleActive.forServer("cabin").blocked(), "the active server's verdict says nothing about cabin")
        assertEquals(ApiV2Gate.UPDATE_REQUIRED_ERROR, staleActive.blocked()?.error)

        val cabinOutdated = cabinCurrent.copy(contract = ServerContract.UPDATE_REQUIRED)
        val currentActive = ApiV2Gate(ContractRegistry(ServerContract.V2, listOf(cabinOutdated)))
        assertEquals(ApiV2Gate.UPDATE_REQUIRED_ERROR, currentActive.forServer("cabin").blocked()?.error)
        assertEquals(null, currentActive.blocked())

        // Through the scoped device-login approval: cabin is reached although
        // the active server needs an update.
        val recorded = mutableListOf<String>()
        val api = DefaultDeviceLoginApi(client(recorded, HttpStatusCode.OK, "{}"), staleActive)
        api.lookupDeviceLoginForScope(AuthScopeSnapshot("cabin", null, "https://cabin.example", null), "48217730")
        assertEquals(listOf("GET /api/v2/auth/device"), recorded)
        val blocked = DefaultDeviceLoginApi(client(recorded, HttpStatusCode.OK, "{}"), currentActive)
            .approveDeviceLoginForScope(AuthScopeSnapshot("cabin", null, "https://cabin.example", null), "48217730")
        assertEquals(ApiV2Gate.UPDATE_REQUIRED_ERROR, assertIs<ApiResult.Error>(blocked).error)
        assertEquals(1, recorded.size, "an outdated target is never called")
    }

    @Test
    fun unknownAndV2StatesDoNotBlock() {
        assertEquals(null, ApiV2Gate(ContractRegistry(ServerContract.UNKNOWN)).blocked())
        assertEquals(null, ApiV2Gate(ContractRegistry(ServerContract.V2)).blocked())
        assertEquals(null, ApiV2Gate.Unrestricted.blocked())
    }
}

private class ContractRegistry(
    contract: ServerContract,
    /** Other saved servers, which scoped calls may target without switching. */
    others: List<ServerEntry> = emptyList(),
) : ServerRegistry {
    private val entry = ServerEntry(id = "active", url = "https://silo.example", contract = contract)
    override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(listOf(entry) + others)
    override val activeServerId: StateFlow<String?> = MutableStateFlow("active")
    override val activeEntry: StateFlow<ServerEntry?> = MutableStateFlow(entry)
    override suspend fun addOrUpdate(url: String, fetchedName: String?): String = "active"
    override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
    override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
    override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
    override suspend fun remove(serverId: String) = Unit
    override suspend fun signOut(serverId: String) = Unit
    override suspend fun switchTo(serverId: String) = Unit
    override suspend fun touchActive() = Unit
}
