package org.siloserver.silo.android.ui.screens.profiles

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.ProfileIdentity
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.repository.ProfileRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSelectionGridScopeTest {
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `failed reload under a new scope does not qualify the old grid as new`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ScopeTokenManager(barrier)
        val oldProfile = Profile(id = "old-profile", name = "Old profile")
        val repository = QueueProfileRepository(
            tokenManager = tokens,
            barrier = barrier,
            results = ArrayDeque(
                listOf(
                    ApiResult.Success(listOf(oldProfile)),
                    ApiResult.Error(500, "failed", "Reload failed"),
                    ApiResult.Error(500, "failed", "Reload still failed"),
                ),
            ),
        )
        val viewModel = ProfileSelectionViewModel(repository)
        advanceUntilIdle()

        // Establish the init load before changing identity. Otherwise the
        // Unconfined dispatcher can let the init response observe the switch
        // below and correctly reject what the test intended as its old grid.
        assertEquals(listOf(oldProfile), viewModel.uiState.value.profiles)

        barrier.changing(IdentityTransitionKind.SERVER_SWITCH) {
            tokens.serverId = "server-b"
        }
        viewModel.loadProfiles()
        advanceUntilIdle()

        // A failed reload leaves the previous grid visible, but must not move
        // that grid's scope to server-b.
        assertEquals(listOf(oldProfile), viewModel.uiState.value.profiles)

        viewModel.onProfileTapped(oldProfile)
        advanceUntilIdle()

        // The retained card is still qualified by server-a. Selecting it under
        // server-b therefore fails closed and drops the now-stale grid.
        assertEquals(emptyList(), viewModel.uiState.value.profiles)
        assertEquals(emptyList(), tokens.committedProfiles)
        assertNull(viewModel.uiState.value.selectedProfileId)
    }

    @Test
    fun `tap dispatched after a scope mismatch cannot select from the cleared grid`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val barrier = DefaultIdentityTransitionBarrier()
        val tokens = ScopeTokenManager(barrier)
        val oldProfile = Profile(id = "old-profile", name = "Old profile")
        val repository = QueueProfileRepository(
            tokenManager = tokens,
            barrier = barrier,
            results = ArrayDeque(
                listOf(
                    ApiResult.Success(listOf(oldProfile)),
                    ApiResult.Success(emptyList()),
                ),
            ),
            beforeResult = { call ->
                if (call == 2) {
                    barrier.changing(IdentityTransitionKind.SERVER_SWITCH) {
                        tokens.serverId = "server-b"
                    }
                }
            },
        )
        val viewModel = ProfileSelectionViewModel(repository)

        viewModel.loadProfiles()
        viewModel.onProfileTapped(oldProfile)

        assertEquals(emptyList(), viewModel.uiState.value.profiles)
        assertEquals(emptyList(), tokens.committedProfiles)
    }
}

private class QueueProfileRepository(
    tokenManager: TokenManager,
    barrier: DefaultIdentityTransitionBarrier,
    private val results: ArrayDeque<ApiResult<List<Profile>>>,
    private val beforeResult: suspend (Int) -> Unit = {},
) : ProfileRepository(
    profileApi = ProfileApi(HttpClient(MockEngine { respond("{}") }), ApiV2Gate.Unrestricted),
    tokenManager = tokenManager,
    identityTransitions = barrier,
) {
    private var calls = 0

    override suspend fun listProfiles(): ApiResult<List<Profile>> {
        beforeResult(++calls)
        return results.removeFirst()
    }

    override suspend fun getActiveProfileId(): String? = null
}

private class ScopeTokenManager(
    private val barrier: DefaultIdentityTransitionBarrier,
) : TokenManager by TokenManagerImpl(barrier) {
    var serverId: String = "server-a"
    val committedProfiles = mutableListOf<ProfileIdentity>()

    override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = AuthScopeSnapshot(
        serverId = serverId,
        profileId = null,
        serverUrl = "https://$serverId",
        profileToken = null,
        identityGeneration = barrier.generation.value,
    )

    override suspend fun getCurrentServerId(): String = serverId

    override suspend fun setProfileIdentity(profileId: String?, profileToken: String?) {
        committedProfiles += ProfileIdentity(profileId, profileToken)
    }
}
