package org.siloserver.silo.tv.ui.screens.library

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.network.apiv2.LibrarySectionItemsV2Api
import org.siloserver.silo.repository.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TvLibrarySectionOwnershipTest {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private var inlined = false
    private var title = "Old"
    private var layoutHook: suspend () -> Unit = {}
    private var sectionHook: suspend () -> Unit = {}
    private var authorityHook: suspend () -> Unit = {}
    private var fallbackReads = 0
    private var failFallback = false
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot { val value = owner; authorityHook(); return value }
    }
    private fun scenario(block: suspend TestScope.(TvLibraryDetailViewModel) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                val captured = title
                val card = """{"content_id":"movie:a","type":"movie","title":"$captured"}"""
                val section = """{"id":"row","section_type":"custom","title":"Row","total_count":1,"items":[$card]}"""
                val body = when (request.url.encodedPath) {
                    "/api/v2/library/3/sections" -> {
                        layoutHook()
                        if (inlined) """{"sections":[$section]}"""
                        else """{"sections":[{"id":"row","section_type":"custom","title":"Row","total_count":1,"items":[]}]}"""
                    }
                    "/api/v2/library/3/sections/row/items" -> { fallbackReads++; sectionHook(); section }
                    else -> error("Unexpected request")
                }
                respond(body, if (failFallback && request.url.encodedPath.endsWith("/items")) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(ContentNegotiation) { json(SiloJson) } }
        val store = ViewModelStore()
        try {
            val sections = SectionRepository(SectionApi(client, sectionItems = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted)))
            val vm = TvLibraryDetailViewModel(sections, CatalogRepository(CatalogApi(client)), 3, "Library", "movie")
            store.put("library", vm); block(vm)
        } finally { store.clear(); client.close(); Dispatchers.resetMain() }
    }
    @Test fun populatedLayoutSkipsFallbackAndUnresolvedUsesBareSection() = scenario { vm ->
        runCurrent(); assertEquals(1, fallbackReads)
        assertEquals("Old", vm.uiState.value.sections.single().items.single().title)
        inlined = true; title = "New"; vm.retryRecommended(); runCurrent()
        assertEquals(1, fallbackReads); assertEquals("New", vm.uiState.value.sections.single().items.single().title)
    }
    @Test fun changedLayoutOwnerCannotDispatchFallback() = scenario { vm ->
        layoutHook = { owner = owner.copy(profileToken = "new") }
        runCurrent(); assertEquals(0, fallbackReads); assertTrue(vm.uiState.value.sections.isEmpty())
        assertFalse(vm.uiState.value.recommendedLoading)
    }
    @Test fun failedFallbackStaysHiddenAndLateOwnerClearsRows() = scenario { vm ->
        failFallback = true; runCurrent()
        // Existing TV filtering hides the retained, still-empty original section.
        assertTrue(vm.uiState.value.sections.isEmpty()); assertFalse(vm.uiState.value.recommendedLoading)
        failFallback = false
        sectionHook = { owner = owner.copy(profileToken = "new") }
        vm.retryRecommended(); runCurrent()
        assertTrue(vm.uiState.value.sections.isEmpty()); assertFalse(vm.uiState.value.recommendedLoading)
    }
    @Test fun suspendedOldAuthorityCannotOverwriteReplacement() = scenario { vm ->
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); var checks = 0
        authorityHook = { if (fallbackReads == 1 && ++checks == 2) { entered.complete(Unit); gate.await() } }
        runCurrent(); assertTrue(entered.isCompleted)
        authorityHook = {}; title = "New"; vm.retryRecommended(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("New", vm.uiState.value.sections.single().items.single().title)
        assertFalse(vm.uiState.value.recommendedLoading)
    }
}
