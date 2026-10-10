package org.siloserver.silo.model.feature

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import org.siloserver.silo.model.shuffle.ShuffleScopeKind
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.ShufflesV2Api
import org.siloserver.silo.network.apiv2.shuffleJson
import kotlin.test.*

class ShuffleFeatureStoreTest {
    private val owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = owner
    }

    private var answer: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "{}" }
    private var offline = false
    private val client = HttpClient(MockEngine {
        if (offline) throw IOException("offline")
        val (status, body) = answer()
        if (status.isSuccess()) {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respondError(status, """{"type":"https://siloserver.org/docs/api/v2/problems/x","title":"T","status":${status.value}}""",
                headersOf(HttpHeaders.ContentType, "application/problem+json"))
        }
    }) { install(ContentNegotiation) { json(SiloJson) } }
    private val store = ShuffleFeatureStore(ShufflesV2Api(client, tokens, ApiV2Gate.Unrestricted))

    @AfterTest fun close() = client.close()

    @Test fun capabilityStartsHiddenSurvivesTransientFailuresAndResets() = runTest {
        assertFalse(store.capability.value.supports(ShuffleScopeKind.LIBRARY))
        answer = { HttpStatusCode.OK to """{"state":"available","allowed":true,"revision":"r","scope_kinds":["library"]}""" }
        store.refresh()
        assertTrue(store.capability.value.supports(ShuffleScopeKind.LIBRARY))

        offline = true
        store.refresh()
        assertTrue(store.capability.value.supports(ShuffleScopeKind.LIBRARY))
        offline = false

        answer = { HttpStatusCode.InternalServerError to "" }
        store.refresh()
        assertTrue(store.capability.value.supports(ShuffleScopeKind.LIBRARY))

        // A server without shuffles answers 404.
        answer = { HttpStatusCode.NotFound to "" }
        store.refresh()
        assertFalse(store.capability.value.supports(ShuffleScopeKind.LIBRARY))

        answer = { HttpStatusCode.OK to """{"state":"available","allowed":true,"revision":"r","scope_kinds":["library"]}""" }
        store.refresh()
        store.reset()
        assertFalse(store.capability.value.supports(ShuffleScopeKind.LIBRARY))
    }

    @Test fun startSaysWhenNothingCanPlay() = runTest {
        answer = { HttpStatusCode.Created to shuffleJson() }
        assertEquals("sh1", assertIs<ShuffleStart.Started>(store.start(ShuffleScopeKind.SERIES, "show")).shuffle.id)
        // The player of that shuffle takes the started pick once.
        assertNull(store.takeStarted("other"))
        assertEquals("mv-2", store.playback("sh1").nextPickAfter("ep-1")?.contentId)
        assertNull(store.takeStarted("sh1"))
        answer = { HttpStatusCode.Conflict to "" }
        assertEquals(ShuffleFeatureStore.NOTHING_PLAYABLE, assertIs<ShuffleStart.Failed>(store.start(ShuffleScopeKind.SERIES, "show")).message)
        answer = { HttpStatusCode.NotFound to "" }
        assertEquals(ShuffleFeatureStore.START_FAILED, assertIs<ShuffleStart.Failed>(store.start(ShuffleScopeKind.SERIES, "show")).message)
        offline = true
        assertEquals(ShuffleFeatureStore.START_FAILED, assertIs<ShuffleStart.Failed>(store.start(ShuffleScopeKind.SERIES, "show")).message)
    }

    @Test fun seasonsShuffleWithTwoPlayableEpisodesAndOnlyVideoLibrariesShuffle() {
        fun episode(n: Int, playable: Boolean) = org.siloserver.silo.model.catalog.EpisodeListItem(
            contentId = "e$n", seasonNumber = 1, episodeNumber = n,
            files = if (playable) listOf(org.siloserver.silo.model.catalog.EpisodeFile(fileId = n)) else emptyList(),
        )
        assertFalse(org.siloserver.silo.model.shuffle.canShuffleSeason(listOf(episode(1, true), episode(2, false))))
        assertTrue(org.siloserver.silo.model.shuffle.canShuffleSeason(listOf(episode(1, true), episode(2, true))))
        assertTrue(org.siloserver.silo.model.shuffle.isShuffleLibraryType("movies"))
        assertTrue(org.siloserver.silo.model.shuffle.isShuffleLibraryType("Mixed"))
        assertTrue(org.siloserver.silo.model.shuffle.isShuffleLibraryType("series"))
        assertFalse(org.siloserver.silo.model.shuffle.isShuffleLibraryType("audiobooks"))
        assertFalse(org.siloserver.silo.model.shuffle.isShuffleLibraryType(null))
    }
}
