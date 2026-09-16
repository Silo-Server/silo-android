package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.onboarding.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.OnboardingApi
import kotlin.test.*

class OnboardingV2Test {
    private var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.state(tag: String = "\"opaque:9007199254740993\"", done: Boolean = false) =
        respond("""{"tour_id":"tour","done":$done}""",HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(tag)))

    @Test fun flowUsesCapturedProfileAndRetainsUnknownSteps() = runTest {
        val c = client {
            assertEquals("/api/v2/onboarding/flow", it.url.encodedPath)
            assertEquals("phone", it.url.parameters["surface"])
            assertEquals(scope, it.attributes[AuthScopeAttributeKey])
            respond("""{"version":1,"tour_id":"tour","steps":[{"id":"future","kind":"future_kind"}]}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }
        try {
            assertEquals("future_kind",assertIs<ApiResult.Success<OnboardingFlow>>(OnboardingApi(c, tokens, ApiV2Gate.Unrestricted).getFlow("phone",scope)).data.steps.single().kind)
        } finally { c.close() }
    }

    @Test fun serialWritesUseExactLatestConfirmedValidator() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val c = client {
            assertEquals(scope,it.attributes[AuthScopeAttributeKey])
            if (it.method == HttpMethod.Get) state() else {
                writes++
                assertEquals("/api/v2/onboarding/progress",it.url.encodedPath)
                assertEquals(HttpMethod.Put,it.method)
                assertTrue(it.attributes[SingleAttemptAttributeKey])
                assertEquals(if (writes == 1) "\"opaque:9007199254740993\"" else "\"next\"",it.headers[HttpHeaders.IfMatch])
                if (writes == 1) { entered.complete(Unit); release.await() }
                state(if (writes == 1) "\"next\"" else "\"done\"", writes == 2)
            }
        }
        try {
            val api = OnboardingApi(c, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Success<OnboardingState>>(api.getState(scope))
            val first = async { api.putProgress(OnboardingProgressRequest("tour", "step"),scope) }
            entered.await()
            val second = async { api.putProgress(OnboardingProgressRequest("tour", completed = true),scope) }
            release.complete(Unit)
            assertIs<ApiResult.Success<Unit>>(first.await()); assertIs<ApiResult.Success<Unit>>(second.await())
            assertEquals(2,writes)
        } finally { c.close() }
    }

    @Test fun uncertaintyAnd401ConsumeValidatorWithoutReplayOrRefetch() = runTest {
        var mode = 0
        var reads = 0
        var writes = 0
        val c = client {
            if (it.method == HttpMethod.Get) { reads++; state() } else {
                writes++
                if (mode == 0) throw IllegalStateException("lost receipt")
                respond("""{"detail":"Rejected"}""",if (mode == 1) HttpStatusCode.Unauthorized else HttpStatusCode.PreconditionFailed,headersOf(HttpHeaders.ContentType,"application/problem+json"))
            }
        }
        try {
            val api = OnboardingApi(c, tokens, ApiV2Gate.Unrestricted)
            for (i in 0..2) {
                mode = i
                api.getState(scope)
                assertFalse(api.putProgress(OnboardingProgressRequest("tour",completed = true),scope) is ApiResult.Success)
                assertEquals("onboarding_state_required",assertIs<ApiResult.Error>(api.putProgress(OnboardingProgressRequest("tour",completed = true),scope)).error)
            }
            assertEquals(3,reads); assertEquals(3,writes)
        } finally { c.close() }
    }

    @Test fun missingValidatorAndLateAuthorityFailClosed() = runTest {
        var mode = 0
        var calls = 0
        val c = client {
            calls++
            when (mode) {
                0 -> state("W/\"weak\"")
                1 -> state()
                else -> { scope = scope.copy(identityGeneration = 2); state("\"done\"",true) }
            }
        }
        try {
            val api = OnboardingApi(c, tokens, ApiV2Gate.Unrestricted)
            val original = scope
            assertIs<ApiResult.Error>(api.getState(original))
            assertIs<ApiResult.Error>(api.putProgress(OnboardingProgressRequest("tour"),original))
            assertEquals(1,calls)
            mode = 1; api.getState(original)
            mode = 2
            assertEquals("identity_changed",assertIs<ApiResult.Error>(api.putProgress(OnboardingProgressRequest("tour",completed = true),original)).error)
            assertIs<ApiResult.Error>(api.putProgress(OnboardingProgressRequest("tour"),original))
            assertEquals(3,calls)
        } finally { c.close() }
    }
}
