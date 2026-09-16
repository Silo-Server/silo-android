package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.model.ebook.*
import org.siloserver.silo.network.*
import org.siloserver.silo.repository.EbookConfigSession
import kotlin.test.*

class EbookReaderV2Test {
    private var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.reply(body: String, etag: String = "\"one\"", status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(etag)))

    @Test fun orderedProgressRetainsOriginalEventAndStringIdentityAcrossRetry() = runTest {
        val bodies = mutableListOf<String>()
        val c = client { request ->
            assertEquals("/api/v2/ebooks/book/progress", request.url.encodedPath)
            assertEquals(HttpMethod.Put, request.method); assertTrue(request.attributes[SingleAttemptAttributeKey])
            bodies += request.body.toByteArray().decodeToString()
            if (bodies.size == 1) reply("{}", status = HttpStatusCode.ServiceUnavailable)
            else reply("""{"progress":{"content_id":"book","file_id":"7","location":"back","progress":0.2,"updated_at":"2026-09-05T12:00:00Z"}}""")
        }
        try {
            val api = EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val event = SaveEbookProgressRequest(7, "back", 0.2, "2026-09-05T12:00:00Z")
            assertIs<ApiResult.Error>(api.saveProgress("book", event, scope))
            assertEquals(0.2, assertIs<ApiResult.Success<EbookReaderProgress>>(api.saveProgress("book", event, scope)).data.progress)
            assertEquals(bodies.first(), bodies.last())
            val json = SiloJson.parseToJsonElement(bodies.first()).jsonObject
            assertEquals(JsonPrimitive("7"), json["file_id"])
            assertEquals(JsonPrimitive(event.updatedAt), json["updated_at"])
        } finally { c.close() }
    }

    @Test fun absentProgressIsEmptyAndForeignContentIsRejected() = runTest {
        var body = "{}"
        val c = client { reply(body) }
        try {
            val api = EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertNull(assertIs<ApiResult.Success<EbookReaderProgress>>(api.progress("book", scope)).data.fileId)
            body = """{"progress":{"content_id":"other","file_id":"7","location":"x","progress":0.5,"updated_at":"2026-09-05T12:00:00Z"}}"""
            assertIs<ApiResult.Error>(api.progress("book", scope))
        } finally { c.close() }
    }

    @Test fun configSerializesValidatorsPreservesOtherClientsAndFencesConflict() = runTest {
        val validators = mutableListOf<String?>()
        val c = client { request ->
            if (request.method == HttpMethod.Get) reply("""{"content_id":"book","config":{"web":{"theme":"dark"}}}""")
            else {
                validators += request.headers[HttpHeaders.IfMatch]
                assertTrue(request.attributes[SingleAttemptAttributeKey])
                val config = SiloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["config"]!!.jsonObject
                assertEquals("dark", config["web"]!!.jsonObject["theme"]!!.jsonPrimitive.content)
                if (validators.size == 1) reply("""{"content_id":"book","config":$config}""", "\"two\"")
                else reply("{}", "\"foreign\"", HttpStatusCode.PreconditionFailed)
            }
        }
        try {
            val session = EbookConfigSession(EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted), "book", scope)
            assertIs<ApiResult.Success<JsonObject>>(session.load())
            val settings = buildJsonObject { put("textScale", 1.2) }
            assertIs<ApiResult.Success<Unit>>(session.saveAndroidDisplay(settings))
            assertEquals(412, assertIs<ApiResult.Error>(session.saveAndroidDisplay(settings)).code)
            assertEquals("config_reload_required", assertIs<ApiResult.Error>(session.saveAndroidDisplay(settings)).error)
            assertEquals<List<String?>>(listOf("\"one\"", "\"two\""), validators)
        } finally { c.close() }
    }

    @Test fun configReplyAfterProfileChangeCannotInstallValidator() = runTest {
        val captured = scope; var puts = 0
        val c = client { request ->
            if (request.method == HttpMethod.Put) puts++
            scope = scope.copy(profileId = "other", identityGeneration = 2)
            reply("""{"content_id":"book","config":{}}""")
        }
        try {
            val session = EbookConfigSession(EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted), "book", captured)
            assertIs<ApiResult.Error>(session.load())
            assertIs<ApiResult.Error>(session.saveAndroidDisplay(JsonObject(emptyMap())))
            assertEquals(0, puts)
        } finally { c.close() }
    }
    @Test fun flatConversionCapabilityPreservesFormatAndHeaderFields() = runTest {
        val c = client { request ->
            assertEquals("/api/v2/capabilities/ebooks", request.url.encodedPath)
            reply("""{"kindle_conversion":true,"source_formats":["mobi"],"served_format":"epub","header":"X-Conversion","header_failed_value":"failed"}""")
        }
        try {
            val capability = assertIs<ApiResult.Success<EbookConversionCapability>>(EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted).capability()).data
            assertTrue(capability.enabled); assertEquals(listOf("mobi"), capability.sourceFormats)
            assertEquals("X-Conversion", capability.header)
        } finally { c.close() }
    }

    @Test fun missingWriteReceiptAndMalformedConfigCannotBeAcknowledged() = runTest {
        val c = client { reply("{}") }
        try {
            val api = EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Error>(api.saveProgress("book", SaveEbookProgressRequest(7, "page", 0.2, "2026-09-05T12:00:00Z"), scope))
            val session = EbookConfigSession(api, "book", scope)
            assertEquals("invalid_response", assertIs<ApiResult.Error>(session.load()).error)
            assertEquals("config_reload_required", assertIs<ApiResult.Error>(session.saveAndroidDisplay(JsonObject(emptyMap()))).error)
        } finally { c.close() }
    }

}
