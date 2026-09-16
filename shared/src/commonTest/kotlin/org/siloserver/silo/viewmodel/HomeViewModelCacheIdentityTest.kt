package org.siloserver.silo.viewmodel

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.domain.MediaActionsCoordinator
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.HomeSectionsV2Api
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.repository.port.HomeCachePort
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelCacheIdentityTest {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }


    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun homeResponseStartedBeforeProfileSwitchIsNotCachedForNewProfile() = runTest(dispatcher) {
        val requestEntered = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val client = HttpClient(
            MockEngine {
                requestEntered.complete(Unit)
                releaseResponse.await()
                respond(
                    """{"sections":[{"id":"old","section_type":"row","title":"Profile A","items":[]}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val cache = RecordingHomeCache()
        val viewModel = HomeViewModel(
            sectionRepository = SectionRepository(
                sectionApi = SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted)),
            ),
            mediaActions = mediaActions(),
            homeCache = cache,
            identityTransitions = identityTransitions,
        )

        requestEntered.await()
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) { owner = owner.copy(profileToken = "new") }
        releaseResponse.complete(Unit)
        viewModel.uiState.first { !it.isLoading }

        assertEquals(null, cache.sections)
    }

    @Test
    fun homeReportsCacheAndNetworkProvenanceWithoutContentMetadata() = runTest(dispatcher) {
        val client = HttpClient(
            MockEngine {
                respond(
                    """{"sections":[{"id":"private-section","section_type":"row","title":"Private Title","items":[]}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val observations = mutableListOf<HomeLoadObservation>()
        val viewModel = HomeViewModel(
            sectionRepository = SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted))),
            mediaActions = mediaActions(),
            diagnostics = HomeDiagnosticsObserver(observations::add),
        )

        viewModel.uiState.first { !it.isLoading }

        assertEquals(
            listOf(HomeLoadOutcome.MISS, HomeLoadOutcome.SUCCESS),
            observations.map(HomeLoadObservation::outcome),
        )
        assertEquals(listOf(HomeLoadSource.CACHE, HomeLoadSource.NETWORK), observations.map { it.source })
        assertTrue(observations.all { it.durationMs >= 0 })
        // The repository removes empty rows during hydration; diagnostics see
        // only the aggregate resolved count, never the private row metadata.
        assertEquals(listOf(0, 0), observations.map { it.sectionCount })
    }

    private class RecordingHomeCache : HomeCachePort {
        var sections: List<ResolvedSection>? = null

        override suspend fun cacheHomeV2(sections: List<ResolvedSection>, owner: AuthScopeSnapshot, stillCurrent: () -> Boolean) {
            this.sections = sections
        }
    }

    private fun mediaActions(): MediaActionsCoordinator {
        val client = HttpClient(MockEngine { error("Personal data network should not be used") }) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return MediaActionsCoordinator(PersonalDataRepository(PersonalDataApi(client)))
    }
}
