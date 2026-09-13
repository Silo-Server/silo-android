package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.domain.settings.ProfileSettingsController
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.repository.*
import org.siloserver.silo.repository.port.MembershipPort
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TvMembershipConsumerTest {
    @Test fun episodeProducerRetainsStateOnUncertaintyAndUpdatesOnlyAfterExplicitRead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val barrier = DefaultIdentityTransitionBarrier()
        val port = TestPort()
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val repository = PersonalDataRepository(PersonalDataApi(client), identityTransitions = barrier, membershipPort = port)
        val vm = createVm(client, repository, barrier)
        try {
            vm.onSetEpisodeFavorite("episode", true)
            advanceUntilIdle()
            assertEquals("episode", port.command?.itemId)
            assertEquals(MembershipPort.Kind.FAVORITE, port.command?.kind)
            assertNull(vm.uiState.value.episodeFavoriteStates["episode"])
            val intent = repository.memberships.actions.value.values.single().intent
            repository.memberships.checkStatus(intent)
            advanceUntilIdle()
            assertEquals(true, vm.uiState.value.episodeFavoriteStates["episode"])
            assertEquals(1, port.sends); assertEquals(1, port.reads)
        } finally { vm.viewModelScope.cancel(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun staleEpisodeCompletionCannotPublishAfterProfileChange() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val barrier = DefaultIdentityTransitionBarrier()
        val release = CompletableDeferred<Unit>()
        val port = TestPort(release)
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val repository = PersonalDataRepository(PersonalDataApi(client), identityTransitions = barrier, membershipPort = port)
        val vm = createVm(client, repository, barrier)
        try {
            vm.onSetEpisodeFavorite("episode", true); advanceUntilIdle()
            assertEquals(1, port.sends)
            barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) { }
            release.complete(Unit); advanceUntilIdle()
            assertNull(vm.uiState.value.episodeFavoriteStates["episode"])
            assertTrue(repository.memberships.actions.value.isEmpty())
        } finally { vm.viewModelScope.cancel(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun laterMembershipReadReplacesEpisodeBaselineButPreAckReadIsRejected() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val barrier = DefaultIdentityTransitionBarrier()
        val port = TestPort()
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val repository = PersonalDataRepository(PersonalDataApi(client), identityTransitions = barrier, membershipPort = port)
        val vm = createVm(client, repository, barrier)
        try {
            vm.onSetEpisodeFavorite("episode", true); advanceUntilIdle()
            val intent = repository.memberships.actions.value.values.single().intent
            repository.memberships.checkStatus(intent); advanceUntilIdle()
            assertEquals(true, vm.uiState.value.episodeFavoriteStates["episode"])
            assertIs<ApiResult.Success<Boolean>>(repository.isFavorite("episode"))
            advanceUntilIdle()
            assertEquals(false, vm.uiState.value.episodeFavoriteStates["episode"])

            val next = repository.memberships.begin("episode", MembershipPort.Kind.FAVORITE, true)
            repository.memberships.perform(next)
            port.readRelease = CompletableDeferred()
            val read = async { repository.isFavorite("episode") }; runCurrent()
            repository.memberships.checkStatus(next); advanceUntilIdle()
            assertEquals(true, vm.uiState.value.episodeFavoriteStates["episode"])
            port.readRelease!!.complete(Unit)
            assertIs<ApiResult.Error>(read.await()); advanceUntilIdle()
            assertEquals(true, vm.uiState.value.episodeFavoriteStates["episode"])
        } finally { vm.viewModelScope.cancel(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun laterReadsReplaceBothDetailFieldBaselines() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val barrier = DefaultIdentityTransitionBarrier()
        val port = TestPort()
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val repository = PersonalDataRepository(PersonalDataApi(client), identityTransitions = barrier, membershipPort = port)
        val vm = createVm(client, repository, barrier)
        try {
            for (kind in MembershipPort.Kind.entries) {
                val intent = repository.memberships.begin("", kind, true)
                repository.memberships.perform(intent); repository.memberships.checkStatus(intent)
                advanceUntilIdle()
                assertTrue(if (kind == MembershipPort.Kind.FAVORITE) vm.uiState.value.isFavorite else vm.uiState.value.inWatchlist)
                if (kind == MembershipPort.Kind.FAVORITE) repository.isFavorite("") else repository.isInWatchlist("")
                advanceUntilIdle()
                assertFalse(if (kind == MembershipPort.Kind.FAVORITE) vm.uiState.value.isFavorite else vm.uiState.value.inWatchlist)
            }
        } finally { vm.viewModelScope.cancel(); client.close(); Dispatchers.resetMain() }
    }

    private fun createVm(client: HttpClient, repository: PersonalDataRepository, barrier: IdentityTransitionBarrier): TvItemDetailViewModel {
        val settings = java.lang.reflect.Proxy.newProxyInstance(PlayerSettingsStore::class.java.classLoader,
            arrayOf(PlayerSettingsStore::class.java)) { _, method, _ ->
            check(method.name == "getPreferredQualityFlow") { "Unexpected settings call ${method.name}" }
            flowOf("auto")
        } as PlayerSettingsStore
        val tokens = TokenManagerImpl(barrier)
        return TvItemDetailViewModel(CatalogRepository(CatalogApi(client)), repository, settings,
            ProfileRepository(ProfileApi(client, ApiV2Gate.Unrestricted), tokens), ProfileSettingsController(SettingsRepository(SettingsApi(org.siloserver.silo.network.apiv2.SettingsV2Api(client, org.siloserver.silo.network.TokenManagerImpl(), ApiV2Gate.Unrestricted)))),
            MetadataAiRepository(DefaultMetadataAiApi(client, gate = ApiV2Gate.Unrestricted)), "", tokenManager = tokens, identityTransitions = barrier)
    }

    private class TestPort(private val release: CompletableDeferred<Unit>? = null) : MembershipPort {
        val authority = DurableLoginAuthority("login", AuthScopeSnapshot("s", "p", "https://example.invalid", null))
        var command: MembershipPort.Command? = null
        var sends = 0; var reads = 0
        var readRelease: CompletableDeferred<Unit>? = null
        override suspend fun captureAuthority() = authority
        override suspend fun record(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind, present: Boolean) =
            MembershipPort.Command(1, authority, itemId, kind, present).also { command = it }
        override suspend fun send(command: MembershipPort.Command): MembershipPort.Completion {
            sends++; release?.await()
            return MembershipPort.Completion(if(release == null) MembershipPort.Disposition.NEEDS_RECONCILIATION else MembershipPort.Disposition.ACKNOWLEDGED, release != null)
        }
        override suspend fun reconcile(command: MembershipPort.Command): MembershipPort.Completion {
            reads++; return MembershipPort.Completion(MembershipPort.Disposition.RECONCILED, true)
        }
        override suspend fun read(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind): ApiResult<Boolean> {
            readRelease?.await(); return ApiResult.Success(false)
        }
        override suspend fun pending(authority: DurableLoginAuthority, limit: Int) = emptyList<MembershipPort.Command>()
        override suspend fun hasLegacyQuarantine() = false
        override suspend fun dispatch(limit: Int) = emptyList<MembershipPort.Completion>()
        override suspend fun readyCount() = 0
        override suspend fun projection(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind): MembershipPort.Projection? = null
    }
}
