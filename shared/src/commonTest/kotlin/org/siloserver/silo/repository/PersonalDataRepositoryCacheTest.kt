package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.repository.port.CatalogCachePort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonalDataRepositoryCacheTest {

    private class FakeCache : CatalogCachePort {
        var cachedLibraries: List<UserLibrary>? = null

        override suspend fun cacheLibraries(libraries: List<UserLibrary>) {
            cachedLibraries = libraries
        }
    }

    @Test
    fun librariesResponseStartedBeforeProfileSwitchIsNotCachedForNewProfile() = runTest {
        val requestEntered = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val client = HttpClient(
            MockEngine {
                requestEntered.complete(Unit)
                releaseResponse.await()
                respond(
                    """[{"id":1,"name":"Profile A","type":"movie"}]""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val cache = FakeCache()
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val repository = PersonalDataRepository(
            personalDataApi = PersonalDataApi(client),
            catalogCache = cache,
            identityTransitions = identityTransitions,
        )

        val oldProfileRequest = async { repository.listUserLibraries() }
        requestEntered.await()
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) { }
        releaseResponse.complete(Unit)

        assertTrue(oldProfileRequest.await() is ApiResult.Success)
        assertEquals(null, cache.cachedLibraries)
    }
}
