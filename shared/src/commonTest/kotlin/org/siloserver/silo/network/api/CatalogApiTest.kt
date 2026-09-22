package org.siloserver.silo.network.api

import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CatalogApiTest {

    private class Captured {
        var path: String = ""
        var query: Map<String, String?> = emptyMap()
    }

    private fun api(
        status: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = """{"total":0,"total_exact":true,"window_cursor":"w","page":{"has_more":false},"items":[]}""",
        captured: Captured = Captured(),
    ): Pair<CatalogApi, Captured> {
        val client = HttpClient(
            MockEngine { request ->
                captured.path = request.url.encodedPath
                captured.query = request.url.parameters.names()
                    .associateWith { request.url.parameters[it] }
                respond(
                    content = responseBody,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return CatalogApi(client) to captured
    }

    @Test
    fun `getCatalog uses v2 without legacy snapshot parameters`() = runTest {
        val (api, captured) = api()

        val result = api.getCatalog()

        assertEquals("/api/v2/catalog", captured.path)
        assertFalse("snapshot" in captured.query.keys)
        assertFalse("snapshot_at" in captured.query.keys, "Must NOT send 'snapshot_at' (server reads 'snapshot')")
        assertIs<ApiResult.Success<*>>(result)
    }

    @Test
    fun `getCatalog without snapshotAt omits snapshot param entirely`() = runTest {
        val (api, captured) = api()

        api.getCatalog(limit = 20)

        assertFalse("snapshot" in captured.query.keys, "snapshot should be absent when not supplied")
        assertFalse("snapshot_at" in captured.query.keys, "snapshot_at should never appear")
        assertEquals("20", captured.query["limit"])
    }

    @Test
    fun `getItemVersions reads the v2 items envelope and checks string file ids`() = runTest {
        val (api, captured) = api(
            responseBody = """{"items":[{"file_id":"42","resolution":"1080p","codec_video":"h264","codec_audio":"aac",
                "edition_raw":"International", "edition_key":"international",
                "hdr":false,"container":"mkv","file_size":10,"duration":120,"bitrate":5000,"added_at":"2026-01-01T00:00:00Z"}]}""",
        )

        val result = api.getItemVersions("item-1")

        assertEquals("/api/v2/catalog/items/item-1/versions", captured.path)
        assertIs<ApiResult.Success<*>>(result)
        assertEquals(42, (result as ApiResult.Success).data.single().fileId)
        assertEquals("International", result.data.single().editionRaw)
        assertEquals("international", result.data.single().editionKey)
    }

    @Test
    fun `opaque content ids are encoded as one path segment`() = runTest {
        val (api, captured) = api(responseBody = """{"content_id":"movie:a/b","type":"movie","title":"A","cast":[],"crew":[],"versions":[],"subtitles":[]}""")

        api.getItemDetail("movie:a/b")

        assertEquals("/api/v2/catalog/items/movie:a%2Fb", captured.path)
    }

    @Test
    fun `getItemVersions rejects numeric file ids`() = runTest {
        val (api, _) = api(responseBody = """{"items":[{"file_id":42}]}""")

        assertFalse(api.getItemVersions("item-1") is ApiResult.Success)
    }

    @Test
    fun `getPersonItems uses signed sort and opaque person ID`() = runTest {
        val (api, captured) = api()

        val result = api.getPersonItems(
            personId = 42,
            mediaType = "movie",
            limit = 60,
        )

        assertEquals("/api/v2/catalog", captured.path)
        assertEquals("person", captured.query["source"])
        assertEquals("42", captured.query["person_id"])
        assertEquals("movie", captured.query["type"])
        assertFalse("offset" in captured.query.keys)
        assertEquals("60", captured.query["limit"])
        assertEquals("-year", captured.query["sort"])
        assertFalse("order" in captured.query.keys)
        assertFalse("snapshot" in captured.query.keys)
        assertFalse("snapshot_at" in captured.query.keys)
        assertIs<ApiResult.Success<*>>(result)
    }
}
