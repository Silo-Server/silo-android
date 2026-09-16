package org.siloserver.silo.viewmodel

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.RecommendationApi
import org.siloserver.silo.network.apiv2.TasteProfileV2Api
import org.siloserver.silo.network.apiv2.DiscoverV2Api
import org.siloserver.silo.repository.RecommendationRepository
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationsTasteOwnershipTest {
    private var owner=AuthScopeSnapshot("s","p","https://example.invalid","pin",identityGeneration=1)
    private var captureHook:suspend ()->Unit={}
    private val tokens=object:TokenManager by TokenManagerImpl(){override suspend fun snapshotCurrentScope(): AuthScopeSnapshot {
        val value=owner;captureHook();return value
    }}
    private var tasteReads=0
    private var genre="Old"
    private var readHook:suspend ()->Unit={}
    private fun scenario(block:suspend TestScope.(RecommendationsViewModel)->Unit)=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);Dispatchers.setMain(dispatcher)
        val c=HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher=dispatcher
            addHandler {
                val body=if(it.url.encodedPath=="/api/v2/recommendations/taste-profile") {
                    tasteReads++;val captured=genre;readHook()
                    """{"top_genres":["$captured"],"favorite_directors":[],"signal_counts":{}}"""
                } else {
                    assertEquals("/api/v2/recommendations/discover",it.url.encodedPath)
                    """{"items":[{"type":"movie","title":"For You","kind":"for-you-main","items":[{"content_id":"$genre","type":"movie","title":"$genre"}]}],"page":{"has_more":false}}"""
                }
                respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }))
        val store=ViewModelStore()
        try {
            val vm=RecommendationsViewModel(RecommendationRepository(RecommendationApi(c,taste=TasteProfileV2Api(c, tokens, ApiV2Gate.Unrestricted),discover=DiscoverV2Api(c, tokens, ApiV2Gate.Unrestricted))))
            store.put("recommendations",vm);block(vm)
        } finally {store.clear();c.close();Dispatchers.resetMain()}
    }
    @Test fun normalLoadAndExplicitRefreshPublishCurrentTaste()=scenario {vm->
        runCurrent();assertEquals(listOf("Old"),vm.uiState.value.tasteProfile?.topGenres)
        genre="New";vm.refresh();runCurrent()
        assertEquals(listOf("New"),vm.uiState.value.tasteProfile?.topGenres)
        assertFalse(vm.uiState.value.isRefreshing);assertEquals(2,tasteReads)
        assertEquals("New", vm.uiState.value.sections.single().items.single().contentId)
    }
    @Test fun latePinNeverPublishesTaste()=scenario {vm->
        readHook={owner=owner.copy(profileToken="new")}
        runCurrent();assertNull(vm.uiState.value.tasteProfile);assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.sections.isEmpty())
    }
    @Test fun oldSuspendedAuthorityCannotOverwriteReplacementRun()=scenario {vm->
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var checks=0
        captureHook={if(tasteReads==1 && ++checks==2) {entered.complete(Unit);withContext(NonCancellable){release.await()}}}
        runCurrent();assertTrue(entered.isCompleted)
        captureHook={};genre="New";vm.refresh();runCurrent()
        assertEquals(listOf("New"),vm.uiState.value.tasteProfile?.topGenres)
        release.complete(Unit);runCurrent();assertEquals(listOf("New"),vm.uiState.value.tasteProfile?.topGenres)
        assertFalse(vm.uiState.value.isRefreshing)
        assertEquals("New", vm.uiState.value.sections.single().items.single().contentId)
    }
}
