package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.siloserver.silo.domain.settings.ProfileSettingsController
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.repository.*
import org.siloserver.silo.repository.port.*
import org.siloserver.silo.tv.testing.FakePlayerSettingsStore
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TvPersonalMutationOwnershipTest {
    private class Identity : TokenManager by TokenManagerImpl() {
        var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof", identityGeneration = 0)
        override suspend fun snapshotCurrentScope() = scope
        override suspend fun getProfileId() = scope.profileId
    }
    private class Port(val identity: Identity) : UserItemStatePort by NoOpUserItemStatePort {
        private var next = 0L
        val pending = mutableMapOf<String?, PersonalWriteHandle>()
        override suspend fun beginPersonalWrite(command: PersonalWrite): PersonalWriteHandle? {
            val scope = identity.scope
            if (pending.containsKey(scope.profileId)) return null
            return PersonalWriteHandle(++next, scope, command).also { pending[scope.profileId] = it }
        }
        override suspend fun completePersonalWrite(handle: PersonalWriteHandle) { pending.remove(handle.scope.profileId) }
    }
    private class Fixture {
        val identity = Identity()
        val barrier = DefaultIdentityTransitionBarrier()
        val requests = Channel<CompletableDeferred<HttpStatusCode>>(Channel.UNLIMITED)
        val port = Port(identity)
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/api/v2/watched/") || request.url.encodedPath.startsWith("/api/v2/ratings/")) {
                val reply = CompletableDeferred<HttpStatusCode>()
                requests.send(reply)
                respond("", reply.await())
            } else respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val personal = PersonalDataRepository(PersonalDataApi(client, tokenManager = identity), port, identityTransitions = barrier)
        val vm = TvItemDetailViewModel(
            catalogRepository = CatalogRepository(CatalogApi(client)),
            personalDataRepository = personal,
            playerSettingsStore = FakePlayerSettingsStore(),
            profileRepository = ProfileRepository(ProfileApi(client, ApiV2Gate.Unrestricted), identity),
            profileSettings = ProfileSettingsController(SettingsRepository(SettingsApi(org.siloserver.silo.network.apiv2.SettingsV2Api(client, org.siloserver.silo.network.TokenManagerImpl(), ApiV2Gate.Unrestricted)))),
            metadataAiRepository = MetadataAiRepository(DefaultMetadataAiApi(client, gate = ApiV2Gate.Unrestricted)),
            contentId = "item", tokenManager = identity, identityTransitions = barrier,
        )
        suspend fun changeProfile() {
            barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) {
                identity.scope = identity.scope.copy(profileId = "next-profile", identityGeneration = barrier.generation.value)
            }
        }
        suspend fun request() = withContext(Dispatchers.IO) { withTimeout(5_000) { requests.receive() } }
        suspend fun state(predicate: (TvItemDetailUiState) -> Boolean) = withContext(Dispatchers.IO) { withTimeout(5_000) { vm.uiState.first(predicate) } }
        fun close() { vm.viewModelScope.cancel(); client.close(); requests.close() }
    }

    @Test fun invalidationReleasesWatchedBusyButLateReplyCannotReleaseNewOwnersBusy() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val f = Fixture()
        try {
            f.vm.onToggleWatched()
            val oldJob = f.vm.viewModelScope.coroutineContext.job.children.last()
            val old = f.request()
            assertTrue(f.vm.uiState.value.isTogglingWatched)
            f.changeProfile()
            f.state { !it.isTogglingWatched }
            f.vm.onToggleWatched()
            val newer = f.request()
            assertTrue(f.vm.uiState.value.isTogglingWatched)
            old.complete(HttpStatusCode.ServiceUnavailable)
            withContext(Dispatchers.IO) { withTimeout(5_000) { oldJob.join() } }
            assertTrue(f.vm.uiState.value.isTogglingWatched)
            newer.complete(HttpStatusCode.ServiceUnavailable)
            f.state { !it.isTogglingWatched }
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun rejectedWatchedActionCannotSupersedePendingRatingRollbackOrCleanup() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val f = Fixture()
        try {
            f.vm.onSetRating(4)
            val rating = f.request()
            assertTrue(f.vm.uiState.value.isTogglingRating)
            f.vm.onToggleWatched() // The unresolved-item gate rejects this different field.
            f.state { !it.isTogglingWatched }
            rating.complete(HttpStatusCode.ServiceUnavailable)
            f.state { !it.isTogglingRating }
            assertNull(f.vm.uiState.value.userRating)
            assertEquals(1, f.port.pending.size)
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun clearRatingInvalidationReleasesOnlyItsOwnBusyState() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val f = Fixture()
        try {
            f.vm.onSetRating(4)
            f.request().complete(HttpStatusCode.NoContent)
            f.state { !it.isTogglingRating && it.userRating == 4 }
            f.vm.onClearRating()
            val oldJob = f.vm.viewModelScope.coroutineContext.job.children.last()
            val old = f.request()
            f.changeProfile()
            f.state { !it.isTogglingRating }
            f.vm.onSetRating(2)
            val newer = f.request()
            old.complete(HttpStatusCode.ServiceUnavailable)
            withContext(Dispatchers.IO) { withTimeout(5_000) { oldJob.join() } }
            assertTrue(f.vm.uiState.value.isTogglingRating)
            newer.complete(HttpStatusCode.ServiceUnavailable)
            f.state { !it.isTogglingRating }
        } finally { f.close(); Dispatchers.resetMain() }
    }
}
