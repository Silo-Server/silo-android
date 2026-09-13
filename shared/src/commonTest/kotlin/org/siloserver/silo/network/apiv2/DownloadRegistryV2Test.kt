package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import org.siloserver.silo.model.download.*
import kotlin.test.*

class DownloadRegistryV2Test {
    private var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private val devices = object : DeviceMetadataProvider { override suspend fun current() = SiloDeviceMetadata("device", "test", "android") }
    private val row = """{"id":"one","content_id":"movie","device_id":"device","media_file_id":"42","file_size":5,"bytes_sent":0,"kind":"movie","status":"ready","quality":"original","effective_quality":"original","delivery_format":"original","target_bitrate_kbps":0,"revision":1,"created_at":"2026-01-01T00:00:00Z"}"""
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.reply(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test fun completePagingRetainsCursorAndCapturedIdentity() = runTest {
        var calls = 0
        val c = client {
            assertEquals(scope, it.attributes[AuthScopeAttributeKey])
            assertEquals("/api/v2/downloads", it.url.encodedPath)
            assertEquals("100", it.url.parameters["limit"])
            calls++
            if (calls == 1) reply("""{"items":[$row],"page":{"has_more":true,"next_cursor":"opaque+/="}}""")
            else {
                assertEquals("opaque+/=", it.url.parameters["cursor"])
                reply("""{"items":[${row.replace("one", "two")}],"page":{"has_more":false}}""")
            }
        }
        try {
            val rows = assertIs<ApiResult.Success<DownloadsListResponse>>(DownloadRegistryV2Api(c,tokens,devices, ApiV2Gate.Unrestricted).list(scope)).data.downloads
            assertEquals(listOf("one","two"), rows.map { it.id }); assertEquals(42, rows[0].mediaFileId)
            assertEquals(1, rows[0].revision)
        } finally { c.close() }
    }

    @Test fun partialAndUnsupportedRegistriesNeverReturnRows() = runTest {
        for (mode in listOf("network", "loop", "duplicate", "foreign", "numeric", "revision")) {
            var calls = 0
            val c = client {
                calls++
                if (calls == 2 && mode == "network") respond("", HttpStatusCode.ServiceUnavailable)
                else {
                    val nextRow = when {
                        calls == 1 -> row
                        mode == "foreign" -> row.replace("device\"", "other\"")
                        mode == "numeric" -> row.replace("\"42\"", "42")
                        mode == "revision" -> row.replace("\"revision\":1", "\"revision\":0")
                        mode == "duplicate" -> row
                        else -> row.replace("one", "two")
                    }
                    reply("""{"items":[$nextRow],"page":{"has_more":true,"next_cursor":"same"}}""")
                }
            }
            try { assertFalse(DownloadRegistryV2Api(c,tokens,devices, ApiV2Gate.Unrestricted).list(scope) is ApiResult.Success, mode) }
            finally { c.close() }
        }
    }

    @Test fun deleteRequires204AndRejectsStaleResponse() = runTest {
        var status = HttpStatusCode.OK
        var replace = false
        val c = client {
            assertEquals(HttpMethod.Delete, it.method)
            assertEquals(scope, it.attributes[AuthScopeAttributeKey])
            if (replace) scope = scope.copy(identityGeneration = 2)
            respond("", status)
        }
        try {
            val api = DownloadRegistryV2Api(c,tokens,devices, ApiV2Gate.Unrestricted)
            assertFalse(api.delete("one",scope) is ApiResult.Success)
            status = HttpStatusCode.NoContent
            assertIs<ApiResult.Success<Unit>>(api.delete("one",scope))
            replace = true
            assertIs<ApiResult.Error>(api.delete("one",scope))
        } finally { c.close() }
    }

    @Test fun capabilityRequiresVersionedStateAndFailsClosed() = runTest {
        var body = """{"revision":"rev","state":"future","enabled":true,"download_allowed":true}"""
        val c = client { reply(body) }
        try {
            val api = DownloadRegistryV2Api(c,tokens,devices, ApiV2Gate.Unrestricted)
            assertFalse(assertIs<ApiResult.Success<DownloadCapability>>(api.capability(scope)).data.isUsable)
            body = """{"enabled":true,"download_allowed":true}"""
            assertIs<ApiResult.Error>(api.capability(scope))
        } finally { c.close() }
    }
}
