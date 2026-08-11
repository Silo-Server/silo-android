package org.siloserver.silo.android.ui.screens.profiles

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.repository.ProfileRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Picker-time management gating. The server authorizes profile management
 * for admin accounts OR when the acting profile is the household primary —
 * and the phone keeps the acting profile across "Switch Profile", so both
 * arms matter here. The picker must not offer create/edit/delete to anyone
 * else — except the add tile on an empty grid, which the server exempts
 * (first-profile bootstrap).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSelectionManagementGatingTest {
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun user(role: String) = User(
        id = 1,
        username = "someone",
        email = "someone@example.com",
        role = role,
    )

    @Test
    fun `admin account gets management affordances`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = ProfileSelectionViewModel(
            profileRepository = FixedProfileRepository(listOf(Profile(id = "p1", name = "One"))),
            currentUserProvider = { user("admin") },
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canManageProfiles)
        assertTrue(viewModel.uiState.value.canAddProfile)
    }

    @Test
    fun `non-admin account with no acting profile gets no management affordances`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = ProfileSelectionViewModel(
            profileRepository = FixedProfileRepository(listOf(Profile(id = "p1", name = "One"))),
            currentUserProvider = { user("user") },
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canManageProfiles)
        assertFalse(viewModel.uiState.value.canAddProfile)
    }

    /**
     * The regression the first cut of this gating introduced: phone keeps the
     * acting profile across "Switch Profile", and the picker is the ONLY
     * route to create/edit — a non-admin household owner acting as the
     * primary is authorized by the server and must keep the affordances.
     */
    @Test
    fun `non-admin acting as the primary profile keeps management affordances`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = ProfileSelectionViewModel(
            profileRepository = FixedProfileRepository(
                profiles = listOf(
                    Profile(id = "owner", name = "Owner", isPrimary = true),
                    Profile(id = "kid", name = "Kid"),
                ),
                activeProfileId = "owner",
            ),
            currentUserProvider = { user("user") },
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canManageProfiles)
        assertTrue(viewModel.uiState.value.canAddProfile)
    }

    @Test
    fun `non-admin acting as a non-primary profile gets no management affordances`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = ProfileSelectionViewModel(
            profileRepository = FixedProfileRepository(
                profiles = listOf(
                    Profile(id = "owner", name = "Owner", isPrimary = true),
                    Profile(id = "kid", name = "Kid"),
                ),
                activeProfileId = "kid",
            ),
            currentUserProvider = { user("user") },
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canManageProfiles)
        assertFalse(viewModel.uiState.value.canAddProfile)
    }

    @Test
    fun `unresolved user fails closed`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = ProfileSelectionViewModel(
            profileRepository = FixedProfileRepository(listOf(Profile(id = "p1", name = "One"))),
            currentUserProvider = { null },
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canManageProfiles)
        assertFalse(viewModel.uiState.value.canAddProfile)
    }

    @Test
    fun `empty grid keeps the add tile for first-profile bootstrap`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = ProfileSelectionViewModel(
            profileRepository = FixedProfileRepository(emptyList()),
            currentUserProvider = { user("user") },
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canManageProfiles)
        assertTrue(viewModel.uiState.value.canAddProfile)
    }

    /** Bootstrap must survive a dead `/me`: fresh account, user unresolved. */
    @Test
    fun `empty grid with unresolved user still shows the add tile`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = ProfileSelectionViewModel(
            profileRepository = FixedProfileRepository(emptyList()),
            currentUserProvider = { null },
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canManageProfiles)
        assertTrue(viewModel.uiState.value.canAddProfile)
    }

    @Test
    fun `revoking the grant on reload leaves manage mode and closes the delete dialog`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var currentUser: User? = user("admin")
        val profile = Profile(id = "p1", name = "One")
        val viewModel = ProfileSelectionViewModel(
            profileRepository = FixedProfileRepository(listOf(profile)),
            currentUserProvider = { currentUser },
        )
        advanceUntilIdle()
        viewModel.toggleManageMode()
        viewModel.requestDeleteProfile(profile)
        assertTrue(viewModel.uiState.value.isManageMode)
        assertNotNull(viewModel.uiState.value.deleteDialogProfile)

        currentUser = null
        viewModel.loadProfiles()
        advanceUntilIdle()

        // Otherwise the "Done" toggle disappears while its mode stays on,
        // and the pending delete confirmation stays open and actionable.
        assertFalse(viewModel.uiState.value.canManageProfiles)
        assertFalse(viewModel.uiState.value.isManageMode)
        assertNull(viewModel.uiState.value.deleteDialogProfile)
    }
}

private class FixedProfileRepository(
    private val profiles: List<Profile>,
    private val activeProfileId: String? = null,
) : ProfileRepository(
    profileApi = ProfileApi(HttpClient(MockEngine { respond("{}") })),
    tokenManager = TokenManagerImpl(),
) {
    override suspend fun listProfiles(): ApiResult<List<Profile>> = ApiResult.Success(profiles)

    override suspend fun getActiveProfileId(): String? = activeProfileId
}
