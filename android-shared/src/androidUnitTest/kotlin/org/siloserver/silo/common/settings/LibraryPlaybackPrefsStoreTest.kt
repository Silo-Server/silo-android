package org.siloserver.silo.common.settings

import org.siloserver.silo.network.apiv2.ApiV2Gate

import org.siloserver.silo.model.settings.LibraryPlaybackPref
import org.siloserver.silo.model.settings.LibraryPlaybackPrefRequest
import org.siloserver.silo.model.settings.LibraryPlaybackPrefsResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.LibraryPlaybackPrefsApi
import org.siloserver.silo.repository.LibraryPlaybackPrefsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPlaybackPrefsStoreTest {

    @Test
    fun `hydrateIfNeeded populates flow on first call`() = runTest {
        val api = FakeLibraryPlaybackPrefsApi(
            initial = listOf(prefFor(libraryId = 1, audio = "en")),
        )
        val store = DefaultLibraryPlaybackPrefsStore(LibraryPlaybackPrefsRepository(api))

        store.hydrateIfNeeded()

        assertEquals(1, store.libraryPrefs.value.size)
        assertEquals("en", store.libraryPrefs.value[1]?.audioLanguage)
        assertEquals(1, api.listCalls)
    }

    @Test
    fun `hydrateIfNeeded is idempotent within a session`() = runTest {
        val api = FakeLibraryPlaybackPrefsApi(initial = emptyList())
        val store = DefaultLibraryPlaybackPrefsStore(LibraryPlaybackPrefsRepository(api))

        store.hydrateIfNeeded()
        store.hydrateIfNeeded()
        store.hydrateIfNeeded()

        assertEquals(1, api.listCalls, "list endpoint should only be hit once")
    }

    @Test
    fun `setPref puts then refreshes`() = runTest {
        val api = FakeLibraryPlaybackPrefsApi(initial = emptyList())
        val store = DefaultLibraryPlaybackPrefsStore(LibraryPlaybackPrefsRepository(api))

        // After PUT, list returns the new row.
        api.queueUpcomingList(listOf(prefFor(libraryId = 7, sub = "fr")))

        val result = store.setPref(7, audioLanguage = null, subtitleLanguage = "fr", subtitleMode = "always", showForcedSubtitles = false)

        assertTrue(result is ApiResult.Success)
        assertEquals(1, api.setCalls.size)
        assertEquals(7, api.setCalls.first().libraryId)
        assertEquals("fr", api.setCalls.first().request.subtitleLanguage)
        assertEquals("fr", store.libraryPrefs.value[7]?.subtitleLanguage)
    }

    @Test
    fun `deletePref deletes then refreshes`() = runTest {
        val api = FakeLibraryPlaybackPrefsApi(initial = listOf(prefFor(libraryId = 3, audio = "es")))
        val store = DefaultLibraryPlaybackPrefsStore(LibraryPlaybackPrefsRepository(api))
        store.hydrateIfNeeded()
        assertTrue(store.libraryPrefs.value.containsKey(3))

        api.queueUpcomingList(emptyList())
        store.deletePref(3)

        assertEquals(1, api.deleteCalls.size)
        assertEquals(3, api.deleteCalls.first())
        assertNull(store.libraryPrefs.value[3])
    }

    @Test
    fun `clear empties state and re-hydrates on next hydrateIfNeeded`() = runTest {
        val api = FakeLibraryPlaybackPrefsApi(initial = listOf(prefFor(libraryId = 1)))
        val store = DefaultLibraryPlaybackPrefsStore(LibraryPlaybackPrefsRepository(api))
        store.hydrateIfNeeded()
        assertEquals(1, store.libraryPrefs.value.size)

        store.clear()
        assertEquals(0, store.libraryPrefs.value.size)

        api.queueUpcomingList(listOf(prefFor(libraryId = 99)))
        store.hydrateIfNeeded()
        assertEquals(99, store.libraryPrefs.value.keys.first())
    }

    @Test
    fun `error from list propagates to lastError without throwing`() = runTest {
        val api = FakeLibraryPlaybackPrefsApi(initial = emptyList(), failNextWith = "boom")
        val store = DefaultLibraryPlaybackPrefsStore(LibraryPlaybackPrefsRepository(api))

        store.refresh()

        assertEquals(0, store.libraryPrefs.value.size)
        assertEquals("boom", store.lastError.value)
    }

    @Test
    fun `clear fences an in-flight list publication`() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reply = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = HttpClient()
        val api = object : LibraryPlaybackPrefsApi(org.siloserver.silo.network.apiv2.SettingsV2Api(client, org.siloserver.silo.network.TokenManagerImpl(), ApiV2Gate.Unrestricted)) {
            override suspend fun list(): ApiResult<LibraryPlaybackPrefsResponse> {
                started.complete(Unit)
                reply.await()
                return ApiResult.Success(LibraryPlaybackPrefsResponse(listOf(prefFor(1))))
            }
        }
        try {
            val store = DefaultLibraryPlaybackPrefsStore(LibraryPlaybackPrefsRepository(api))
            val refresh = async { store.refresh() }
            started.await()
            store.clear()
            reply.complete(Unit)
            refresh.await()
            assertTrue(store.libraryPrefs.value.isEmpty())
            assertNull(store.lastError.value)
        } finally { client.close() }
    }

    private fun prefFor(libraryId: Int, audio: String? = null, sub: String? = null) =
        LibraryPlaybackPref(
            profileId = "p",
            libraryId = libraryId,
            audioLanguage = audio,
            subtitleLanguage = sub,
        )
}

private class FakeLibraryPlaybackPrefsApi(
    initial: List<LibraryPlaybackPref>,
    private var failNextWith: String? = null,
) : LibraryPlaybackPrefsApi(org.siloserver.silo.network.apiv2.SettingsV2Api(HttpClient(), org.siloserver.silo.network.TokenManagerImpl(), org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted)) {
    data class SetCall(val libraryId: Int, val request: LibraryPlaybackPrefRequest)

    private var currentList: List<LibraryPlaybackPref> = initial
    var listCalls: Int = 0
    val setCalls = mutableListOf<SetCall>()
    val deleteCalls = mutableListOf<Int>()

    /** Replace what the next (and subsequent) list() calls return. */
    fun queueUpcomingList(prefs: List<LibraryPlaybackPref>) {
        currentList = prefs
    }

    override suspend fun list(): ApiResult<LibraryPlaybackPrefsResponse> {
        listCalls++
        failNextWith?.let { msg ->
            failNextWith = null
            return ApiResult.Error(500, "internal", msg)
        }
        return ApiResult.Success(LibraryPlaybackPrefsResponse(currentList))
    }

    override suspend fun set(
        libraryId: Int,
        request: LibraryPlaybackPrefRequest,
    ): ApiResult<Unit> {
        setCalls.add(SetCall(libraryId, request))
        return ApiResult.Success(Unit)
    }

    override suspend fun delete(libraryId: Int): ApiResult<Unit> {
        deleteCalls.add(libraryId)
        return ApiResult.Success(Unit)
    }
}
