package org.siloserver.silo.tv.ui.screens.profiles

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TvProfileSelectionManageGateTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `regular account with a primary profile can manage the household on TV`() = runTest {
        val authRepo = createAuthRepository(
            userJson = """{"id":2,"username":"alice","email":"alice@example.com","role":"user"}""",
        )
        val profileRepo = createProfileRepository(
            listOf(Profile(id = "p1", name = "Alice", isPrimary = true), Profile(id = "p2", name = "Kid")),
        )
        val viewModel = TvProfileSelectionViewModel(
            profileRepository = profileRepo,
            authRepository = authRepo,
        )
        // The mock HTTP calls complete on Ktor's own dispatcher, not the test
        // scheduler, so advancing the test scheduler can return before the load lands.
        val loaded = viewModel.uiState.first { !it.isLoading }

        assertTrue(loaded.canManageProfiles)
        viewModel.toggleManageMode()
        assertTrue(viewModel.uiState.value.isManageMode)
    }

    @Test
    fun `failed account fetch still allows managing a household with a primary on TV`() = runTest {
        val authRepo = createAuthRepository(
            userJson = null,
            status = HttpStatusCode.InternalServerError,
        )
        val profileRepo = createProfileRepository(listOf(Profile(id = "p1", name = "Profile 1", isPrimary = true)))
        val viewModel = TvProfileSelectionViewModel(
            profileRepository = profileRepo,
            authRepository = authRepo,
        )
        val loaded = viewModel.uiState.first { !it.isLoading }

        assertTrue(loaded.canManageProfiles)
    }

    @Test
    fun `household without a primary profile cannot enter manage mode or request delete on TV`() = runTest {
        val authRepo = createAuthRepository(
            userJson = """{"id":1,"username":"admin","email":"admin@example.com","role":"admin"}""",
        )
        val profile = Profile(id = "p1", name = "Alice")
        val profileRepo = createProfileRepository(listOf(profile, Profile(id = "p2", name = "Bob")))
        val viewModel = TvProfileSelectionViewModel(
            profileRepository = profileRepo,
            authRepository = authRepo,
        )
        val loaded = viewModel.uiState.first { !it.isLoading }

        assertFalse(loaded.canManageProfiles)
        viewModel.toggleManageMode()
        assertFalse(viewModel.uiState.value.isManageMode)
        viewModel.requestDelete(profile)
        assertFalse(viewModel.uiState.value.deleteCandidate != null)
    }

    private fun createAuthRepository(
        userJson: String?,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): AuthRepository {
        val engine = MockEngine {
            if (userJson != null && status == HttpStatusCode.OK) {
                respond(
                    content = userJson,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    content = """{"error":"error","message":"failed"}""",
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val tokenManager = TokenManagerImpl(DefaultIdentityTransitionBarrier())
        return AuthRepository(
            authApi = AuthApi(client, ApiV2Gate.Unrestricted),
            tokenManager = tokenManager,
        )
    }

    private fun createProfileRepository(profiles: List<Profile>): ProfileRepository {
        val jsonString = buildString {
            append("""{"items":[""")
            append(profiles.joinToString(",") { """{"id":"${it.id}","name":"${it.name}","is_primary":${it.isPrimary},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}""" })
            append("""]}""")
        }
        val engine = MockEngine {
            respond(
                content = jsonString,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val tokenManager = TokenManagerImpl(DefaultIdentityTransitionBarrier())
        return ProfileRepository(
            profileApi = ProfileApi(client, ApiV2Gate.Unrestricted),
            tokenManager = tokenManager,
        )
    }
}
