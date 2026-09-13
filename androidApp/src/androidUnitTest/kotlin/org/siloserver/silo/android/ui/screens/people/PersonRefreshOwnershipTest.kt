package org.siloserver.silo.android.ui.screens.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.apiv2.*
import org.siloserver.silo.repository.CatalogRepository
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PersonRefreshOwnershipTest {
    private var owner=AuthScopeSnapshot("s","p","https://example.invalid","pin",identityGeneration=1)
    private var onCapture: suspend () -> Unit = {}
    private val tokens=object:TokenManager by TokenManagerImpl(){override suspend fun snapshotCurrentScope(): AuthScopeSnapshot {
        val captured=owner; onCapture(); return captured
    }}
    private var posts=0; private var reads=0
    private var refusal=false; private var complete=false
    private var onRead:suspend ()->Unit={}
    private lateinit var engineDispatcher: CoroutineDispatcher
    private val client by lazy { HttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher=engineDispatcher
        addHandler {
        val path=it.url.encodedPath
        val body=when {
            path.endsWith("/refresh") -> {posts++; """{"status":"queued","person_id":"7"}"""}
            path.endsWith("/people/7") -> {reads++; onRead(); if(complete) """{"id":"7","name":"Person","bio":"Ready","photo_url":"photo","birth_date":"2000"}""" else """{"id":"7","name":"Person"}"""}
            else -> """{"items":[],"page":{"has_more":false},"total":0,"total_exact":true,"window_cursor":"window"}"""
        }
        respond(body,if(path.endsWith("/refresh")) {if(refusal) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Accepted} else HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
    } } )) }
    private fun vm()=PersonDetailViewModel(CatalogRepository(CatalogApi(client,CatalogV2Api(client, ApiV2Gate.Unrestricted, tokens),PersonRefreshV2Api(client, tokens, ApiV2Gate.Unrestricted))),SavedStateHandle(mapOf("personId" to 7)),tokens)
    private fun scenario(block:suspend TestScope.(PersonDetailViewModel)->Unit)=runTest {
        engineDispatcher=StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(engineDispatcher)
        val store=ViewModelStore()
        try { val v=vm(); store.put("person",v); block(v) } finally {store.clear(); client.close(); Dispatchers.resetMain()}
    }
    @Test fun oncePerViewRefusalDoesNotPollOrResendOnReload()=scenario {v->
        refusal=true; runCurrent(); assertEquals(1,posts); assertEquals(1,reads)
        assertFalse(v.uiState.value.isRefreshingMetadata)
        v.reload(); runCurrent(); advanceTimeBy(150_000); runCurrent()
        assertEquals(1,posts); assertEquals(2,reads)
    }
    @Test fun unchangedDetailsStopAfterFiveReadsWithoutAnotherPost()=scenario {v->
        runCurrent(); advanceTimeBy(15_001); runCurrent()
        assertEquals(1,posts); assertEquals(6,reads); assertFalse(v.uiState.value.isRefreshingMetadata)
    }
    @Test fun completeDetailPublishesAndStops()=scenario {v->
        runCurrent(); complete=true; advanceTimeBy(3_001); runCurrent()
        assertEquals("Ready",v.uiState.value.person?.bio); assertFalse(v.uiState.value.isRefreshingMetadata)
        advanceTimeBy(150_000);runCurrent();assertEquals(2,reads);assertEquals(1,posts)
    }
    @Test fun latePinReplyCannotPublishOrContinuePolling()=scenario {v->
        runCurrent(); complete=true
        onRead={owner=owner.copy(profileToken="replacement")}
        advanceTimeBy(3_001);runCurrent();assertEquals(null,v.uiState.value.person?.bio)
        advanceTimeBy(150_000);runCurrent();assertEquals(2,reads);assertEquals(1,posts)
    }
    @Test fun reloadCancelsOldPollWithoutReposting()=scenario {v->
        runCurrent(); v.reload(); complete=true;runCurrent()
        advanceTimeBy(150_000);runCurrent()
        assertEquals(1,posts);assertEquals(2,reads);assertEquals("Ready",v.uiState.value.person?.bio)
    }
    @Test fun suspendedOldAuthorityCheckCannotPublishAfterReload()=scenario {v->
        runCurrent()
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        complete=true
        onCapture={ if(reads==2 && !entered.isCompleted) {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }
        } }
        advanceTimeBy(3_001); runCurrent(); assertTrue(entered.isCompleted)
        complete=false; onCapture={}; v.reload(); runCurrent()
        assertEquals(null,v.uiState.value.person?.bio)
        release.complete(Unit); runCurrent()
        assertEquals(null,v.uiState.value.person?.bio)
        assertFalse(v.uiState.value.isRefreshingMetadata); assertEquals(1,posts)
        advanceTimeBy(150_000);runCurrent();assertEquals(3,reads)
    }

}
