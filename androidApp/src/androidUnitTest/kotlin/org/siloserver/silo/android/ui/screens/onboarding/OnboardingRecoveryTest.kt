package org.siloserver.silo.android.ui.screens.onboarding

import org.siloserver.silo.network.apiv2.ApiV2Gate

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.repository.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class OnboardingRecoveryTest {
    @Test fun failedIntermediateProgressRecoversOnExplicitDoneWithoutReplay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scope = AuthScopeSnapshot("server","profile","https://example.invalid",null,identityGeneration=1)
        val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
        val events = mutableListOf<String>()
        var writes = 0
        val intermediateSent = CompletableDeferred<Unit>()
        val c = HttpClient(MockEngine {
            val path = it.url.encodedPath
            when {
                path.endsWith("flow") -> respond("""{"version":1,"tour_id":"tour","steps":[{"id":"one","kind":"welcome"},{"id":"two","kind":"handoff"}]}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
                path.endsWith("state") -> {
                    events += "read"
                    respond("""{"tour_id":"tour","done":false}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType to listOf("application/json"),HttpHeaders.ETag to listOf(if (writes == 0) "\"first\"" else "\"reconciled\"")))
                }
                else -> {
                    writes++
                    events += "write$writes"
                    if (writes == 1) { intermediateSent.complete(Unit); throw IllegalStateException("offline") }
                    assertEquals(HttpMethod.Put,it.method)
                    assertEquals("\"reconciled\"",it.headers[HttpHeaders.IfMatch])
                    assertTrue(it.body.toByteArray().decodeToString().contains("\"completed\":true"))
                    respond("""{"tour_id":"tour","done":true}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType to listOf("application/json"),HttpHeaders.ETag to listOf("\"done\"")))
                }
            }
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val context = RuntimeEnvironment.getApplication()
            context.getSharedPreferences("onboarding_tour",0).edit().clear().commit()
            val cache = OnboardingTourLocalCache(context)
            val settings = java.lang.reflect.Proxy.newProxyInstance(PlayerSettingsStore::class.java.classLoader,
                arrayOf(PlayerSettingsStore::class.java)) { _, method, _ -> error("Unexpected setting call ${method.name}") } as PlayerSettingsStore
            val vm = OnboardingTourViewModel(OnboardingRepository(OnboardingApi(c, tokens, ApiV2Gate.Unrestricted)),ProfileRepository(ProfileApi(c, ApiV2Gate.Unrestricted),tokens),settings,tokens,cache)
            vm.load(); vm.uiState.first { !it.isLoading }
            vm.onAdvance(); intermediateSent.await()
            assertEquals(listOf("read","write1"),events)
            assertFalse(cache.isDone("server","profile"))
            // Connectivity has recovered; the next explicit decision is completion.
            vm.onFinish()
            withContext(Dispatchers.Default) {
                withTimeout(5000) { while (!cache.isDone("server","profile")) delay(10) }
            }
            assertEquals(listOf("read","write1","read","write2"),events)
            assertTrue(cache.isDone("server","profile"))
            assertTrue(vm.uiState.value.finished)
        } finally { c.close(); Dispatchers.resetMain() }
    }
}
