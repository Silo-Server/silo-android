package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.apiv2.WatchDetailV2Api
import org.siloserver.silo.repository.CatalogRepository
import kotlin.test.*

class ReadyWatchMetadataTest {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private var ownsLoad = true
    private var requests = 0
    private val requestedLibraries = mutableListOf<String?>()
    private var code = HttpStatusCode.OK
    private var requestHook: () -> Unit = {}
    private var captureHook: suspend () -> Unit = {}
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot { captureHook(); return owner }
    }
    private suspend fun scenario(block: suspend (CatalogRepository) -> Unit) {
        val client = HttpClient(MockEngine {
            requests++
            requestedLibraries += it.url.parameters["library_id"]
            assertEquals("/api/v2/watch/movie:a", it.url.encodedPath)
            assertEquals(owner, it.attributes[AuthScopeAttributeKey])
            requestHook()
            respond("""{"content_id":"movie:a","type":"movie","title":"A","versions":[{"file_id":"7","duration_seconds":90}]}""",
                code, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { block(CatalogRepository(CatalogApi(client, watchDetail = WatchDetailV2Api(client, tokens, ApiV2Gate.Unrestricted)))) } finally { client.close() }
    }
    private fun lease(repo: CatalogRepository, captured: AuthScopeSnapshot? = owner, url: String = "https://example.invalid") =
        ReadyWatchMetadata(repo, captured, "movie:a", url) { ownsLoad }

    @Test fun readyMetadataPreservesLibraryAndAllowsUnscopedReads() = runTest { scenario { repo ->
        for (libraryId in listOf(7, 8, null)) {
            val metadata = ReadyWatchMetadata(repo, owner, "movie:a", owner.serverUrl, libraryId) { ownsLoad }
            assertNotNull(metadata.read())
        }
        assertEquals(listOf("7", "8", null), requestedLibraries)
    } }

    @Test fun originalOwnerReadsAndNetworkFailureKeepsReadyFallbackAvailable() = runTest { scenario { repo ->
        val metadata = lease(repo)
        assertEquals(7, assertNotNull(metadata.read()).versions.single().fileId)
        assertTrue(metadata.current())
        code = HttpStatusCode.ServiceUnavailable
        assertNull(metadata.read()); assertTrue(metadata.current())
        val missing = lease(repo, null)
        assertNull(missing.read()); assertTrue(missing.current()); assertEquals(2, requests)
    } }
    @Test fun replacementDuringCoordinatorOrHttpAndWrongReadyServerRefuse() = runTest { scenario { repo ->
        val beforeCoordinator = lease(repo)
        owner = owner.copy(profileToken = "replacement")
        assertNull(beforeCoordinator.read()); assertFalse(beforeCoordinator.current()); assertEquals(0, requests)
        val foreignReady = lease(repo, url = "https://other.invalid")
        assertNull(foreignReady.read()); assertFalse(foreignReady.current()); assertEquals(0, requests)
        val inFlight = lease(repo)
        requestHook = { owner = owner.copy(credentialEpoch = owner.credentialEpoch + 1) }
        assertNull(inFlight.read()); assertFalse(inFlight.current()); assertEquals(1, requests)
    } }
    @Test fun laterPreparationAndSuspendedAuthorityCannotPublishOldMetadata() = runTest { scenario { repo ->
        val metadata = lease(repo)
        assertNotNull(metadata.read())
        owner = owner.copy(identityGeneration = owner.identityGeneration + 1)
        assertFalse(metadata.current())
        val replacement = lease(repo)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        captureHook = { entered.complete(Unit); release.await() }
        val checking = async { replacement.current() }
        entered.await(); ownsLoad = false; release.complete(Unit)
        assertFalse(checking.await())
        assertNull(replacement.read()); assertEquals(1, requests)
    } }
}
