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
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchNextHomeSyncTest {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private val gate = WatchNextWriteGate()
    private var unresolved = false
    private var fail = false
    private var failFallback = false
    private var empty = false
    private var reads = 0
    private var applies = 0
    private var hook: () -> Unit = {}
    private suspend fun scenario(block: suspend (SectionRepository) -> Unit) {
        val client = HttpClient(MockEngine {
            assertEquals(owner, it.attributes[AuthScopeAttributeKey])
            val fallback = it.url.encodedPath.endsWith("/items")
            val row = """{"id":"row","section_type":"continue_watching","title":"Row","items":[{"content_id":"movie:a","type":"movie","title":"A","backdrop_url":"/image"}]}"""
            val body = if (fallback) { reads++; row } else {
                assertEquals("/api/v2/home/sections", it.url.encodedPath); hook()
                if (empty) """{"sections":[]}"""
                else if (unresolved) """{"sections":[{"id":"row","section_type":"continue_watching","title":"Row","total_count":1,"items":[]}]}"""
                else """{"sections":[$row]}"""
            }
            respond(body, if (fail || (fallback && failFallback)) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { block(SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted)))) } finally { client.close() }
    }
    private suspend fun sync(repo: SectionRepository) = syncWatchNextHome(repo, gate, { false }) { fields, run, authority ->
        gate.write(run, authority) { applies++; if (empty) assertTrue(fields.isEmpty()) else {
            assertEquals("continue_watching:movie:a", fields.single().externalId)
            assertEquals("silo://play/movie:a?type=movie", fields.single().intentUri)
        } }
    }
    @Test fun completeAndEmptySuccessMayReconcileButFailuresCannot() = runTest { scenario { repo ->
        assertTrue(sync(repo)); assertEquals(1, applies); assertEquals(0, reads)
        fail = true; assertFalse(sync(repo)); assertEquals(1, applies)
        fail = false; empty = true; assertTrue(sync(repo)); assertEquals(2, applies)
    } }
    @Test fun onlyUnresolvedRowsFetchAndFailedFallbackPreservesTiles() = runTest { scenario { repo ->
        unresolved = true; assertTrue(sync(repo)); assertEquals(1, reads); assertEquals(1, applies)
        failFallback = true; assertFalse(sync(repo)); assertEquals(2, reads); assertEquals(1, applies)
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
