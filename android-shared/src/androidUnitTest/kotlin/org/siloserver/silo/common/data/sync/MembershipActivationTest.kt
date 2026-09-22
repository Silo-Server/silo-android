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
        val port = RoomMembershipPort(db, MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens), tokens, authorities, barrier)
        return PersonalDataRepository(PersonalDataApi(client), identityTransitions = barrier, membershipPort = port) to port
    }
    private fun page(next: Boolean, second: Boolean = false) = """{"items":[{"content_id":"${if(second) "second" else "item"}","type":"movie","title":"Film"}],"page":{"has_more":$next${if(next) ",\"next_cursor\":\"next\"" else ""}},"total":2,"total_exact":true,"window_cursor":"window"}"""
    @AfterTest fun close() { viewModels.forEach { it.viewModelScope.cancel() }; clients.forEach { it.close() }; db.close(); Dispatchers.resetMain() }

    @Test fun productionRepositoryWiringSharesIdentityAcrossMembershipChanges() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val client = client(MockEngine { methods += it.method; respond("", HttpStatusCode.NoContent) })
        val port = RoomMembershipPort(db, MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens), tokens, authorities, barrier)
        val app = org.koin.dsl.koinApplication(createEagerInstances = false) {
            modules(org.siloserver.silo.di.repositoryModule, org.koin.dsl.module {
                single { PersonalDataApi(client) }
                single<MembershipPort> { port }
                single<IdentityTransitionBarrier> { barrier }
            })
        }
        try {
            barrier.changing(IdentityTransitionKind.SIGN_IN) {
                authority = authority.copy(scope = authority.scope.copy(identityGeneration = barrier.generation.value))
            }
            val actions = app.koin.get<PersonalDataRepository>().memberships
            for (kind in MembershipPort.Kind.entries) {
                val intent = actions.begin("item", kind, true)
                actions.perform(intent)
                assertTrue(actions.confirmed(intent), "$kind must save after sign-in")
            }
            barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) {
                authority = authority.copy(scope = authority.scope.copy(profileId = "other-profile", identityGeneration = barrier.generation.value))
            }
            assertTrue(actions.actions.value.isEmpty(), "Old profile membership must be cleared")
            for (kind in MembershipPort.Kind.entries) {
                val intent = actions.begin("item", kind, false)
                actions.perform(intent)
                assertTrue(actions.confirmed(intent), "$kind must save after switching profiles")
            }
            assertEquals(listOf(HttpMethod.Put, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Delete), methods)
        } finally {
            app.close()
        }
    }

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
            val catalog = CatalogRepository(CatalogApi(client, CatalogV2Api(client, ApiV2Gate.Unrestricted, tokens)), identityTransitions = barrier)
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
        val worker = SyncEngine(db, PersonalDataApi(client), EbookReaderApi(org.siloserver.silo.network.apiv2.EbookReaderV2Api(client, tokens, ApiV2Gate.Unrestricted)), { authority.scope }, memberships = port)
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
        val vm = HomeViewModel(SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted))),
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
        assertFalse(item.userState?.inWatchlist == true) // This read started after the watchlist acknowledgement.
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

    @Test fun acknowledgedHomeBaselineSurvivesOppositeFailureAndEarlierReadButYieldsToLaterRead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val readEntered = CompletableDeferred<Unit>(); val readRelease = CompletableDeferred<Unit>()
        val deleteEntered = CompletableDeferred<Unit>(); val deleteRelease = CompletableDeferred<Unit>()
        var homeReads = 0
        val client = client(MockEngine { request ->
            if (request.url.encodedPath == "/api/v2/favorites/item") {
                if (request.method == HttpMethod.Delete) { deleteEntered.complete(Unit); deleteRelease.await(); respond("", HttpStatusCode.ServiceUnavailable) }
                else respond("", HttpStatusCode.NoContent)
            } else {
                if (++homeReads == 2) { readEntered.complete(Unit); readRelease.await() }
                respond("""{"sections":[{"id":"row","section_type":"row","title":"Home","items":[{"content_id":"item","type":"movie","title":"Film","user_state":{"is_favorite":false}}]}]}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val (repository, _) = repository(client)
        val vm = HomeViewModel(SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted))),
            org.siloserver.silo.domain.MediaActionsCoordinator(repository), identityTransitions = barrier)
        viewModels += vm
        vm.uiState.first { !it.isLoading }
        vm.refresh(); readEntered.await() // Captures no acknowledgement yet.
        vm.toggleFavorite("item", true)
        vm.uiState.first { it.sections.single().items.single().userState?.isFavorite == true }
        readRelease.complete(Unit); vm.uiState.first { !it.isRefreshing }
        assertTrue(vm.uiState.value.sections.single().items.single().userState!!.isFavorite)
        vm.toggleFavorite("item", false); deleteEntered.await(); runCurrent()
        assertTrue(vm.uiState.value.sections.single().items.single().userState!!.isFavorite)
        deleteRelease.complete(Unit)
        repository.memberships.actions.first { it.values.single().completion?.disposition == MembershipPort.Disposition.NEEDS_RECONCILIATION }
        assertTrue(vm.uiState.value.sections.single().items.single().userState!!.isFavorite)
        vm.refresh()
        vm.uiState.first { !it.isRefreshing && it.sections.single().items.single().userState?.isFavorite == false }
    }

    @Test fun freshPersonalListReadSupersedesRemovalButEarlierReadCannotRestoreIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (kind in MembershipPort.Kind.entries) {
            var reads = 0
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val client = client(MockEngine { request ->
                if (request.url.encodedPath == "/api/v2/catalog") {
                    if (++reads == 2) { entered.complete(Unit); release.await() }
                    respond(page(false), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                } else respond("", HttpStatusCode.NoContent)
            })
            val (repository, _) = repository(client)
            val catalog = CatalogRepository(CatalogApi(client, CatalogV2Api(client, ApiV2Gate.Unrestricted, tokens)), identityTransitions = barrier)
            val vm = if (kind == MembershipPort.Kind.FAVORITE) FavoritesViewModel(repository, catalog) else WatchlistViewModel(repository, catalog)
            viewModels += vm
            vm.uiState.first { !it.isLoading }
            vm.refresh(); entered.await()
            if (vm is FavoritesViewModel) vm.toggleFavorite("item") else (vm as WatchlistViewModel).removeFromWatchlist("item")
            vm.uiState.first { it.items.isEmpty() }
            release.complete(Unit); vm.uiState.first { !it.isRefreshing }
            assertTrue(vm.uiState.value.items.isEmpty()); assertEquals(1, vm.uiState.value.total)
            vm.refresh()
            vm.uiState.first { !it.isRefreshing && it.items.isNotEmpty() }
            assertEquals(2, vm.uiState.value.total)
            assertEquals("item", vm.uiState.value.items.single().contentId)
            vm.viewModelScope.cancel()
        }
    }

    @Test fun postAckHomeRefreshCannotReusePreAckRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var reads = 0
        val client = client(MockEngine { request ->
            if (request.url.encodedPath == "/api/v2/favorites/item") respond("", HttpStatusCode.NoContent)
            else {
                val ordinal = ++reads
                if (ordinal == 2) { entered.complete(Unit); release.await() }
                respond("""{"sections":[{"id":"row","section_type":"row","title":"Home","items":[{"content_id":"item","type":"movie","title":"Read $ordinal","user_state":{"is_favorite":false}}]}]}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val (repository, _) = repository(client)
        val vm = HomeViewModel(SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted))),
            org.siloserver.silo.domain.MediaActionsCoordinator(repository), identityTransitions = barrier)
        viewModels += vm
        vm.uiState.first { !it.isLoading }
        vm.refresh(); entered.await()
        vm.toggleFavorite("item", true)
        vm.uiState.first { it.sections.single().items.single().userState?.isFavorite == true }
        vm.refresh()
        vm.uiState.first { it.sections.single().items.single().title == "Read 3" }
        assertFalse(vm.uiState.value.sections.single().items.single().userState!!.isFavorite)
        release.complete(Unit); advanceUntilIdle()
        assertEquals("Read 3", vm.uiState.value.sections.single().items.single().title)
        assertFalse(vm.uiState.value.sections.single().items.single().userState!!.isFavorite)
    }

    @Test fun authoritativeMembershipGetsReplaceSharedDisplayBaselineForBothFields() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val methods = mutableListOf<HttpMethod>()
        val client = client(MockEngine { request ->
            methods += request.method
            respond("", if (request.method == HttpMethod.Get) HttpStatusCode.NotFound else HttpStatusCode.NoContent)
        })
        val (repository, _) = repository(client)
        for (kind in MembershipPort.Kind.entries) {
            val intent = repository.memberships.begin("item", kind, true)
            repository.memberships.perform(intent)
            assertEquals(true, repository.memberships.actions.value.getValue(intent.key).baseline?.present)
            val result = if (kind == MembershipPort.Kind.FAVORITE) repository.isFavorite("item") else repository.isInWatchlist("item")
            assertEquals(false, assertIs<ApiResult.Success<Boolean>>(result).data)
            val action = repository.memberships.actions.value.getValue(intent.key)
            assertEquals(false, action.baseline?.present)
            assertEquals(intent, action.intent) // GET updates display truth, not the user's command.
            assertEquals(MembershipPort.Disposition.ACKNOWLEDGED, action.completion?.disposition)
        }
        assertEquals(listOf(HttpMethod.Put, HttpMethod.Get, HttpMethod.Put, HttpMethod.Get), methods)
    }

}
