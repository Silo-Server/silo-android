package org.siloserver.silo.playback

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.shuffle.ShuffleScope
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.ShufflesV2Api
import org.siloserver.silo.network.apiv2.shuffleJson
import kotlin.test.*

class ShufflePlaybackTest {
    private val owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = owner
    }

    private val episodeCard =
        """{"content_id":"ep-1","type":"episode","title":"Pilot","series_title":"Show","season_number":0,"episode_number":2,"poster_url":"/still.jpg","backdrop_url":"/backdrop.jpg","runtime":42,"overview":"O"}"""
    private val movieCard =
        """{"content_id":"mv-2","type":"movie","title":"Film","series_title":"Ignored","poster_url":"/poster.jpg","backdrop_url":"/backdrop.jpg","runtime":95}"""

    /** A session against a fake server whose next answer each test sets. */
    private class FakeServer {
        var status: HttpStatusCode = HttpStatusCode.OK
        var body: String = shuffleJson()
        val requests = mutableListOf<String>()
    }

    private fun session(server: FakeServer): Pair<ShufflePlayback, HttpClient> {
        val client = HttpClient(MockEngine {
            server.requests += "${it.method.value} ${it.url.encodedPath} ${(it.body as? TextContent)?.text.orEmpty()}".trim()
            if (server.status.isSuccess()) {
                respond(server.body, server.status, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(
                    """{"type":"https://siloserver.org/docs/api/v2/problems/x","title":"T","status":${server.status.value},"detail":"d"}""",
                    server.status,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            }
        }) { install(ContentNegotiation) { json(SiloJson) } }
        return ShufflePlayback(ShufflesV2Api(client, tokens, ApiV2Gate.Unrestricted), "sh1") to client
    }

    @Test fun scopeLabelNamesASeasonUnderItsSeries() {
        assertEquals("Movies", ShuffleScope("library", "1", "Movies").label())
        assertEquals("Breaking Bad · Season 2", ShuffleScope("season", "s", "Season 2", "Breaking Bad").label())
        assertEquals("Specials", ShuffleScope("season", "s", "Specials", " ").label())
    }

    @Test fun nextPickShowsAnEpisodeWithItsStillAndAMovieWithItsTitleAndBackdrop() = runTest {
        val server = FakeServer()
        val (playback, client) = session(server)
        try {
            server.body = shuffleJson(current = movieCard, next = episodeCard)
            assertIs<ApiResult.Success<*>>(playback.refresh())
            val episode = assertNotNull(playback.nextPickAfter("mv-2"))
            assertTrue(episode.isEpisode)
            assertEquals("Show", episode.seriesTitle)
            // Specials are season 0 and still show their numbers.
            assertEquals(0, episode.seasonNumber)
            assertEquals(2, episode.episodeNumber)
            assertEquals("http://localhost/still.jpg", episode.stillUrl)
            assertEquals(42, episode.runtimeMinutes)

            server.body = shuffleJson(current = episodeCard, next = movieCard)
            playback.refresh()
            val movie = assertNotNull(playback.nextPickAfter("ep-1"))
            assertFalse(movie.isEpisode)
            assertEquals("Film", movie.title)
            assertNull(movie.seriesTitle)
            assertEquals(0, movie.seasonNumber)
            assertEquals(0, movie.episodeNumber)
            assertEquals("http://localhost/backdrop.jpg", movie.stillUrl)
            assertEquals("Show · Season 1", playback.scopeLabel)
        } finally {
            client.close()
        }
    }

    @Test fun aScopeWithOnePlayableItemIsFinished() = runTest {
        val server = FakeServer().apply { body = shuffleJson(current = movieCard, next = movieCard) }
        val (playback, client) = session(server)
        try {
            playback.refresh()
            assertNull(playback.nextPickAfter("mv-2"))
        } finally {
            client.close()
        }
    }

    @Test fun conflictMeansFinishedButOtherFailuresKeepTheLastPick() = runTest {
        val server = FakeServer()
        val (playback, client) = session(server)
        try {
            playback.refresh()
            assertEquals("mv-2", playback.nextPickAfter("ep-1")?.contentId)

            server.status = HttpStatusCode.InternalServerError
            assertIs<ApiResult.Error>(playback.refresh())
            assertFalse(playback.exhausted)
            assertEquals("mv-2", playback.nextPickAfter("ep-1")?.contentId)

            server.status = HttpStatusCode.Conflict
            assertEquals(409, assertIs<ApiResult.Error>(playback.refresh()).code)
            assertTrue(playback.exhausted)
            assertNull(playback.nextPickAfter("ep-1"))

            // A later successful read lifts the finished state.
            server.status = HttpStatusCode.OK
            playback.refresh()
            assertFalse(playback.exhausted)
            assertEquals("mv-2", playback.nextPickAfter("ep-1")?.contentId)
        } finally {
            client.close()
        }
    }

    @Test fun aReadThatPickAnotherOvertookCannotPutBackTheReplacedPick() = runTest {
        val readGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        val client = HttpClient(MockEngine {
            val skipped = it.url.encodedPath.endsWith("/skip")
            if (!skipped && reads++ == 1) readGate.await()
            val next = if (skipped) """{"content_id":"mv-3","type":"movie","title":"Other"}""" else movieCard
            respond(shuffleJson(current = episodeCard, next = next), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val playback = ShufflePlayback(ShufflesV2Api(client, tokens, ApiV2Gate.Unrestricted), "sh1")
            playback.refresh()
            val slowRead = async { playback.refresh() }
            kotlinx.coroutines.yield()
            playback.pickAnother()
            assertEquals("mv-3", playback.nextPickAfter("ep-1")?.contentId)
            readGate.complete(Unit)
            assertIs<ApiResult.Success<*>>(slowRead.await())
            assertEquals("mv-3", playback.nextPickAfter("ep-1")?.contentId)
        } finally {
            client.close()
        }
    }

    @Test fun aReadStartedDuringPickAnotherCannotPutBackTheReplacedPick() = runTest {
        val skipGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val readGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        val client = HttpClient(MockEngine {
            val skipped = it.url.encodedPath.endsWith("/skip")
            if (skipped) skipGate.await() else if (reads++ == 1) readGate.await()
            val next = if (skipped) """{"content_id":"mv-3","type":"movie","title":"Other"}""" else movieCard
            respond(shuffleJson(current = episodeCard, next = next), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val playback = ShufflePlayback(ShufflesV2Api(client, tokens, ApiV2Gate.Unrestricted), "sh1")
            playback.refresh()
            val skip = async { playback.pickAnother() }
            kotlinx.coroutines.yield()
            // The read starts while the skip waits for the server, which
            // answers it with the pick the skip is replacing.
            val slowRead = async { playback.refresh() }
            kotlinx.coroutines.yield()
            skipGate.complete(Unit)
            skip.await()
            assertEquals("mv-3", playback.nextPickAfter("ep-1")?.contentId)
            readGate.complete(Unit)
            assertIs<ApiResult.Success<*>>(slowRead.await())
            assertEquals("mv-3", playback.nextPickAfter("ep-1")?.contentId)
        } finally {
            client.close()
        }
    }

    @Test fun pickAnotherSkipsTheAnnouncedPickAndAdvanceNamesTheItemThatPlayed() = runTest {
        val server = FakeServer()
        val (playback, client) = session(server)
        try {
            // Nothing announced yet: Pick Another has nothing to replace.
            assertIs<ApiResult.Error>(playback.pickAnother())
            assertTrue(server.requests.isEmpty())

            playback.refresh()
            server.body = shuffleJson(next = """{"content_id":"mv-3","type":"movie","title":"Other"}""")
            assertIs<ApiResult.Success<*>>(playback.pickAnother())
            assertEquals("mv-3", playback.nextPickAfter("ep-1")?.contentId)

            server.body = shuffleJson(current = """{"content_id":"mv-3","type":"movie","title":"Other"}""", next = episodeCard)
            val advanced = assertIs<ApiResult.Success<org.siloserver.silo.model.shuffle.Shuffle>>(playback.advance("ep-1")).data
            assertEquals("mv-3", advanced.current.contentId)
            assertEquals("ep-1", playback.nextPickAfter("mv-3")?.contentId)

            assertEquals(
                listOf(
                    "GET /api/v2/shuffles/sh1",
                    """POST /api/v2/shuffles/sh1/skip {"next_content_id":"mv-2"}""",
                    """POST /api/v2/shuffles/sh1/advance {"from_content_id":"ep-1"}""",
                ),
                server.requests,
            )
        } finally {
            client.close()
        }
    }
}
