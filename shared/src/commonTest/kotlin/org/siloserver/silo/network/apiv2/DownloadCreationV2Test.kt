package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.model.download.*
import org.siloserver.silo.network.*
import kotlin.test.*

class DownloadCreationV2Test {
    private var scope = AuthScopeSnapshot("server","profile","https://example.invalid",null,identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private val devices = object : DeviceMetadataProvider { override suspend fun current() = SiloDeviceMetadata("device","Phone","Android") }
    private val row = """{"id":"entry","content_id":"movie","media_file_id":"42","device_id":"device","file_size":100,"bytes_sent":0,"kind":"queued","status":"ready","quality":"original","effective_quality":"original","delivery_format":"original","target_bitrate_kbps":0,"revision":7,"created_at":"2026-01-01T00:00:00Z"}"""
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun api(c: HttpClient) = DownloadCreationV2Api(c,tokens,devices,DownloadRegistryV2Api(c,tokens,devices, ApiV2Gate.Unrestricted), ApiV2Gate.Unrestricted)
    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(body,status,headersOf(HttpHeaders.ContentType,"application/json"))

    @Test fun createSendsExactlyOneDeviceIdHeaderThroughTheAuthPlugin() = runTest {
        // Regression: the creation API used to add X-Silo-Device-Id itself while the
        // auth plugin also attached it, so the server received "device,device",
        // stored that, and the receipt guard rejected the reply.
        var deviceValues: List<String>? = null
        val authenticated = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
        authenticated.setServerUrl(scope.serverUrl); authenticated.saveTokens("access","refresh",3600)
        val c = HttpClient(MockEngine {
            if (it.method == HttpMethod.Get) json("""{"items":[],"page":{"has_more":false}}""") else {
                deviceValues = it.headers.getAll("X-Silo-Device-Id")
                json("""{"items":[$row],"skipped":[],"page":{"has_more":false}}""",HttpStatusCode.Accepted)
            }
        }) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = authenticated; deviceMetadataProvider = devices }
        }
        try {
            val api = DownloadCreationV2Api(c,authenticated,devices,DownloadRegistryV2Api(c,authenticated,devices, ApiV2Gate.Unrestricted), ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Success<DownloadRecord>>(api.create(DownloadRequest("movie",fileId=42),scope))
            assertEquals(listOf("device"),deviceValues)
        } finally { c.close() }
    }

    @Test fun singleAbsenceAndReplacementUseExactGuardWithoutLegacyFields() = runTest {
        var existing = false
        val c = client {
            if (it.method == HttpMethod.Get) json("""{"items":[${if (existing) row else ""}],"page":{"has_more":false}}""") else {
                assertEquals("/api/v2/downloads",it.url.encodedPath)
                assertEquals(scope,it.attributes[AuthScopeAttributeKey]); assertTrue(it.attributes[SingleAttemptAttributeKey])
                // No auth plugin on this client: the API must not add the device header itself.
                assertNull(it.headers["X-Silo-Device-Id"])
                val body = SiloJson.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
                assertEquals(JsonPrimitive("42"),body["media_file_id"])
                assertEquals(JsonPrimitive(if (existing) 7 else 0),body["expected_revision"])
                assertEquals(if (existing) JsonPrimitive("entry") else null,body["expected_download_id"])
                assertFalse(body.containsKey("expected_entries")); assertFalse(body.containsKey("file_id")); assertFalse(body.containsKey("target_bitrate_kbps"))
                json("""{"items":[${if (existing) row.replace("\"quality\":\"original\"", "\"quality\":\"5mbps\"") else row}],"skipped":[],"page":{"has_more":false}}""",HttpStatusCode.Accepted)
            }
        }
        try {
            val api = api(c)
            assertIs<ApiResult.Success<DownloadRecord>>(api.create(DownloadRequest("movie",fileId=42),scope))
            existing = true
            assertIs<ApiResult.Success<DownloadRecord>>(api.create(DownloadRequest("movie",fileId=42,quality="5mbps"),scope))
        } finally { c.close() }
    }

    @Test fun matchingEntryReconcilesWithoutResetAnd401HasNoReplay() = runTest {
        var posts = 0
        val c = client {
            if (it.method == HttpMethod.Get) json("""{"items":[$row],"page":{"has_more":false}}""") else {
                posts++; json("""{"detail":"Rejected"}""",HttpStatusCode.Unauthorized)
            }
        }
        try {
            val api = api(c)
            assertEquals("entry",assertIs<ApiResult.Success<DownloadRecord>>(api.create(DownloadRequest("movie",fileId=42),scope)).data.id)
            assertEquals(0,posts)
            assertIs<ApiResult.Error>(api.create(DownloadRequest("movie",fileId=42,quality="5mbps"),scope))
            assertEquals(1,posts)
        } finally { c.close() }
    }

    @Test fun batchContinuesEmptyPageAndPreservesSkippedAndOldBatch() = runTest {
        var firstBody: JsonObject? = null
        var calls = 0
        val c = client {
            calls++
            val body = SiloJson.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            if (firstBody == null) firstBody = body else assertEquals(firstBody,body)
            assertEquals(JsonObject(emptyMap()),body["expected_entries"])
            assertFalse(body.containsKey("expected_revision"))
            val batch = body.getValue("batch_id").jsonPrimitive.content
            if (calls == 1) json("""{"items":[],"skipped":[{"episode_id":"episode1","reason":"no_file"}],"batch_id":"$batch","page":{"has_more":true,"next_cursor":"next"}}""",HttpStatusCode.Accepted)
            else {
                assertEquals("next",it.url.parameters["cursor"])
                val retained = row.replace("\"content_id\":\"movie\"","\"content_id\":\"series\",\"episode_id\":\"episode2\",\"batch_id\":\"older-batch\"")
                json("""{"items":[$retained],"skipped":[],"batch_id":"$batch","page":{"has_more":false}}""",HttpStatusCode.Accepted)
            }
        }
        try {
            val result = assertIs<ApiResult.Success<DownloadsListResponse>>(api(c).createBatch(DownloadRequest("series",series=true),scope)).data
            assertEquals(2,calls); assertEquals("older-batch",result.downloads.single().batchId)
            assertEquals("no_file",result.skipped.single().reason)
        } finally { c.close() }
    }

    @Test fun staleBatchReceiptCannotDispatchAnotherPage() = runTest {
        var calls = 0
        val c = client {
            calls++
            val batch = SiloJson.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject.getValue("batch_id").jsonPrimitive.content
            scope = scope.copy(identityGeneration = 2)
            json("""{"items":[],"skipped":[],"batch_id":"$batch","page":{"has_more":true,"next_cursor":"next"}}""",HttpStatusCode.Accepted)
        }
        try {
            assertEquals("identity_changed",assertIs<ApiResult.Error>(api(c).createBatch(DownloadRequest("series",series=true),scope)).error)
            assertEquals(1,calls)
        } finally { c.close() }
    }

    @Test fun batchLostSecondReceiptReturnsNoPrefixAndDoesNotRestart() = runTest {
        var calls = 0
        val c = client {
            calls++
            if (calls == 2) throw IllegalStateException("lost receipt")
            val batch = SiloJson.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject.getValue("batch_id").jsonPrimitive.content
            json("""{"items":[],"skipped":[],"batch_id":"$batch","page":{"has_more":true,"next_cursor":"next"}}""",HttpStatusCode.Accepted)
        }
        try {
            assertIs<ApiResult.NetworkError>(api(c).createBatch(DownloadRequest("series",series=true),scope))
            assertEquals(2,calls)
        } finally { c.close() }
    }
}
