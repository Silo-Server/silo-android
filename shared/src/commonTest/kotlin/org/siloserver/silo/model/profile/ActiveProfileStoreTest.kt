package org.siloserver.silo.model.profile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.ProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val noOpClient = HttpClient(MockEngine { _ ->
    respond(content = "{}", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
})

private class FakeProfileRepository(
    var activeId: String? = "p1",
    var result: ApiResult<List<Profile>> = ApiResult.Success(
        listOf(Profile(id = "p1", name = "Nova", avatar = "preset:a", avatarUrl = "https://cdn/a.png")),
    ),
) : ProfileRepository(
    profileApi = ProfileApi(noOpClient, ApiV2Gate.Unrestricted),
    tokenManager = TokenManagerImpl(),
) {
    var listCalls = 0
    override suspend fun getActiveProfileId(): String? = activeId
    override suspend fun listProfiles(): ApiResult<List<Profile>> {
        listCalls++
        return result
    }
}

class ActiveProfileStoreTest {

    @Test
    fun fetchesOnceAndServesTheCachedProfileAfterwards() = runTest {
        val repo = FakeProfileRepository()
        val store = ActiveProfileStore(repo)

        store.refresh()
        assertEquals("Nova", store.activeProfile.value?.name)
        assertEquals(1, repo.listCalls)

        // A second reader — a header on another page — must not re-fetch.
        store.refresh()
        store.refresh()
        assertEquals(1, repo.listCalls)
    }

    @Test
    fun forceReReadsAfterTheProfileIsEdited() = runTest {
        val repo = FakeProfileRepository()
        val store = ActiveProfileStore(repo)
        store.refresh()

        repo.result = ApiResult.Success(
            listOf(Profile(id = "p1", name = "Nova Renamed", avatar = "preset:b")),
        )
        store.refresh(force = true)

        assertEquals("Nova Renamed", store.activeProfile.value?.name)
        assertEquals(2, repo.listCalls)
    }

    @Test
    fun aFailedRefreshKeepsTheCachedProfile() = runTest {
        val repo = FakeProfileRepository()
        val store = ActiveProfileStore(repo)
        store.refresh()

        // The regression this guards: blanking on error sent the header back
        // to the generic person glyph on a flaky connection.
        repo.result = ApiResult.NetworkError(RuntimeException("offline"))
        store.refresh(force = true)

        assertEquals("Nova", store.activeProfile.value?.name)
    }

    @Test
    fun resetClearsSoNoProfileLeaksAcrossAccounts() = runTest {
        val repo = FakeProfileRepository()
        val store = ActiveProfileStore(repo)
        store.refresh()

        store.reset()
        assertNull(store.activeProfile.value)

        // And the next refresh really re-reads rather than trusting the cache.
        store.refresh()
        assertEquals(2, repo.listCalls)
    }

    @Test
    fun aFailedRefreshForAnotherProfileDoesNotKeepThePreviousOne() = runTest {
        val repo = FakeProfileRepository()
        val store = ActiveProfileStore(repo)
        store.refresh()

        // Session expiry routes to Login without resetting this singleton, so
        // the next account can arrive with the previous profile still cached.
        // Showing its name and avatar in the header is the regression.
        repo.activeId = "p2"
        repo.result = ApiResult.NetworkError(RuntimeException("offline"))
        store.refresh()

        assertNull(store.activeProfile.value)
    }

    @Test
    fun signedOutStateClearsTheProfile() = runTest {
        val repo = FakeProfileRepository()
        val store = ActiveProfileStore(repo)
        store.refresh()

        repo.activeId = null
        store.refresh()

        assertNull(store.activeProfile.value)
    }
}
