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
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.repository.AuthRepository
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LoginV2ConsumerTest {
    @Test fun actualPhoneLoginSubmitsOnceAndRejectsChangedServerCompletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (switchServer in listOf(false, true)) {
                val tokens = TokenManagerImpl().apply { setServerUrl("https://first.example.test") }
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
                val client = HttpClient(MockEngine { request ->
                    calls++; assertEquals("https://first.example.test/api/v2/auth/login", request.url.toString())
                    entered.complete(Unit); release.await()
                    respond("""{"access_token":"access","refresh_token":"refresh","expires_in":3600,"user":{"id":"1","username":"user","email":"u@example.test","role":"user"}}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }) { install(ContentNegotiation) { json(SiloJson) } }
                val vm = LoginViewModel(AuthRepository(AuthApi(client, ApiV2Gate.Unrestricted), tokens))
                try {
                    vm.onUsernameChanged("user"); vm.onPasswordChanged("password")
                    vm.onLoginClick(); vm.onLoginClick(); entered.await()
                    if (switchServer) tokens.setServerUrl("https://second.example.test")
                    release.complete(Unit)
                    val settled = vm.uiState.first { !it.isLoading }
                    assertEquals(1, calls); assertEquals(!switchServer, settled.loginSuccess)
                    assertEquals(if (switchServer) null else "access", tokens.getAccessToken())
                    if (switchServer) assertNotNull(settled.error)
                } finally { vm.viewModelScope.cancel(); client.close() }
            }
        } finally { Dispatchers.resetMain() }
    }
}
