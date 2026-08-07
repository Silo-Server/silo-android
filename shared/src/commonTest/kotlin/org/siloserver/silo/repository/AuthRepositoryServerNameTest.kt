package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.BrandingApi
import org.siloserver.silo.network.api.BrandingStatus
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.network.api.HealthStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthRepositoryServerNameTest {
    @Test
    fun `refresh prefers native branding over compat-backed health name`() = runTest {
        val registry = RecordingServerRegistry()
        val health = FakeHealthApi(ApiResult.Success(HealthStatus("ok", "StreamApp")))
        val repository = repository(
            registry = registry,
            branding = FakeBrandingApi(ApiResult.Success(BrandingStatus("  Home Silo  "))),
            health = health,
        )

        repository.refreshActiveServerName()

        assertEquals("Home Silo", registry.fetchedName)
        assertEquals(0, health.calls)
    }

    @Test
    fun `refresh falls back to health when branding is unavailable`() = runTest {
        val registry = RecordingServerRegistry()
        val repository = repository(
            registry = registry,
            branding = FakeBrandingApi(ApiResult.Error(404, "not_found", "missing")),
            health = FakeHealthApi(ApiResult.Success(HealthStatus("ok", "Legacy Home"))),
        )

        repository.refreshActiveServerName()

        assertEquals("Legacy Home", registry.fetchedName)
    }

    @Test
    fun `refresh falls back to health when branding name is blank`() = runTest {
        val registry = RecordingServerRegistry()
        val repository = repository(
            registry = registry,
            branding = FakeBrandingApi(ApiResult.Success(BrandingStatus("  "))),
            health = FakeHealthApi(ApiResult.Success(HealthStatus("ok", "Fallback"))),
        )

        repository.refreshActiveServerName()

        assertEquals("Fallback", registry.fetchedName)
    }

    @Test
    fun `refresh ignores response after active server changes`() = runTest {
        val registry = RecordingServerRegistry()
        val repository = repository(
            registry = registry,
            branding = FakeBrandingApi(ApiResult.Success(BrandingStatus("Wrong Server"))) {
                registry.activate("other")
            },
            health = FakeHealthApi(ApiResult.Success(HealthStatus("ok", "Fallback"))),
        )

        repository.refreshActiveServerName()

        assertNull(registry.fetchedName)
    }

    private fun repository(
        registry: RecordingServerRegistry,
        branding: BrandingApi,
        health: HealthApi,
    ) = AuthRepository(
        authApi = AuthApi(unusedClient()),
        tokenManager = FakeTokenManager,
        serverRegistry = registry,
        healthApi = health,
        brandingApi = branding,
    )
}

private class FakeBrandingApi(
    private val result: ApiResult<BrandingStatus>,
    private val beforeReturn: suspend () -> Unit = {},
) : BrandingApi(unusedClient()) {
    override suspend fun getBranding(): ApiResult<BrandingStatus> {
        beforeReturn()
        return result
    }
}

private class FakeHealthApi(
    private val result: ApiResult<HealthStatus>,
) : HealthApi(unusedClient()) {
    var calls = 0
        private set

    override suspend fun checkHealth(): ApiResult<HealthStatus> {
        calls += 1
        return result
    }
}

private class RecordingServerRegistry : ServerRegistry {
    private val activeId = MutableStateFlow<String?>("active")
    private val savedEntries = MutableStateFlow(
        listOf(ServerEntry(id = "active", url = "https://silo.example")),
    )

    var fetchedName: String? = null
        private set

    override val entries: StateFlow<List<ServerEntry>> = savedEntries
    override val activeServerId: StateFlow<String?> = activeId
    override val activeEntry: StateFlow<ServerEntry?> = MutableStateFlow(savedEntries.value.single())

    fun activate(serverId: String) {
        activeId.value = serverId
    }

    override suspend fun addOrUpdate(url: String, fetchedName: String?): String = "active"
    override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
    override suspend fun setFetchedName(serverId: String, fetchedName: String?) {
        this.fetchedName = fetchedName
    }
    override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
    override suspend fun remove(serverId: String) = Unit
    override suspend fun signOut(serverId: String) = Unit
    override suspend fun switchTo(serverId: String) {
        activeId.value = serverId
    }
    override suspend fun touchActive() = Unit
}

private object FakeTokenManager : TokenManager {
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
    override suspend fun getServerUrl(): String = "https://silo.example"
    override suspend fun setServerUrl(url: String) = Unit
    override suspend fun getCurrentServerId(): String? = "active"
    override suspend fun switchActiveServer(serverId: String?) = Unit
    override suspend fun signOutCurrentServer() = Unit
}

private fun unusedClient(): HttpClient = HttpClient(MockEngine { error("Unexpected request") })
