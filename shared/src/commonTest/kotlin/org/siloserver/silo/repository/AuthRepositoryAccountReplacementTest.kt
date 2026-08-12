package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ProfileIdentity
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.AuthApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AuthRepositoryAccountReplacementTest {
    @Test
    fun invitationAcceptanceInstallsTheSessionThroughOneExplicitAccountReplacement() = runTest {
        val tokenManager = RecordingAccountReplacementTokenManager()
        val registry = RecordingInvitationRegistry()
        val client = HttpClient(
            MockEngine { request ->
                assertEquals(
                    "/api/v1/invitations/invite-token/accept",
                    request.url.encodedPath,
                )
                respond(
                    content =
                        """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"user":{"id":7,"username":"new-user","email":"new@example.com","role":"user"}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val repository = AuthRepository(
            authApi = AuthApi(client),
            tokenManager = tokenManager,
            serverRegistry = registry,
        )

        val result = repository.acceptInvitation(
            serverUrl = "https://invited.example",
            token = "invite-token",
            password = "password",
        )

        assertEquals("new-user", assertIs<org.siloserver.silo.network.ApiResult.Success<*>>(result).data.let {
            (it as org.siloserver.silo.model.auth.User).username
        })
        assertEquals(
            AccountReplacement(
                serverId = "invited-server",
                accessToken = "new-access",
                refreshToken = "new-refresh",
                expiresIn = 3600,
                profileId = null,
                profileToken = null,
            ),
            tokenManager.replacement,
        )
        assertEquals("https://invited.example", registry.addedUrl)
        assertEquals(0, registry.switchCalls)
    }
}

private data class AccountReplacement(
    val serverId: String?,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val profileId: String?,
    val profileToken: String?,
)

private class RecordingAccountReplacementTokenManager : TokenManager {
    override val sessionExpired = MutableSharedFlow<Unit>()
    var replacement: AccountReplacement? = null

    override suspend fun replaceAccountSession(
        serverId: String?,
        serverUrl: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        profileId: String?,
        profileToken: String?,
    ) {
        check(serverUrl == null) { "a registry-backed invitation must install by server id" }
        check(replacement == null) { "the session must be installed exactly once" }
        replacement = AccountReplacement(
            serverId,
            accessToken,
            refreshToken,
            expiresIn,
            profileId,
            profileToken,
        )
    }

    override suspend fun getAccessToken(): String? = null
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long): Unit =
        error("explicit invitation credentials must not use refresh-token persistence")
    override suspend fun clearTokens() = Unit
    override suspend fun invalidateSession() = Unit
    override suspend fun getProfileId(): String? = null
    override suspend fun setProfileId(profileId: String?) = Unit
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) = Unit
    override suspend fun getProfileIdentity() = ProfileIdentity(null, null)
    override suspend fun getServerUrl(): String = "https://old.example"
    override suspend fun setServerUrl(url: String) = Unit
    override suspend fun getCurrentServerId(): String? = "old-server"
    override suspend fun switchActiveServer(serverId: String?) = Unit
    override suspend fun signOutCurrentServer() = Unit
}

private class RecordingInvitationRegistry : ServerRegistry {
    private val oldEntry = ServerEntry(id = "old-server", url = "https://old.example")
    override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(listOf(oldEntry))
    override val activeServerId: StateFlow<String?> = MutableStateFlow(oldEntry.id)
    override val activeEntry: StateFlow<ServerEntry?> = MutableStateFlow(oldEntry)
    var addedUrl: String? = null
    var switchCalls = 0

    override suspend fun addOrUpdate(url: String, fetchedName: String?): String {
        addedUrl = url
        return "invited-server"
    }

    override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
    override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
    override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
    override suspend fun remove(serverId: String) = Unit
    override suspend fun signOut(serverId: String) = Unit
    override suspend fun switchTo(serverId: String) {
        switchCalls += 1
    }
    override suspend fun touchActive() = Unit
}
