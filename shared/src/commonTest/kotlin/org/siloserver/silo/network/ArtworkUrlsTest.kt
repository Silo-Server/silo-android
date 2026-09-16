package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.safeApiV2Call
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ArtworkUrlsTest {
    private val signed = "/api/v2/artwork/posters/a%2Fb%20c%2b.jpg?exp=123&sig=a%2Bb%2F%3D&v=1&v=2"

    @Test
    fun signedPathAndQueryArePreservedAndBasePathIsDiscarded() {
        assertEquals("https://silo.test:8443$signed", resolveArtworkUrl(signed, "https://user:password@silo.test:8443/silo/base?ignored=yes"))
        assertEquals("http://[::1]:8096$signed", resolveArtworkUrl(signed, "http://[::1]:8096/silo"))
    }

    @Test
    fun absoluteS3AndLocalFilesRemainUnchanged() {
        listOf("https://bucket.s3.test/a%2Fb.jpg?X-Amz-Signature=a%2Bb", "file:///downloads/poster.jpg", "content://images/1", "", "//cdn.test/poster.jpg").forEach {
            assertEquals(it, resolveArtworkUrl(it, "https://silo.test/base"))
        }
    }

    @Test
    fun nestedArtworkAndGroupedPostersResolveWithoutChangingTmdbPathsOrOtherUrls() {
        val json = SiloJson.parseToJsonElement("""{"items":[{"poster_url":"$signed","backdrop_url":"$signed","logo_url":"$signed","photo_url":"$signed","still_url":"$signed","thumbnail_url":"$signed","avatar_url":"$signed","poster_urls":["$signed", "https://s3.test/p.jpg"],"poster_path":"/tmdb.jpg","backdrop_path":"/tmdb-backdrop.jpg","url":"/stream","poster_thumbhash":"unchanged"}]}""")
            .resolveArtworkUrls("https://a.test/base").jsonObject["items"]!!.jsonArray.single().jsonObject
        listOf("poster_url", "backdrop_url", "logo_url", "photo_url", "still_url", "thumbnail_url", "avatar_url").forEach {
            assertEquals("https://a.test$signed", json[it]!!.jsonPrimitive.content, it)
        }
        assertEquals(listOf("https://a.test$signed", "https://s3.test/p.jpg"), json["poster_urls"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("/tmdb.jpg", json["poster_path"]!!.jsonPrimitive.content)
        assertEquals("/tmdb-backdrop.jpg", json["backdrop_path"]!!.jsonPrimitive.content)
        assertEquals("/stream", json["url"]!!.jsonPrimitive.content)
        assertEquals("unchanged", json["poster_thumbhash"]!!.jsonPrimitive.content)
    }

    @Test
    fun lateResponseKeepsItsOriginAfterAnotherServerSuppliesTheSameKey() = runTest {
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "a.test") { aStarted.complete(Unit); releaseA.await() }
            respond("""{"content_id":"1","type":"movie","title":"Example","poster_url":"$signed","backdrop_url":"$signed"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        try {
            val a = async { safeApiV2Call<BrowseItem>(ApiV2Gate.Unrestricted) { client.get("https://a.test/base/api/v2/catalog") }.getOrThrow() }
            aStarted.await()
            val b = safeApiV2Call<BrowseItem>(ApiV2Gate.Unrestricted) { client.get("https://b.test/api/v2/catalog") }.getOrThrow()
            releaseA.complete(Unit)
            val old = a.await()
            assertEquals("https://a.test$signed", old.posterUrl)
            assertEquals("https://a.test$signed", old.backdropUrl)
            assertEquals("https://b.test$signed", b.posterUrl)
            assertNotEquals(old.posterUrl, b.posterUrl) // Coil's default cache keys cannot collide.
        } finally { client.close() }
    }
}
