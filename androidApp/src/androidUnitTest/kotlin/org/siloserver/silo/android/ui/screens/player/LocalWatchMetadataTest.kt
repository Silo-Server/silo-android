package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.apiv2.WatchDetailV2Api
import org.siloserver.silo.repository.CatalogRepository
import kotlin.test.*

class LocalWatchMetadataTest {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private var requests = 0
    private val requestedLibraries = mutableListOf<String?>()
    private var hook: () -> Unit = {}
    private var current = true
    private var code = HttpStatusCode.OK
    private var body = """{"content_id":"movie:a/b","type":"movie","title":"Online","versions":[{"file_id":"42","duration_seconds":123,"chapters":[{"index":0,"title":"Start","start_seconds":0,"end_seconds":12}]}],"intro":{"start_seconds":3,"end_seconds":9},"user_data":{"last_file_id":"42","position_seconds":7}}"""
    private suspend fun scenario(block: suspend (CatalogRepository) -> Unit) {
        val client = HttpClient(MockEngine {
            requests++
            requestedLibraries += it.url.parameters["library_id"]
            assertEquals("/api/v2/watch/movie:a%2Fb", it.url.encodedPath)
            assertEquals(owner, it.attributes[AuthScopeAttributeKey])
            hook()
            respond(body, code, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { block(CatalogRepository(CatalogApi(client, watchDetail = WatchDetailV2Api(client, tokens, ApiV2Gate.Unrestricted)))) } finally { client.close() }
    }
    private suspend fun read(repo: CatalogRepository, original: AuthScopeSnapshot? = owner, server: String = "s") =
        loadLocalWatchMetadata(repo, original, server, "p", "movie:a/b") { current }

    @Test fun localMetadataPreservesLibraryAndAllowsUnscopedReads() = runTest { scenario { repo ->
        for (libraryId in listOf(7, 8, null)) {
            assertNotNull(loadLocalWatchMetadata(repo, owner, "s", "p", "movie:a/b", libraryId) { current })
        }
        assertEquals(listOf("7", "8", null), requestedLibraries)
    } }

    @Test fun convertsExactWatchWireForExistingLocalMetadata() = runTest { scenario { repo ->
        val detail = assertNotNull(read(repo))
        assertEquals("Online", detail.title)
        assertEquals(42, detail.versions.single().fileId)
        assertEquals(123.0, detail.versions.single().duration)
        assertEquals(12.0, detail.versions.single().chapters!!.single().endSeconds)
        assertEquals(3.0, detail.intro!!.start)
        assertEquals(7.0, detail.userData!!.positionSeconds)
    } }
    @Test fun missingOrMismatchedOwnerSkipsNetworkAndLatePinOrRunDiscards() = runTest { scenario { repo ->
        assertNull(read(repo, null)); assertNull(read(repo, server = "other")); assertEquals(0, requests)
        val original = owner; owner = owner.copy(profileToken = "new")
        assertNull(read(repo, original)); assertEquals(0, requests)
        hook = { owner = owner.copy(credentialEpoch = owner.credentialEpoch + 1) }
        assertNull(read(repo)); assertEquals(1, requests)
        hook = { current = false }; assertNull(read(repo)); assertEquals(2, requests)
    } }
    @Test fun errorsWrongIdentityAndUnsafeFileIdsRemainOptionalFailure() = runTest { scenario { repo ->
        code = HttpStatusCode.ServiceUnavailable; assertNull(read(repo))
        code = HttpStatusCode.Accepted; assertNull(read(repo))
        code = HttpStatusCode.OK
        val valid = body
        body = valid.replace("movie:a/b", "movie:other"); assertNull(read(repo))
        body = valid.replace("\"file_id\":\"42\"", "\"file_id\":42"); assertNull(read(repo))
        body = valid.replace("\"file_id\":\"42\"", "\"file_id\":\"2147483648\""); assertNull(read(repo))
        body = valid.replace("duration_seconds", "duration"); assertNull(read(repo))
    } }
}
