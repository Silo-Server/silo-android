package org.siloserver.silo.android.ui.screens.auth

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.siloserver.silo.common.network.CleartextConsentStore
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.repository.AuthRepository
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InviteClaimV2Test {
    private val consent = object : CleartextConsentStore {
        override suspend fun isApproved(origin: String) = true
        override suspend fun approve(origin: String) = Unit
    }
    private val lookup = """{"email":"u@example.test","inviter_name":"Admin","server_name":"Server","expires_at":"2099-01-01T00:00:00Z","show_tour":true,"acceptance_available":true}"""
    private val capability = """{"revision":"r","state":"available","default_profile":true,"profileless":true}"""
    private val signedIn = """{"status":"accepted","login_status":"signed_in","username":"u@example.test","tokens":{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"user":{"id":"1","username":"u@example.test","email":"","role":"user"}}}"""

    @Test fun committedSignInRequiredDoesNotAdoptServerOrReplayClaim() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val tokens = TokenManagerImpl().apply { setServerUrl("https://existing.example.test"); saveTokens("old", "old-refresh", 3600) }
        var posts = 0
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val client = client { request ->
            if (request.method == HttpMethod.Post) {
                posts++; entered.complete(Unit); release.await()
                """{"status":"accepted","login_status":"sign_in_required","username":"u@example.test"}""" to HttpStatusCode.Created
            } else (if (request.url.encodedPath.endsWith("capabilities")) capability else lookup) to HttpStatusCode.OK
        }
        val vm = InviteClaimViewModel(AuthRepository(AuthApi(client, ApiV2Gate.Unrestricted), tokens), consent)
        try {
            ready(vm); vm.onClaimClick(); vm.onClaimClick(); entered.await(); release.complete(Unit)
            vm.uiState.first { it.signInRequiredUsername != null }
            vm.onClaimClick(); advanceUntilIdle()
            assertEquals(1, posts)
            assertFalse(vm.uiState.value.claimSuccess)
            assertEquals("old", tokens.getAccessToken())
            assertEquals("https://existing.example.test", tokens.getServerUrl())
        } finally { vm.viewModelScope.cancel(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun routeChangeAtNoncancellableInstallIsRejectedInsideActualTokenStore() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val actual = TokenManagerImpl().apply { setServerUrl("https://existing.example.test"); saveTokens("old", "old-refresh", 3600) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val tokens = object : TokenManager by actual {
            override suspend fun replaceAccountSession(serverId: String?, serverUrl: String?, accessToken: String,
                refreshToken: String, expiresIn: Long, profileId: String?, profileToken: String?, expectedIdentity: AccountSessionExpectation?) {
                withContext(NonCancellable) {
                    entered.complete(Unit); release.await()
                    try { actual.replaceAccountSession(serverId, serverUrl, accessToken, refreshToken,
                        expiresIn, profileId, profileToken, expectedIdentity) }
                    finally { finished.complete(Unit) }
                }
            }
        }
        val client = client { request ->
            if (request.method == HttpMethod.Post) signedIn to HttpStatusCode.Created
            else (if (request.url.encodedPath.endsWith("capabilities")) capability else lookup) to HttpStatusCode.OK
        }
        val vm = InviteClaimViewModel(AuthRepository(AuthApi(client, ApiV2Gate.Unrestricted), tokens), consent)
        try {
            ready(vm); vm.onClaimClick(); entered.await()
            vm.load("https://other.example.test", "new-token")
            release.complete(Unit); finished.await(); advanceUntilIdle()
            assertEquals("old", actual.getAccessToken())
            assertEquals("old-refresh", actual.getRefreshToken())
            assertEquals("https://existing.example.test", actual.getServerUrl())
            assertFalse(vm.uiState.value.claimSuccess)
        } finally { vm.viewModelScope.cancel(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun availabilityAndLookupFailuresAreNotExpiredTokensAndUncertainPostCannotRepeat() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (scenario in listOf("capability", "unavailable", "404", "500", "uncertain")) {
                val tokens = TokenManagerImpl()
                var posts = 0
                val client = client { request ->
                    when {
                        request.url.encodedPath.endsWith("capabilities") -> (if (scenario == "capability") capability.replace("available", "unavailable") else capability) to HttpStatusCode.OK
                        request.method == HttpMethod.Post -> { posts++; "" to HttpStatusCode.ServiceUnavailable }
                        scenario == "unavailable" -> lookup.replace("\"acceptance_available\":true", "\"acceptance_available\":false") to HttpStatusCode.OK
                        scenario == "404" -> "" to HttpStatusCode.NotFound
                        scenario == "500" -> "" to HttpStatusCode.InternalServerError
                        else -> lookup to HttpStatusCode.OK
                    }
                }
                val vm = InviteClaimViewModel(AuthRepository(AuthApi(client, ApiV2Gate.Unrestricted), tokens), consent)
                try {
                    vm.load("https://invite.example.test", "token")
                    vm.uiState.first { !it.isLoadingInvitation }
                    assertEquals(scenario == "404", vm.uiState.value.invitationInvalid)
                    assertEquals(scenario == "500", vm.uiState.value.lookupFailed)
                    assertEquals(scenario == "capability", vm.uiState.value.acceptanceUnavailable)
                    vm.onPasswordChanged("password"); vm.onConfirmPasswordChanged("password")
                    vm.onClaimClick()
                    if (scenario == "uncertain") vm.uiState.first { it.acceptanceUncertain }
                    vm.onClaimClick(); advanceUntilIdle()
                    assertEquals(if (scenario == "uncertain") 1 else 0, posts)
                } finally { vm.viewModelScope.cancel(); client.close() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun successfulClaimInstallsNestedSessionAndRoutesOnlyAfterCommit() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val tokens = TokenManagerImpl().apply { setServerUrl("https://existing.example.test") }
        var posts = 0
        val client = client { request ->
            if (request.method == HttpMethod.Post) { posts++; signedIn to HttpStatusCode.Created }
            else (if (request.url.encodedPath.endsWith("capabilities")) capability else lookup) to HttpStatusCode.OK
        }
        val vm = InviteClaimViewModel(AuthRepository(AuthApi(client, ApiV2Gate.Unrestricted), tokens), consent)
        try {
            ready(vm); vm.onClaimClick()
            vm.uiState.first { it.claimSuccess }
            assertEquals(1, posts)
            assertEquals("new-access", tokens.getAccessToken())
            assertEquals("https://invite.example.test", tokens.getServerUrl())
            assertNull(vm.uiState.value.signInRequiredUsername)
        } finally { vm.viewModelScope.cancel(); client.close(); Dispatchers.resetMain() }
    }

    private suspend fun ready(vm: InviteClaimViewModel) {
        vm.load("https://invite.example.test", "token")
        vm.uiState.first { !it.isLoadingInvitation }
        vm.onPasswordChanged("password"); vm.onConfirmPasswordChanged("password")
    }
    private fun client(handler: suspend (io.ktor.client.request.HttpRequestData) -> Pair<String, HttpStatusCode>) =
        HttpClient(MockEngine { request ->
            val (body, status) = handler(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
}
