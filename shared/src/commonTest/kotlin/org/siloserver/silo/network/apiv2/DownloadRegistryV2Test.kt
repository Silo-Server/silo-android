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

    @Test fun reportStatusPatchesTheRevisionBoundEvent() = runTest {
        val event = DownloadStatusEvent("completed", "2026-01-02T03:04:05.000Z", 1)
        var answer: MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData = {
            reply(row.replace("\"ready\"", "\"completed\"").replace("\"revision\":1", "\"revision\":1,\"status_event_at\":\"2026-01-02T03:04:05.000Z\""))
        }
        var sent = 0
        val c = client {
            sent++
            assertEquals(HttpMethod.Patch, it.method)
            assertEquals("/api/v2/downloads/one", it.url.encodedPath)
            assertEquals(scope, it.attributes[AuthScopeAttributeKey])
            val body = SiloJson.parseToJsonElement((it.body as io.ktor.http.content.TextContent).text)
            assertEquals(SiloJson.parseToJsonElement("""{"status":"completed","updated_at":"2026-01-02T03:04:05.000Z","revision":1}"""), body)
            answer()
        }
        try {
            val api = DownloadRegistryV2Api(c,tokens,devices, ApiV2Gate.Unrestricted)
            val answered = assertIs<ApiResult.Success<DownloadRecord>>(api.reportStatus("one", event, scope)).data
            assertEquals("completed", answered.status); assertEquals("2026-01-02T03:04:05.000Z", answered.statusEventAt)

            answer = { respond("""{"code":"conflict","detail":"The download revision changed."}""", HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/problem+json")) }
            assertEquals(409, assertIs<ApiResult.Error>(api.reportStatus("one", event, scope)).code)

            // An answer for another entry is not this event's receipt.
            answer = { reply(row.replace("\"one\"", "\"two\"")) }
            assertIs<ApiResult.Error>(api.reportStatus("one", event, scope))

            // Events the server would refuse are never sent.
            val before = sent
            assertIs<ApiResult.Error>(api.reportStatus("one", event.copy(revision = 0), scope))
            assertIs<ApiResult.Error>(api.reportStatus("one", event.copy(status = "ready"), scope))
            assertEquals(before, sent)
        } finally { c.close() }
    }

    @Test fun delayedStatusRefreshesItsOwnerAndResendsTheRetainedEvent() = runTest {
        var access = "expired-access"
        var refreshedOwner: AuthScopeSnapshot? = null
        val authenticated = object : TokenManager by TokenManagerImpl() {
            override suspend fun snapshotCurrentScope() = scope
            override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot) = access
            override suspend fun getRefreshTokenForScope(scope: AuthScopeSnapshot) = "owner-refresh"
            override suspend fun saveTokensForScope(scope: AuthScopeSnapshot, accessToken: String, refreshToken: String, expiresIn: Long) {
                refreshedOwner = scope
                access = accessToken
            }
            override suspend fun invalidateSession() = fail("A background report must retain its owner's session.")
        }
        val bodies = mutableListOf<String>()
        val bearers = mutableListOf<String?>()
        var refreshes = 0
        val c = HttpClient(MockEngine { request ->
            assertEquals("example.invalid", request.url.host)
            when (request.url.encodedPath) {
                "/api/v2/auth/refresh" -> {
                    refreshes++
                    reply("""{"access_token":"rotated-access","refresh_token":"rotated-refresh","expires_in":900}""")
                }
                "/api/v2/downloads/one" -> {
                    assertEquals(HttpMethod.Patch, request.method)
                    assertEquals(scope, request.attributes[AuthScopeAttributeKey])
                    bodies += request.body.toByteArray().decodeToString()
                    bearers += request.headers[HttpHeaders.Authorization]
                    if (bearers.size == 1) respond("""{"code":"invalid_token","detail":"Expired."}""",
                        HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/problem+json"))
                    else reply(row.replace("\"ready\"", "\"completed\""))
                }
                else -> fail("Unexpected request ${request.url}")
            }
        }) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = authenticated; deviceMetadataProvider = devices }
        }
        try {
            val event = DownloadStatusEvent("completed", "2026-01-02T03:04:05.000Z", 1)
            assertIs<ApiResult.Success<DownloadRecord>>(
                DownloadRegistryV2Api(c, authenticated, devices, ApiV2Gate.Unrestricted).reportStatus("one", event, scope),
            )
            assertEquals(1, refreshes)
            assertEquals(scope, refreshedOwner)
            assertEquals<List<String?>>(listOf("Bearer expired-access", "Bearer rotated-access"), bearers)
            assertEquals(2, bodies.size)
            assertEquals(bodies[0], bodies[1])
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
