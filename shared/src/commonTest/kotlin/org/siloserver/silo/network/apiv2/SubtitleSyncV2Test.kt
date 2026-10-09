package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*
import kotlin.test.*

class SubtitleSyncV2Test {
    private val scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private val key = "external-" + "0123456789abcdef".repeat(4)

    private fun state(timing: String = """{"offset_ms":0,"scale":1}""", sync: String? = null, file: String = "1", key: String = this.key) =
        """{"key":"$key","media_file_id":"$file","source":"external","language":"en","format":"srt","label":"a.en.srt","timing":$timing""" +
            (sync?.let { ""","sync":$it""" } ?: "") + "}"

    private val pending = """{"id":"2","status":"pending","trigger":"manual","phase":"queued","progress":0,"confidence":null,"created_at":"2026-10-04T02:37:35.235Z","finished_at":null}"""

    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK, etag: String? = null) =
        respond(
            body,
            status,
            if (etag == null) headersOf(HttpHeaders.ContentType, "application/json")
            else headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf(etag)),
        )

    @Test
    fun listsAndStartsBySyncKey() = runTest {
        val c = client { request ->
            when (request.method to request.url.encodedPath) {
                HttpMethod.Get to "/api/v2/subtitles/1/sync" -> reply("""{"subtitles":[${state()}]}""")
                HttpMethod.Post to "/api/v2/subtitles/1/sync/$key" ->
                    reply("""{"subtitle":${state(sync = pending)}}""", HttpStatusCode.Accepted)
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        try {
            val api = SubtitleSyncV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val listed = assertIs<ApiResult.Success<List<SubtitleSyncState>>>(api.list(1)).data
            assertEquals(listOf(key), listed.map { it.key })
            val started = assertIs<ApiResult.Success<SubtitleSyncState>>(api.start(1, key)).data
            assertEquals("pending", started.sync?.status)
            assertEquals("queued", started.sync?.phase)
        } finally {
            c.close()
        }
    }

    @Test
    fun refusesAnswersForAnotherFileOrSubtitle() = runTest {
        var body = """{"subtitles":[${state(file = "2")}]}"""
        val c = client { reply(body, if (it.method == HttpMethod.Post) HttpStatusCode.Accepted else HttpStatusCode.OK) }
        try {
            val api = SubtitleSyncV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Error>(api.list(1))
            body = """{"subtitle":${state(key = "stored-9")}}"""
            assertIs<ApiResult.Error>(api.start(1, key))
        } finally {
            c.close()
        }
    }

    @Test
    fun neverPutsAMalformedKeyInAUrl() = runTest {
        val c = client { error("no request expected: ${it.url}") }
        try {
            val api = SubtitleSyncV2Api(c, tokens, ApiV2Gate.Unrestricted)
            for (bad in listOf("stored-0", "stored-x", "external-ABC", "../stored-9", "external-" + "a".repeat(63))) {
                assertEquals("invalid_sync_key", assertIs<ApiResult.Error>(api.start(1, bad)).error)
            }
            assertTrue(isSubtitleSyncKey("stored-9"))
            assertTrue(isSubtitleSyncKey(key))
        } finally {
            c.close()
        }
    }

    @Test
    fun resetSendsTheReadValidator() = runTest {
        var puts = 0
        val c = client { request ->
            when (request.method) {
                HttpMethod.Get -> reply("""{"subtitle":${state(timing = """{"offset_ms":-3010,"scale":1}""")}}""", etag = "\"v1\"")
                HttpMethod.Put -> {
                    puts++
                    assertEquals("/api/v2/subtitles/1/sync/$key/timing", request.url.encodedPath)
                    assertEquals("\"v1\"", request.headers[HttpHeaders.IfMatch])
                    val sent = SiloJson.decodeFromString(SubtitleTiming.serializer(), request.body.toByteArray().decodeToString())
                    assertEquals(SubtitleTiming(), sent)
                    reply("""{"subtitle":${state()}}""", etag = "\"v2\"")
                }
                else -> error("unexpected ${request.method}")
            }
        }
        try {
            val api = SubtitleSyncV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val reset = assertIs<ApiResult.Success<SubtitleSyncState>>(api.setTiming(1, key, SubtitleTiming())).data
            assertTrue(reset.timing.isIdentity)
            assertEquals(1, puts)
        } finally {
            c.close()
        }
    }

    @Test
    fun aStaleValidatorOrADemoRefusalSurfacesItsStatus() = runTest {
        var putStatus = HttpStatusCode.PreconditionFailed
        val problem = """{"type":"https://siloserver.org/docs/api/v2/problems/precondition_failed","title":"x","status":412,"detail":"stale"}"""
        val c = client { request ->
            when (request.method) {
                HttpMethod.Get -> reply("""{"subtitle":${state()}}""", etag = "\"v1\"")
                else -> reply(problem, putStatus)
            }
        }
        try {
            val api = SubtitleSyncV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertEquals(412, assertIs<ApiResult.Error>(api.setTiming(1, key, SubtitleTiming())).code)
            putStatus = HttpStatusCode.Forbidden
            assertEquals(403, assertIs<ApiResult.Error>(api.start(1, key)).code)
        } finally {
            c.close()
        }
    }

    @Test
    fun aReadWithoutValidatorDoesNotPut() = runTest {
        val c = client { request ->
            assertEquals(HttpMethod.Get, request.method)
            reply("""{"subtitle":${state()}}""")
        }
        try {
            val api = SubtitleSyncV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertEquals("missing_etag", assertIs<ApiResult.Error>(api.setTiming(1, key, SubtitleTiming())).error)
        } finally {
            c.close()
        }
    }
}
