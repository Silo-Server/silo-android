package org.siloserver.silo.model.request

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestModelsSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `decodes media detail with request state and recommendations`() {
        val payload = """
            {
              "media_type": "movie",
              "tmdb_id": 550,
              "imdb_id": "tt0137523",
              "title": "Fight Club",
              "year": 1999,
              "poster_path": "/poster.jpg",
              "availability": "missing",
              "request": {
                "status": "pending",
                "requestable": false,
                "reason": "Already requested",
                "request_id": "req_1"
              },
              "recommendations": [
                {
                  "media_type": "movie",
                  "tmdb_id": 13,
                  "title": "Forrest Gump",
                  "availability": "available",
                  "library_content_id": "movie-13",
                  "request": { "requestable": false, "reason": "Available" }
                }
              ]
            }
        """.trimIndent()

        val detail = json.decodeFromString(RequestMediaDetail.serializer(), payload)

        assertEquals(RequestMediaType.Movie, detail.mediaType)
        assertEquals(550, detail.tmdbId)
        assertFalse(detail.request.requestable)
        assertEquals("req_1", detail.request.requestId)
        assertEquals("movie-13", detail.recommendations.single().libraryContentId)
    }

    @Test
    fun `decodes request with fulfillment targets`() {
        val payload = """
            {
              "id": "req_1",
              "provider": "tmdb",
              "media_type": "series",
              "tmdb_id": 1399,
              "title": "Game of Thrones",
              "status": "queued",
              "outcome": "active",
              "targets": [
                {
                  "id": "7",
                  "request_id": "req_1",
                  "integration_kind": "sonarr",
                  "instance_name": "Sonarr 4K",
                  "quality": "2160p",
                  "status": "queued",
                  "created_at": "2026-06-09T00:00:00Z",
                  "updated_at": "2026-06-09T00:00:00Z"
                }
              ],
              "created_at": "2026-06-09T00:00:00Z",
              "updated_at": "2026-06-09T00:00:00Z"
            }
        """.trimIndent()

        val request = json.decodeFromString(MediaRequest.serializer(), payload)

        assertEquals(RequestMediaType.Series, request.mediaType)
        assertEquals(RequestStatus.Queued, request.status)
        assertEquals("Sonarr 4K", request.targets.single().instanceName)
        assertTrue(request.isActive)
    }
}
