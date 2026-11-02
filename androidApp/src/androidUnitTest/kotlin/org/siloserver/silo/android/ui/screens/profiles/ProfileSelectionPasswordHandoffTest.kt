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
import org.siloserver.silo.model.auth.PASSWORD_CHANGE_REQUIRED
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.ProfileRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * silo-android#663: the picker hands a temporary password over to "Choose a
 * new password". A picker shown again must hand off again (or show the grid
 * once the password changed), never wait on an answer it already consumed.
 * silo-apple#874 hung on "Loading profiles…" exactly there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSelectionPasswordHandoffTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val restricted: ApiResult<List<Profile>> =
        ApiResult.Error(403, PASSWORD_CHANGE_REQUIRED, "temporary password")

    private class AnsweringRepository(
        private val answers: ArrayDeque<ApiResult<List<Profile>>>,
    ) : ProfileRepository(
        profileApi = ProfileApi(HttpClient(MockEngine { respond("{}") }), ApiV2Gate.Unrestricted),
        tokenManager = TokenManagerImpl(DefaultIdentityTransitionBarrier()),
        identityTransitions = DefaultIdentityTransitionBarrier(),
    ) {
        override suspend fun listProfiles(): ApiResult<List<Profile>> = answers.removeFirst()
        override suspend fun getActiveProfileId(): String? = null
    }

    @Test
    fun aPickerShownAgainHandsOffAgainWithoutShowingTheRefusal() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var rearmed = 0
        val viewModel = ProfileSelectionViewModel(
            AnsweringRepository(ArrayDeque(listOf(restricted, restricted))),
            consumeSkipsSingleProfile = { true },
            rearmSkipsSingleProfile = { rearmed++ },
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.requiresPasswordChange)
        assertNull(viewModel.uiState.value.error, "the refusal never shows")
        assertTrue(viewModel.uiState.value.isLoading, "the picker keeps its loading state")
        assertEquals(1, rearmed, "the picker after the new password still skips a one-person household")

        viewModel.onPasswordChangeConsumed()
        assertFalse(viewModel.uiState.value.requiresPasswordChange)

        // Shown again (resume reloads): it asks the server again.
        viewModel.loadProfiles()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.requiresPasswordChange)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun aPickerShownAfterThePasswordChangedShowsTheGrid() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val profile = Profile(id = "p1", name = "Member")
        val viewModel = ProfileSelectionViewModel(
            AnsweringRepository(ArrayDeque(listOf(restricted, ApiResult.Success(listOf(profile))))),
            consumeSkipsSingleProfile = { false },
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.requiresPasswordChange)

        // Not consumed: the picker was covered when the answer came. Shown
        // again, the fresh load replaces it rather than handing off on it.
        viewModel.loadProfiles()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.requiresPasswordChange)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf(profile), viewModel.uiState.value.profiles)
    }
}
