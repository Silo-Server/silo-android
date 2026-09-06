package org.siloserver.silo.common.data.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.network.apiv2.*
import org.siloserver.silo.repository.*
import org.siloserver.silo.repository.port.MembershipPort
import org.siloserver.silo.viewmodel.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MembershipActivationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, SiloDatabase::class.java)
        .addCallback(SiloDatabase.CALLBACK).allowMainThreadQueries().build()
    private val barrier = DefaultIdentityTransitionBarrier()
    private var authority = DurableLoginAuthority("login-one", AuthScopeSnapshot("s", "p", "https://example.invalid", null, identityGeneration = 0))
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = authority.scope
    }
    private val authorities = object : DurableLoginAuthorityProvider {
        override suspend fun snapshotDurableLoginAuthority() = authority
    }
    private val clients = mutableListOf<HttpClient>()
    private val viewModels = mutableListOf<ViewModel>()
    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(SiloJson) }
    }.also { clients += it }
    private fun repository(client: HttpClient): Pair<PersonalDataRepository, RoomMembershipPort> {
        val port = RoomMembershipPort(db, MembershipV2Api(client, tokenManager = tokens), tokens, authorities, barrier)
        return PersonalDataRepository(PersonalDataApi(client), identityTransitions = barrier, membershipPort = port) to port
    }
    private fun page(next: Boolean, second: Boolean = false) = """{"items":[{"content_id":"${if(second) "second" else "item"}","type":"movie","title":"Film"}],"page":{"has_more":$next${if(next) ",\"next_cursor\":\"next\"" else ""}},"total":2,"total_exact":true,"window_cursor":"window"}"""
    @AfterTest fun close() { viewModels.forEach { it.viewModelScope.cancel() }; clients.forEach { it.close() }; db.close(); Dispatchers.resetMain() }

    @Test fun actualFavoritesAndWatchlistRetainFailedRowsTotalsAndCursorThenReconcileByGet() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (kind in MembershipPort.Kind.entries) {
            val methods = mutableListOf<HttpMethod>()
            val client = client(MockEngine { request ->
                when {
                    request.url.encodedPath == "/api/v2/catalog" -> {
                        val second = request.url.parameters["cursor"] != null
                        if (second) assertEquals("next", request.url.parameters["cursor"])
                        respond(page(!second, second), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    request.url.encodedPath.startsWith("/api/v2/${if(kind == MembershipPort.Kind.FAVORITE) "favorites" else "watchlist"}/") -> {
                        methods += request.method
                        respond("", if(request.method == HttpMethod.Get) HttpStatusCode.NotFound else HttpStatusCode.ServiceUnavailable)
                    }
                    else -> error("Unexpected legacy transport ${request.url.encodedPath}")
                }
            })
            val (repository, _) = repository(client)
            val catalog = CatalogRepository(CatalogApi(client, CatalogV2Api(client, tokenManager = tokens)), identityTransitions = barrier)
            val vm = if(kind == MembershipPort.Kind.FAVORITE) FavoritesViewModel(repository, catalog) else WatchlistViewModel(repository, catalog)
            viewModels += vm
            vm.uiState.first { !it.isLoading }
            if(vm is FavoritesViewModel) vm.toggleFavorite("item") else (vm as WatchlistViewModel).removeFromWatchlist("item")
            repository.memberships.actions.first { it.values.any { action -> action.completion != null } }
            assertEquals(listOf("item"), vm.uiState.value.items.map { it.contentId })
            assertEquals(2, vm.uiState.value.total); assertTrue(vm.uiState.value.hasMore)
            vm.loadMore(); vm.uiState.first { it.items.size == 2 }
            assertEquals(listOf("item", "second"), vm.uiState.value.items.map { it.contentId })
            val intent = repository.memberships.actions.value.values.single().intent
            repository.memberships.checkStatus(intent)
            vm.uiState.first { it.items.size == 1 }
            assertEquals(listOf("second"), vm.uiState.value.items.map { it.contentId })
            assertEquals(1, vm.uiState.value.total)
            assertEquals(listOf(HttpMethod.Delete, HttpMethod.Get), methods)
            vm.viewModelScope.cancel()
        }
    }

    @Test fun oldLoginAckAndRepeatedUncertainChoiceCannotPublishOrReplayWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var writes = 0; var reads = 0
        val client = client(MockEngine {
            if(it.method == HttpMethod.Get) { reads++; respond("", HttpStatusCode.NotFound) }
            else { writes++; entered.complete(Unit); release.await(); respond("", HttpStatusCode.ServiceUnavailable) }
        })
        val (repository, _) = repository(client)
        val actions = repository.memberships
        val first = actions.begin("item", MembershipPort.Kind.FAVORITE, false)
        val pending = async { actions.perform(first) }; entered.await(); release.complete(Unit); pending.await()
        val repeated = actions.begin("item", MembershipPort.Kind.FAVORITE, false)
        assertEquals(first, repeated)
        actions.perform(repeated)
        assertEquals(1, writes); assertEquals(1, reads); assertTrue(actions.confirmed(first))
        val old = actions.begin("item", MembershipPort.Kind.WATCHLIST, true)
        barrier.changing(IdentityTransitionKind.ACCOUNT_REPLACE) {
            authority = authority.copy(loginId = "new-login", scope = authority.scope.copy(identityGeneration = 1))
        }
        actions.perform(old)
        assertFalse(actions.current(old)); assertEquals(1, writes)
        assertTrue(actions.actions.value.isEmpty())
    }

    @Test fun inlineRepositoryAndWorkerUseOneRuntimeAndUncertainWorkDoesNotRetryWorker() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val methods = mutableListOf<HttpMethod>()
        val client = client(MockEngine { methods += it.method; respond("", HttpStatusCode.ServiceUnavailable) })
        val (repository, port) = repository(client)
        val intent = repository.memberships.begin("item", MembershipPort.Kind.FAVORITE, true)
        repository.memberships.perform(intent)
        val worker = SyncEngine(db, PersonalDataApi(client), EbookReaderApi(client), { authority.scope }, memberships = port)
        assertFalse(worker.drainOnce().hasPendingWork)
        assertFalse(worker.drainOnce().hasPendingWork)
        assertEquals(listOf(HttpMethod.Put), methods)
        assertEquals(1, db.dirtyOperationDao().count())
    }
    @Test fun homeFailedFavoriteDoesNotRollbackConcurrentWatchlistOrRefreshedContent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var title = "Original"
        val client = client(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v2/favorites/item" -> { entered.complete(Unit); release.await(); respond("", HttpStatusCode.ServiceUnavailable) }
                "/api/v2/watchlist/item" -> respond("", HttpStatusCode.NoContent)
                else -> respond("""{"sections":[{"id":"row","section_type":"row","title":"Home","items":[{"content_id":"item","type":"movie","title":"$title"}]}]}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val (repository, _) = repository(client)
        val vm = HomeViewModel(SectionRepository(SectionApi(client), identityTransitions = barrier,
            homeRequestDispatcher = StandardTestDispatcher(testScheduler)),
            org.siloserver.silo.domain.MediaActionsCoordinator(repository), identityTransitions = barrier)
        viewModels += vm
        vm.uiState.first { !it.isLoading }
        vm.toggleFavorite("item", true); entered.await()
        vm.toggleWatchlist("item", true)
        vm.uiState.first { it.sections.single().items.single().userState?.inWatchlist == true }
        title = "Refreshed"; vm.refresh()
        vm.uiState.first { it.sections.single().items.single().title == "Refreshed" }
        release.complete(Unit)
        repository.memberships.actions.first { actions -> actions.values.any {
            it.intent.key.kind == MembershipPort.Kind.FAVORITE && it.completion != null } }
        val item = vm.uiState.value.sections.single().items.single()
        assertEquals("Refreshed", item.title)
        assertTrue(item.userState?.inWatchlist == true)
        assertFalse(item.userState?.isFavorite == true)
    }

    @Test fun restoredUiWitnessObservesWorkerAcknowledgementThroughRoomInvalidation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = client(MockEngine { respond("", HttpStatusCode.NoContent) })
        val (repository, port) = repository(client)
        val command = port.record(authority, "worker-item", MembershipPort.Kind.WATCHLIST, true)
        val observer = backgroundScope.launch { repository.memberships.observeChanges() }
        val pending = repository.memberships.actions.first { it.isNotEmpty() }.values.single()
        assertEquals(command.id, pending.command?.id)
        assertFalse(pending.confirmed)
        assertEquals(MembershipPort.Disposition.ACKNOWLEDGED, port.dispatch().single().disposition)
        repository.memberships.actions.first { it.values.single().confirmed }
        assertTrue(repository.memberships.confirmed(pending.intent))
        observer.cancel()
    }

}
