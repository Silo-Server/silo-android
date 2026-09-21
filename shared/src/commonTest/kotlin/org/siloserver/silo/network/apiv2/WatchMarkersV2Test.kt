package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeAttributeKey
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.RequireSiloAuthAttributeKey
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.playback.PlaybackMarkersUpdate
import org.siloserver.silo.playback.markersForVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WatchMarkersV2Test {
    private var owner = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = owner
    }
    private val markerResponse = """{"file_id":"7","marker_segments":[
        {"kind":"intro","start_seconds":10,"end_seconds":20},
        {"kind":"intro","start_seconds":30,"end_seconds":40},
        {"kind":"recap","start_seconds":0,"end_seconds":10},
        {"kind":"preview","start_seconds":90,"end_seconds":100}
    ]}"""

    @Test
    fun watchPreservesVersionOccurrencesAndAdaptsSingularRanges() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/watch/episode", request.url.encodedPath)
            assertEquals(owner, request.attributes[AuthScopeAttributeKey])
            respond("""{
                "content_id":"episode","type":"episode","title":"Episode",
                "recap":{"start_seconds":0,"end_seconds":5},
                "versions":[
                    {"file_id":"7","duration_seconds":120,
                     "intro":{"start_seconds":10,"end_seconds":20},
                     "recap":{"start_seconds":0,"end_seconds":10},
                     "preview":{"start_seconds":90,"end_seconds":100},
                     "marker_segments":[
                        {"kind":"intro","start_seconds":10,"end_seconds":20},
                        {"kind":"intro","start_seconds":30,"end_seconds":40}
                     ]},
                    {"file_id":"8","duration_seconds":120,
                     "intro":{"start_seconds":10,"end_seconds":20},"marker_segments":[]}
                ]
            }""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val detail = assertIs<ApiResult.Success<WatchDetail>>(
                WatchDetailV2Api(client, tokens, ApiV2Gate.Unrestricted).detail("episode", owner),
            ).data
            val version = detail.versions.first()
            assertEquals(TimeRange(0.0, 5.0), detail.recap)
            assertEquals(TimeRange(0.0, 10.0), version.recap)
            assertEquals(TimeRange(90.0, 100.0), version.preview)
            assertEquals(listOf(20.0, 40.0), detail.markersForVersion(version).map { it.endSeconds })
            assertTrue(detail.markersForVersion(detail.versions.last()).isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun fileMarkerReadRequiresMatchingV2IdentityAndSuccessfulStatus() = runTest {
        var body = markerResponse
        var status = HttpStatusCode.OK
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals(owner, request.attributes[AuthScopeAttributeKey])
            assertTrue(request.attributes[RequireSiloAuthAttributeKey])
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = WatchDetailV2Api(client, tokens, ApiV2Gate.Unrestricted)
            val result = assertIs<ApiResult.Success<PlaybackMarkersUpdate>>(api.fileMarkers(7, owner)).data
            assertEquals(7, result.fileId)
            assertEquals(4, result.markerSegments.size)
            assertEquals(TimeRange(0.0, 10.0), result.recap)
            assertEquals(TimeRange(90.0, 100.0), result.preview)
            for (identity in listOf("7", "\"8\"", "null")) {
                body = markerResponse.replace("\"file_id\":\"7\"", "\"file_id\":$identity")
                assertEquals("invalid_response", assertIs<ApiResult.Error>(api.fileMarkers(7, owner)).error)
            }
            status = HttpStatusCode.NotFound
            body = """{"status":404,"code":"not_found","detail":"File is unavailable."}"""
            assertEquals(404, assertIs<ApiResult.Error>(api.fileMarkers(7, owner)).code)
            assertEquals(List(5) { "/api/v2/markers/files/7" }, paths)
        } finally {
            client.close()
        }
    }

    @Test
    fun staleOwnerIsRejectedBeforeRequestAndAfterResponse() = runTest {
        var sends = 0
        val client = HttpClient(MockEngine {
            sends++
            owner = owner.copy(profileId = "other-profile")
            respond(markerResponse, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = WatchDetailV2Api(client, tokens, ApiV2Gate.Unrestricted)
            val original = owner
            assertEquals("identity_changed", assertIs<ApiResult.Error>(api.fileMarkers(7, original)).error)
            assertEquals("identity_changed", assertIs<ApiResult.Error>(api.fileMarkers(7, original)).error)
            assertEquals(1, sends)
        } finally {
            client.close()
        }
    }
}
