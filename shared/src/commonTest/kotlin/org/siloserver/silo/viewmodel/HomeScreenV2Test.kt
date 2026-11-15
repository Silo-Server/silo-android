package org.siloserver.silo.viewmodel

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.siloserver.silo.domain.MediaActionsCoordinator
import org.siloserver.silo.model.section.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.network.apiv2.HomeSectionsV2Api
import org.siloserver.silo.repository.*
import org.siloserver.silo.repository.port.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreenV2Test {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private val language = org.siloserver.silo.domain.settings.CatalogLanguageRevision()
    private var title = "Old"
    private var unresolved = false
    private var failFallback = false
    private var fallbackReads = 0
    private var cacheWrites = 0
    private var listReads = 0
    private var cached: HomeCacheSnapshot? = null
    private var cacheHook: suspend () -> Unit = {}
    private var overlayHook: suspend () -> Unit = {}
    private var listHook: suspend () -> Unit = {}
    private val cache = object : HomeCachePort {
        override suspend fun getCachedHomeV2(owner: AuthScopeSnapshot): HomeCacheSnapshot? { val value = cached; cacheHook(); return value }
        override suspend fun cacheHomeV2(sections: List<ResolvedSection>, owner: AuthScopeSnapshot, artworkFingerprint: String?, stillCurrent: () -> Boolean) { cacheWrites++; cached = HomeCacheSnapshot(sections, 1) }
    }
    private val local = object : UserItemStatePort by NoOpUserItemStatePort {
        override suspend fun localPlaybackProgressForContent(contentIds: List<String>): Map<String, LocalPlaybackProgress> { overlayHook(); return emptyMap() }
    }
    // Each test report is a new change: a minute apart on this clock.
    private var clockMs = 0L
    private val accessChanges = AccessChangeSignals { clockMs }
    private fun reportAccessChange() { clockMs += 60_000; accessChanges.reportAccessChanged() }
    private val realtime = HomeRealtimeCoordinator(
        object : HomeRealtimeClient { override fun connect() = kotlinx.coroutines.flow.emptyFlow<HomeRealtimeEvent>() },
        tokens, accessChanges,
    )
    private fun scenario(startShown: Boolean = true, block: suspend TestScope.(HomeViewModel) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                val snapshotTitle = title
                val row = """{"id":"row/a","section_type":"continue_watching","title":"Row","total_count":1,"items":[{"content_id":"movie:a","type":"movie","title":"$snapshotTitle"}]}"""
                val fallback = request.url.encodedPath.endsWith("/items")
                val body = if (fallback) {
                    assertEquals("/api/v2/home/sections/row%2Fa/items", request.url.encodedPath)
                    fallbackReads++; row
                } else {
                    assertEquals("/api/v2/home/sections", request.url.encodedPath)
                    listReads++; listHook()
                    if (unresolved) """{"sections":[{"id":"row/a","section_type":"continue_watching","title":"Row","total_count":1,"items":[]}]}"""
                    else """{"sections":[$row]}"""
                }
                respond(body, if (fallback && failFallback) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }))
        val store = ViewModelStore()
        try {
            val repository = SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted, decodeDispatcher = dispatcher)))
            val vm = HomeViewModel(repository, MediaActionsCoordinator(PersonalDataRepository(PersonalDataApi(client))), cache, local,
                homeRealtime = realtime, catalogLanguage = language, startShown = startShown)
            store.put("home", vm); block(vm)
        } finally { store.clear(); client.close(); Dispatchers.resetMain() }
    }
    @Test fun populatedAndFallbackReadsPreservePartialGoodHome() = scenario { vm ->
        runCurrent(); assertEquals(0, fallbackReads); assertEquals(1, cacheWrites)
        assertEquals("Old", vm.uiState.value.sections.single().items.single().title)
        unresolved = true; failFallback = true; title = "New"; vm.refresh(); runCurrent()
        assertEquals(1, fallbackReads); assertEquals(1, cacheWrites)
        assertEquals("Old", vm.uiState.value.sections.single().items.single().title)
        failFallback = false; vm.refresh(); runCurrent()
        assertEquals(2, fallbackReads); assertEquals(2, cacheWrites)
        assertEquals("New", vm.uiState.value.sections.single().items.single().title)
    }
    @Test fun suspendedBootstrapCacheCannotSupersedeNewRefresh() = scenario { vm ->
        val gate = CompletableDeferred<Unit>(); cacheHook = { gate.await() }
        runCurrent(); assertEquals(0, listReads)
        title = "New"; vm.refresh(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(1, listReads); assertEquals("New", vm.uiState.value.sections.single().items.single().title)
    }
    @Test fun latePinRefusesNetworkAndOverlayPublication() = scenario { vm ->
        listHook = { owner = owner.copy(profileToken = "new") }
        runCurrent(); assertTrue(vm.uiState.value.sections.isEmpty()); assertEquals(0, cacheWrites)
        listHook = {}; overlayHook = { owner = owner.copy(credentialEpoch = 2) }
        vm.refresh(); runCurrent(); assertTrue(vm.uiState.value.sections.isEmpty())
        assertFalse(vm.uiState.value.isRefreshing)
    }
    @Test fun oldSuspendedOverlayCannotReplaceNewRun() = scenario { vm ->
        val gate = CompletableDeferred<Unit>(); var first = true
        overlayHook = { if (first) { first = false; gate.await() } }
        runCurrent(); title = "New"; vm.refresh(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("New", vm.uiState.value.sections.single().items.single().title)
    }
    @Test fun languageRefreshCompletesSupersededManualRefreshAndDrainsRealtime() = scenario { vm ->
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        var delayed = true
        listHook = { if (delayed) { delayed = false; gate.await() } }
        vm.refresh(); runCurrent()
        assertTrue(vm.uiState.value.isRefreshing)
        vm.refreshFromRealtime()
        title = "Localized"; language.changed(); runCurrent()
        assertFalse(vm.uiState.value.isRefreshing)
        assertEquals(4, listReads)
        gate.complete(Unit); runCurrent()
        assertEquals("Localized", vm.uiState.value.sections.single().items.single().title)
    }

    @Test fun newerLanguageCancelsOlderLanguageRead() = scenario { vm ->
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        var delayed = true
        listHook = { if (delayed) { delayed = false; gate.await() } }
        title = "First language"; language.changed(); runCurrent()
        title = "Latest language"; language.changed(); runCurrent()
        assertEquals("Latest language", vm.uiState.value.sections.single().items.single().title)
        gate.complete(Unit); runCurrent()
        assertEquals("Latest language", vm.uiState.value.sections.single().items.single().title)
    }

    @Test fun hiddenHomeRunsOneDeferredRefreshWhenShown() = scenario(startShown = false) { vm ->
        runCurrent(); assertEquals(1, listReads)
        reportAccessChange(); advanceTimeBy(5_000); runCurrent()
        language.changed(); runCurrent()
        assertEquals(1, listReads, "a hidden Home must not re-read")
        title = "New"; vm.onShown(); vm.runPendingRealtimeRefresh(); runCurrent()
        assertEquals(2, listReads)
        assertEquals("New", vm.uiState.value.sections.single().items.single().title)
        vm.runPendingRealtimeRefresh(); runCurrent()
        assertEquals(2, listReads, "the deferred refresh runs once")
    }

    @Test fun shownHomeRefreshesLiveAndStopsWhenHidden() = scenario { vm ->
        runCurrent(); assertEquals(1, listReads)
        reportAccessChange(); advanceTimeBy(5_000); runCurrent()
        assertEquals(2, listReads)
        vm.onHidden()
        reportAccessChange(); advanceTimeBy(5_000); runCurrent()
        assertEquals(2, listReads)
        // The resume refresh covers the update that waited.
        vm.onShown(); vm.refreshFromRealtime(); runCurrent()
        assertEquals(3, listReads)
        vm.runPendingRealtimeRefresh(); runCurrent()
        assertEquals(3, listReads)
    }

    @Test fun hidingDropsARealtimeReadAndRunsItWhenShown() = scenario { vm ->
        runCurrent(); assertEquals(1, listReads)
        val gate = CompletableDeferred<Unit>(); listHook = { gate.await() }
        title = "New"; reportAccessChange(); advanceTimeBy(5_000); runCurrent()
        assertEquals(2, listReads)
        vm.onHidden(); gate.complete(Unit); runCurrent()
        assertEquals("Old", vm.uiState.value.sections.single().items.single().title, "the dropped read must not publish")
        listHook = {}
        vm.onShown(); vm.runPendingRealtimeRefresh(); runCurrent()
        assertEquals(3, listReads)
        assertEquals("New", vm.uiState.value.sections.single().items.single().title)
    }

    @Test fun hidingKeepsTheRealtimeReadThatFillsAnEmptyHome() = scenario { vm ->
        val gate = CompletableDeferred<Unit>(); listHook = { gate.await() }
        runCurrent(); assertTrue(vm.uiState.value.isLoading)
        // This read supersedes the first load, so only it can fill Home.
        reportAccessChange(); advanceTimeBy(5_000); runCurrent()
        assertEquals(2, listReads)
        vm.onHidden(); gate.complete(Unit); runCurrent()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals("Old", vm.uiState.value.sections.single().items.single().title)
    }

    @Test fun hiddenLanguageChangeKeepsTheReadThatFillsAnEmptyHome() = scenario { vm ->
        val gate = CompletableDeferred<Unit>(); listHook = { gate.await() }
        runCurrent()
        // This read supersedes the first load, so only it can fill Home.
        language.changed(); runCurrent()
        assertEquals(2, listReads)
        vm.onHidden(); language.changed(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals("Old", vm.uiState.value.sections.single().items.single().title)
        listHook = {}; title = "New"
        vm.onShown(); vm.runPendingRealtimeRefresh(); runCurrent()
        assertEquals(3, listReads)
        assertEquals("New", vm.uiState.value.sections.single().items.single().title)
    }

    @Test fun updateThatWaitedDuringFirstLoadRunsAfterIt() = scenario(startShown = false) { vm ->
        val gate = CompletableDeferred<Unit>(); listHook = { gate.await() }
        runCurrent(); assertEquals(1, listReads)
        reportAccessChange(); advanceTimeBy(5_000); runCurrent()
        vm.onShown(); vm.runPendingRealtimeRefresh(); runCurrent()
        assertEquals(1, listReads, "no second read beside the first load")
        listHook = {}; gate.complete(Unit); runCurrent()
        assertEquals(2, listReads)
    }
}
