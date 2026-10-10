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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalDataRepositoryCacheTest {

    private class FakeCache : CatalogCachePort {
        var cachedLibraries: List<UserLibrary>? = null

        override suspend fun cacheLibraries(libraries: List<UserLibrary>) {
            cachedLibraries = libraries
        }

        override suspend fun getCachedLibraries(): List<UserLibrary>? = cachedLibraries
    }

    private fun librariesClient(vararg ids: Int) = HttpClient(
        MockEngine {
            val items = ids.joinToString(",") { """{"id":"$it","name":"Library $it","type":"movie","sort_order":0}""" }
            respond(
                """{"items":[$items]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) {
        install(ContentNegotiation) { json(SiloJson) }
    }

    private fun library(id: Int) = UserLibrary(id = id, name = "Library $id", type = "movie")

    @Test
    fun libraryListChangeIsReportedAgainstTheCachedList() = runTest {
        val cache = FakeCache().apply { cachedLibraries = listOf(library(1), library(2)) }
        val repository = PersonalDataRepository(PersonalDataApi(librariesClient(1)), catalogCache = cache)

        assertTrue(repository.libraryListChangedSinceCached())
        assertEquals(listOf(1), cache.cachedLibraries?.map { it.id })
        // The fresh list is now the baseline, so the same answer is no change.
        assertFalse(repository.libraryListChangedSinceCached())
    }

    @Test
    fun libraryListWithTheSameLibrariesInAnotherOrderIsNoChange() = runTest {
        val cache = FakeCache().apply { cachedLibraries = listOf(library(1), library(2)) }
        val repository = PersonalDataRepository(PersonalDataApi(librariesClient(2, 1)), catalogCache = cache)

        assertFalse(repository.libraryListChangedSinceCached())
    }

    @Test
    fun libraryListWithoutACachedListIsNoChange() = runTest {
        val repository = PersonalDataRepository(PersonalDataApi(librariesClient(1)), catalogCache = FakeCache())

        assertFalse(repository.libraryListChangedSinceCached())
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
                    """{"items":[{"id":"1","name":"Profile A","type":"movie","sort_order":0}]}""",
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

        // The old profile's list doesn't come back for the new profile either.
        assertEquals("identity_changed", (oldProfileRequest.await() as ApiResult.Error).error)
        assertEquals(null, cache.cachedLibraries)
    }
}
