package org.siloserver.silo.tv.ui.screens.profiles

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
import kotlin.test.assertNull

/**
 * silo-android#663 on TV: there is no form for a temporary password. The
 * picker says to change it on a phone or the web; that ends the TV's
 * session, so Retry then leads to sign-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvProfilePasswordChangeTest {
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class AnsweringRepository(
        private val answers: ArrayDeque<ApiResult<List<Profile>>>,
    ) : ProfileRepository(
        profileApi = ProfileApi(HttpClient(MockEngine { respond("{}") }), ApiV2Gate.Unrestricted),
        tokenManager = TokenManagerImpl(DefaultIdentityTransitionBarrier()),
        identityTransitions = DefaultIdentityTransitionBarrier(),
    ) {
        override suspend fun listProfiles(): ApiResult<List<Profile>> = answers.removeFirst()
    }

    @Test
    fun aTemporaryPasswordSaysWhereToChangeIt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val profile = Profile(id = "p1", name = "Member")
        val viewModel = TvProfileSelectionViewModel(
            AnsweringRepository(
                ArrayDeque(
                    listOf(
                        ApiResult.Error(403, PASSWORD_CHANGE_REQUIRED, "The account holds a temporary password."),
                        ApiResult.Success(listOf(profile)),
                    ),
                ),
            ),
            consumeSkipsSingleProfile = { false },
        )
        advanceUntilIdle()
        assertEquals(TV_PASSWORD_CHANGE_REQUIRED_MESSAGE, viewModel.uiState.value.error)

        // Retry once it changed: the grid, not the old refusal.
        viewModel.loadProfiles()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.error)
        assertEquals(listOf(profile), viewModel.uiState.value.profiles)
    }
}
