package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.image.ImageSize
import org.siloserver.silo.model.image.ImagesCapability
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.ImagesApi
import org.siloserver.silo.network.api.SectionApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CAPABILITY_BODY = """
{
  "schema_version": 1,
  "param": "image_size",
  "sizes": ["small", "medium", "large", "original"],
  "widths": {
    "poster": {"small": 300, "medium": 500, "large": 780},
    "still": {"small": 300, "medium": 500, "large": 780},
    "logo": {"small": 300, "medium": 500, "large": 1280},
    "backdrop": {"small": 300, "medium": 780, "large": 1920}
  },
  "original_max_width_px": 1920
}
"""

private const val CATALOG_BODY = """{"total":0,"has_more":false,"items":[]}"""
private const val SECTIONS_BODY = """{"sections":[]}"""

class ImageSizeSelectorTest {

    /** Every non-capability request the engine saw, path to query map. */
    private class Recorder {
        val requests = mutableListOf<Pair<String, Map<String, String?>>>()
        var capabilityHits = 0

        fun lastQuery(): Map<String, String?> = requests.last().second
    }

    private fun client(
        recorder: Recorder,
        capabilityStatus: HttpStatusCode = HttpStatusCode.OK,
        capabilityBody: String = CAPABILITY_BODY,
    ): HttpClient = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (path == "/api/v1/images/capability") {
                recorder.capabilityHits++
                respond(
                    content = if (capabilityStatus.isSuccess()) capabilityBody else """{"error":"not_found"}""",
                    status = capabilityStatus,
                    headers = json,
                )
            } else {
                recorder.requests += path to request.url.parameters.names()
                    .associateWith { request.url.parameters[it] }
                val body = if (path.endsWith("/sections")) SECTIONS_BODY else CATALOG_BODY
                respond(content = body, status = HttpStatusCode.OK, headers = json)
            }
        },
    ) {
        install(ContentNegotiation) { json(SiloJson) }
    }

    private fun selector(client: HttpClient, preferred: String?) =
        ImageSizeSelector(ImagesApi(client), preferred?.let { PreferredImageSize(it) })

    @Test
    fun `catalog request carries image_size when the server advertises it`() = runTest {
        val recorder = Recorder()
        val http = client(recorder)
        val api = CatalogApi(http, selector(http, ImageSize.LARGE))

        api.getCatalog(limit = 20)

        assertEquals("large", recorder.lastQuery()["image_size"])
    }

    @Test
    fun `no preferred size means no image_size param and no capability probe`() = runTest {
        val recorder = Recorder()
        val http = client(recorder)
        val api = CatalogApi(http, selector(http, preferred = null))

        api.getCatalog(limit = 20)

        assertTrue("image_size" !in recorder.lastQuery().keys)
        assertEquals(0, recorder.capabilityHits, "Probe must not run when nothing was requested")
    }

    @Test
    fun `omitting the selector entirely leaves requests untouched`() = runTest {
        val recorder = Recorder()
        val api = CatalogApi(client(recorder))

        api.getCatalog(limit = 20)

        assertTrue("image_size" !in recorder.lastQuery().keys)
        assertEquals(0, recorder.capabilityHits)
    }

    @Test
    fun `a 404 capability endpoint disables the feature`() = runTest {
        val recorder = Recorder()
        val http = client(recorder, capabilityStatus = HttpStatusCode.NotFound)
        val api = CatalogApi(http, selector(http, ImageSize.LARGE))

        api.getCatalog(limit = 20)

        assertTrue("image_size" !in recorder.lastQuery().keys)
    }

    @Test
    fun `a size the server does not list is not requested`() = runTest {
        val recorder = Recorder()
        val http = client(
            recorder,
            capabilityBody = """{"schema_version":1,"param":"image_size","sizes":["small","medium"]}""",
        )
        val api = CatalogApi(http, selector(http, ImageSize.LARGE))

        api.getCatalog(limit = 20)

        assertTrue("image_size" !in recorder.lastQuery().keys)
    }

    @Test
    fun `the capability is probed once and reused across apis`() = runTest {
        val recorder = Recorder()
        val http = client(recorder)
        val shared = selector(http, ImageSize.LARGE)
        val catalog = CatalogApi(http, shared)
        val sections = SectionApi(http, shared)

        catalog.getCatalog(limit = 20)
        catalog.getItemDetail("abc")
        catalog.getWatchDetail("abc")
        catalog.getSeasons("abc")
        catalog.getEpisodes("abc", 1)
        catalog.getItemEpisodes("abc")
        catalog.getPersonItems(personId = 7)
        sections.getHomeSections()
        sections.getHomeSectionItems("continue")
        sections.getLibrarySections(3)
        sections.getLibrarySectionItems(3, "recent")
        sections.getLibraryCollectionItems("col-1")

        assertEquals(1, recorder.capabilityHits, "Capability must be cached for the session")
        assertEquals(12, recorder.requests.size)
        for ((path, query) in recorder.requests) {
            assertEquals("large", query["image_size"], "Expected image_size on $path")
        }
    }

    @Test
    fun `home layout carries no artwork and so no image_size`() = runTest {
        val recorder = Recorder()
        val http = client(recorder)
        val sections = SectionApi(http, selector(http, ImageSize.LARGE))

        sections.getHomeLayout()

        assertTrue("image_size" !in recorder.lastQuery().keys)
    }

    @Test
    fun `reset re-probes the capability`() = runTest {
        val recorder = Recorder()
        val http = client(recorder)
        val shared = selector(http, ImageSize.LARGE)

        assertEquals("large", shared.current())
        shared.reset()
        assertEquals("large", shared.current())

        assertEquals(2, recorder.capabilityHits)
    }

    @Test
    fun `capability payload decodes every advertised width`() {
        val capability = SiloJson.decodeFromString<ImagesCapability>(CAPABILITY_BODY)

        assertEquals(1, capability.schemaVersion)
        assertEquals("image_size", capability.param)
        assertEquals(listOf("small", "medium", "large", "original"), capability.sizes)
        assertEquals(780, capability.widths["poster"]?.get("large"))
        assertEquals(780, capability.widths["still"]?.get("large"))
        assertEquals(1280, capability.widths["logo"]?.get("large"))
        assertEquals(1920, capability.widths["backdrop"]?.get("large"))
        assertEquals(1920, capability.originalMaxWidthPx)
        assertTrue(capability.supports(ImageSize.LARGE))
        assertTrue(!capability.supports("gigantic"))
    }

    @Test
    fun `an unknown-key capability payload still decodes and an empty one supports nothing`() {
        val forwardCompatible = SiloJson.decodeFromString<ImagesCapability>(
            """{"schema_version":2,"param":"image_size","sizes":["large"],"future_key":{"a":1}}""",
        )
        assertTrue(forwardCompatible.supports(ImageSize.LARGE))
        assertNull(forwardCompatible.originalMaxWidthPx)

        val empty = SiloJson.decodeFromString<ImagesCapability>("{}")
        assertTrue(!empty.supports(ImageSize.LARGE))
    }
}
