package org.siloserver.silo.tv.watchnext

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.network.apiv2.HomeSectionsV2Api
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.section.SECTION_ROW_MAX_ITEMS
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchNextHomeSyncTest {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private val gate = WatchNextWriteGate()
    private var fail = false
    private var failFallback = false
    private var empty = false
    private var reads = 0
    private var applies = 0
    private var hook: () -> Unit = {}
    private var episodeRows = false
    private var otherRow = false
    private val rowPaths = mutableListOf<String>()
    private var layoutBody: String? = null
    private val rowBodies = mutableMapOf<String, String>()
    private var failRow: String? = null
    private var rowHook: (String) -> Unit = {}
    private fun movieRow(id: String, type: String, ids: List<String>, totalCount: Int = ids.size) =
        "{\"id\":\"$id\",\"section_type\":\"$type\",\"title\":\"$id\",\"total_count\":$totalCount,\"items\":[" +
            ids.joinToString(",") { "{\"content_id\":\"movie:$it\",\"type\":\"movie\",\"title\":\"$it\",\"backdrop_url\":\"/image\"}" } + "]}"
    private suspend fun scenario(block: suspend (SectionRepository) -> Unit) {
        val client = HttpClient(MockEngine {
            assertEquals(owner, it.attributes[AuthScopeAttributeKey])
            val path = it.url.encodedPath
            assertNotEquals("/api/v2/home/sections", path, "Watch Next must not download every Home row")
            val rowRead = path.endsWith("/items")
            val type = if (episodeRows) "next_up" else "continue_watching"
            val rowId = path.removePrefix("/api/v2/home/sections/").removeSuffix("/items")
            val row = if (episodeRows) {
                """{"id":"row","section_type":"next_up","title":"Row","items":[{"content_id":"episode:a","type":"episode","title":"A","series_id":"series","backdrop_url":"/still-a","backdrop_is_episode_still":true},{"content_id":"episode:b","type":"episode","title":"B","series_id":"series","backdrop_url":"/still-b","backdrop_is_episode_still":true}]}"""
            } else """{"id":"row","section_type":"continue_watching","title":"Row","items":[{"content_id":"movie:a","type":"movie","title":"A","backdrop_url":"/image"}]}"""
            val body = if (rowRead) {
                reads++; rowPaths += path; rowHook(rowId)
                rowBodies[rowId] ?: row
            } else {
                assertEquals("/api/v2/home/layout", path); hook()
                val other = if (otherRow) """,{"id":"other","section_type":"recently_added","title":"Other"}""" else ""
                if (layoutBody != null) layoutBody!!
                else if (empty) """{"sections":[]}"""
                else """{"sections":[{"id":"row","section_type":"$type","title":"Row"}$other]}"""
            }
            respond(body, if (fail || (rowRead && (failFallback || rowId == failRow))) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { block(SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted)))) } finally { client.close() }
    }
    private suspend fun sync(repo: SectionRepository) = syncWatchNextHome(repo, gate, { false }) { fields, run, authority ->
        gate.write(run, authority) { applies++; if (empty) assertTrue(fields.isEmpty()) else {
            assertEquals("continue_watching:movie:a", fields.single().externalId)
            assertEquals("silo://play/movie:a?type=movie", fields.single().intentUri)
        } }
    }
    @Test fun protectedEpisodesFetchSeriesArtOnceAndReplaceBothTiles() = runTest {
        episodeRows = true
        var artworkReads = 0
        scenario { repo ->
            assertTrue(syncWatchNextHome(repo, gate, { false },
                spoilerPreferences = { EpisodeSpoilerPrefs(hideImages = true) },
                seriesArtwork = { id ->
                    assertEquals("series", id); artworkReads++
                    ItemDetail(contentId = id, type = "series", title = "Series", backdropUrl = "/safe")
                },
            ) { fields, run, authority ->
                gate.write(run, authority) {
                    applies++
                    assertEquals(listOf("/safe", "/safe"), fields.map { it.posterArtUri })
                }
            })
        }
        assertEquals(1, artworkReads)
        assertEquals(1, applies)
    }

    @Test fun failedSeriesLookupRetriesInsteadOfDroppingProtectedTiles() = runTest {
        episodeRows = true
        scenario { repo ->
            assertFalse(syncWatchNextHome(repo, gate, { false },
                spoilerPreferences = { EpisodeSpoilerPrefs(hideImages = true) },
                seriesArtwork = { null },
            ) { _, _, _ -> applies++ })
        }
        assertEquals(0, applies)
    }

    @Test fun aCastIdentityInstalledMidRunNeverWritesTheLauncher() = runTest {
        scenario { repo ->
            var borrowed = false
            hook = { borrowed = true }
            assertTrue(syncWatchNextHome(repo, gate, { false }, borrowedIdentity = { borrowed }) { _, run, authority ->
                gate.write(run, authority) { applies++ }
            })
            assertEquals(0, applies)
        }
    }

    @Test fun unknownPreferencesRetryWithoutPublishingAndChangedPreferencesFenceWrites() = runTest {
        scenario { repo ->
            assertFalse(syncWatchNextHome(repo, gate, { false }, spoilerPreferences = { null }) { _, _, _ -> applies++ })
            assertEquals(0, applies)
            var current = true
            hook = { current = false }
            assertTrue(syncWatchNextHome(repo, gate, { false }, preferencesCurrent = { current }) { _, _, _ -> applies++ })
            assertEquals(0, applies)
        }
    }
    @Test fun completeAndEmptySuccessMayReconcileButFailuresCannot() = runTest { scenario { repo ->
        assertTrue(sync(repo)); assertEquals(1, applies); assertEquals(1, reads)
        fail = true; assertFalse(sync(repo)); assertEquals(1, applies); assertEquals(1, reads)
        fail = false; empty = true; assertTrue(sync(repo)); assertEquals(2, applies); assertEquals(1, reads)
    } }
    @Test fun readsOnlyWatchNextRowsAndFailedRowReadsPreserveTiles() = runTest { scenario { repo ->
        otherRow = true; assertTrue(sync(repo)); assertEquals(listOf("/api/v2/home/sections/row/items"), rowPaths); assertEquals(1, applies)
        failFallback = true; assertFalse(sync(repo)); assertEquals(1, applies)
    } }
    @Test fun aRowReadKeepsTheCardsAHomeRowDraws() = runTest { scenario { repo ->
        rowBodies["row"] = movieRow("row", "continue_watching", (1..25).map { "m$it" })
        assertTrue(syncWatchNextHome(repo, gate, { false }) { fields, run, authority -> gate.write(run, authority) {
            applies++
            assertEquals((1..SECTION_ROW_MAX_ITEMS).map { "continue_watching:movie:m$it" }, fields.map { it.externalId })
        } })
        assertEquals(1, applies)
    } }
    @Test fun anEmptyRowStillReconcilesTheOtherRows() = runTest { scenario { repo ->
        // Hide-watched filtering can empty Next Up while its total_count stays above zero.
        layoutBody = """{"sections":[{"id":"row","section_type":"continue_watching","title":"Row"},{"id":"next","section_type":"next_up","title":"Next"}]}"""
        rowBodies["next"] = movieRow("next", "next_up", emptyList(), totalCount = 3)
        assertTrue(sync(repo)); assertEquals(1, applies)
        assertEquals(listOf("/api/v2/home/sections/row/items", "/api/v2/home/sections/next/items"), rowPaths)
    } }
    @Test fun aLaterRowFailureKeepsEveryTile() = runTest { scenario { repo ->
        layoutBody = """{"sections":[{"id":"row","section_type":"continue_watching","title":"Row"},{"id":"next","section_type":"next_up","title":"Next"}]}"""
        rowBodies["next"] = movieRow("next", "next_up", emptyList())
        failRow = "next"
        assertFalse(sync(repo)); assertEquals(0, applies); assertEquals(2, reads)
    } }
    @Test fun aProfileChangeDuringARowReadNeverWrites() = runTest { scenario { repo ->
        rowHook = { owner = owner.copy(profileToken = "replacement") }
        assertTrue(sync(repo)); assertEquals(0, applies)
    } }
    @Test fun aMalformedLayoutRetriesWithoutReadingRows() = runTest { scenario { repo ->
        layoutBody = """{"sections":[{"id":"","section_type":"continue_watching","title":"Row"}]}"""
        assertFalse(sync(repo)); assertEquals(0, applies); assertEquals(0, reads)
    } }
    @Test fun originalPinAndClearGenerationFenceLateResponse() = runTest { scenario { repo ->
        hook = { owner = owner.copy(profileToken = "replacement") }; assertTrue(sync(repo)); assertEquals(0, applies)
        hook = { gate.invalidate() }; assertTrue(sync(repo)); assertEquals(0, applies)
    } }
    @Test fun clearWaitsForDispatchedWriteAndRejectsOldWaitingWriter() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val run = gate.capture()
        val first = launch { gate.write(run, { true }) { entered.complete(Unit); release.await(); events += "write" } }
        entered.await()
        val old = launch { gate.write(run, { true }) { events += "stale" } }
        runCurrent(); gate.invalidate()
        val clear = launch { gate.clear { events += "clear" } }
        runCurrent(); assertTrue(events.isEmpty())
        release.complete(Unit); first.join(); old.join(); clear.join()
        assertEquals(listOf("write", "clear"), events)
        gate.write(gate.capture(), { true }) { events += "new" }
        assertEquals(listOf("write", "clear", "new"), events)
    }
    @Test fun invalidationDuringSuspendedAuthorityCheckRefusesWrite() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val run = gate.capture()
        var writes = 0
        val job = launch { gate.write(run, { entered.complete(Unit); release.await(); true }) { writes++ } }
        entered.await(); gate.invalidate(); release.complete(Unit); job.join()
        assertEquals(0, writes)
        assertNull(gate.write(gate.capture(), { false }) { writes++ })
    }
}
