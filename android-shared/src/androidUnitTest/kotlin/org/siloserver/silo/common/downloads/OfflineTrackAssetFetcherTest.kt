package org.siloserver.silo.common.downloads

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineTrackAssetFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manifest = """
        {
          "download_id": "dl_1",
          "selected_audio_track_index": 1,
          "audio_tracks": [
            {"index": 0, "language": "eng", "codec": "aac", "channels": 2, "default": true},
            {"index": 1, "language": "jpn", "codec": "aac", "channels": 2}
          ],
          "subtitles": [
            {"language": "eng", "format": "ass", "fetch_url": "/api/v2/downloads/dl_1/subtitles/embedded:2"},
            {"language": "fre", "format": "srt", "external": true, "fetch_url": "/api/v2/downloads/dl_1/subtitles/external:0"},
            {"language": "eng", "format": "sup", "forced": true, "fetch_url": "/api/v2/downloads/dl_1/subtitles/embedded:4"},
            {"language": "ger", "format": "sub", "fetch_url": "/api/v2/downloads/dl_1/subtitles/external:1"},
            {"language": "spa", "format": "srt", "fetch_url": "https://elsewhere.example/sub.srt"}
          ],
          "poster_thumbhash": "EPISODE",
          "series_poster_thumbhash": "SERIES",
          "artwork_urls": {
            "poster": "/api/v2/downloads/dl_1/artwork/poster",
            "backdrop": "/api/v2/downloads/dl_1/artwork/backdrop",
            "series_poster": "/api/v2/downloads/dl_1/artwork/series_poster"
          }
        }
    """.trimIndent()

    /** A movie manifest, or an episode manifest from a server without series posters. */
    private val movieManifest = """
        {
          "download_id": "dl_1",
          "poster_thumbhash": "MOVIE",
          "artwork_urls": {"poster": "/api/v2/downloads/dl_1/artwork/poster"}
        }
    """.trimIndent()

    private val posterBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
    private val seriesPosterBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private val requested = mutableListOf<String>()

    private fun client(
        manifestStatus: HttpStatusCode = HttpStatusCode.OK,
        transientManifestFailures: Int = 0,
        manifestBody: String = manifest,
        artwork: (kind: String) -> Pair<HttpStatusCode, ByteArray> = { kind ->
            when (kind) {
                "poster" -> HttpStatusCode.OK to posterBytes
                "series_poster" -> HttpStatusCode.OK to seriesPosterBytes
                else -> HttpStatusCode.NotFound to ByteArray(0)
            }
        },
    ) = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath
            requested += path
            when {
                path.endsWith("/manifest") && requested.count { it.endsWith("/manifest") } <= transientManifestFailures ->
                    respond("", HttpStatusCode.ServiceUnavailable)
                path.endsWith("/manifest") -> respond(manifestBody, manifestStatus)
                path.contains("/artwork/") -> {
                    val (status, bytes) = artwork(path.substringAfterLast('/'))
                    respond(bytes, status)
                }
                path.endsWith("/subtitles/embedded:2") -> respond("[Script Info]\nTitle: x\n")
                // The server failed to read the external sidecar.
                path.endsWith("/subtitles/external:0") -> respond("", HttpStatusCode.NotFound)
                path.endsWith("/subtitles/embedded:4") -> respond(byteArrayOf(0x50, 0x47, 0x00, 0x01))
                else -> respond("", HttpStatusCode.InternalServerError)
            }
        },
    ) {
        install(HttpTimeout)
        defaultRequest { url("https://silo.example/") }
    }

    /**
     * A sidecar next to the media is served with its timing correction
     * applied. When the server retimes it after the download, the manifest
     * lists it at a new revision and a refresh replaces the saved file.
     */
    @Test
    fun refreshesASavedExternalSidecarTheServerRetimed() = runBlocking {
        var revision = "r1"
        var cue = "00:00:42,148 --> 00:00:44,517"
        val subtitleFetches = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/manifest") -> respond(
                        """{"subtitles": [{"language": "en", "format": "srt", "external": true,
                           "fetch_url": "/api/v2/downloads/dl_1/subtitles/external:0", "revision": "$revision"}]}""",
                        HttpStatusCode.OK,
                    )
                    path.endsWith("/subtitles/external:0") -> {
                        subtitleFetches += revision
                        respond("1\n$cue\nFor a moment, everyone was silent.\n")
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ) {
            install(HttpTimeout)
            defaultRequest { url("https://silo.example/") }
        }
        val fetcher = OfflineTrackAssetFetcher(http, DownloadStorage(tmp.newFolder("filesDir")))
        val saved = assertNotNull(fetcher.fetch("dl_1", "srv", "prof", 42) {}).tracks
        assertEquals("r1", saved.subtitles.single().revision)

        // Nothing changed on the server: nothing is fetched.
        assertNull(fetcher.refreshSubtitles("dl_1", saved) {})
        assertEquals(listOf("r1"), subtitleFetches)

        revision = "r2"
        cue = "00:00:39,138 --> 00:00:41,507"
        val refreshed = assertNotNull(fetcher.refreshSubtitles("dl_1", saved) {})
        assertEquals("r2", refreshed.subtitles.single().revision)
        assertEquals(saved.subtitles.single().path, refreshed.subtitles.single().path)
        assertTrue(File(refreshed.subtitles.single().path).readText().contains(cue))
        http.close()
    }

    @Test
    fun capturesAudioTracksAndSavesEveryFetchableSidecar() = runBlocking {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))

        val info = assertNotNull(
            OfflineTrackAssetFetcher(client(), storage).fetch("dl_1", "srv", "prof", 42) {},
        ).tracks

        assertEquals(listOf("eng", "jpn"), info.audioTracks.map { it.language })
        assertEquals(1, info.defaultAudioPosition())
        // The failed SRT, the unmountable .sub and the foreign URL are skipped
        // without failing the rest.
        assertEquals(listOf("ass", "pgs"), info.subtitles.map { it.format })
        assertEquals(listOf(false, true), info.subtitles.map { it.forced })
        val ass = File(info.subtitles[0].path)
        val pgs = File(info.subtitles[1].path)
        assertTrue(ass.isFile && ass.name.endsWith(".ass"))
        assertTrue(pgs.isFile && pgs.name.endsWith(".sup"))
        assertEquals(storage.offlineSubtitleDirectory("srv", "prof", 42), ass.parentFile)
        assertFalse(requested.any { it.contains("sub.srt") })
        assertTrue(ass.parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun deletingTheDownloadRemovesItsSidecars() = runBlocking {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))
        val assets = assertNotNull(
            OfflineTrackAssetFetcher(client(), storage).fetch("dl_1", "srv", "prof", 42) {},
        )
        val artworkDirectory = assertNotNull(storage.offlineArtworkDirectory("srv", "prof", 42))
        assertTrue(artworkDirectory.isDirectory)

        storage.delete("srv", "prof", 42)

        assertTrue(assets.tracks.subtitles.none { File(it.path).exists() })
        assertFalse(artworkDirectory.exists())
    }

    @Test
    fun aMissingManifestCapturesNothing() = runBlocking {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))

        assertNull(
            OfflineTrackAssetFetcher(client(HttpStatusCode.NotFound), storage).fetch("dl_1", "srv", "prof", 42) {},
        )
        assertEquals(listOf("/api/v2/downloads/dl_1/manifest"), requested)
    }

    @Test
    fun aTransientManifestFailureIsRetried() = runBlocking {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))

        val info = assertNotNull(
            OfflineTrackAssetFetcher(client(transientManifestFailures = 2), storage, manifestRetryDelayMs = 0)
                .fetch("dl_1", "srv", "prof", 42) {},
        ).tracks

        assertEquals(3, requested.count { it.endsWith("/manifest") })
        assertEquals(2, info.audioTracks.size)
    }

    @Test
    fun savesThePosterAndSeriesPosterFromTheSameManifest() = runBlocking {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))

        val artwork = assertNotNull(
            OfflineTrackAssetFetcher(client(), storage).fetch("dl_1", "srv", "prof", 42) {},
        ).artwork

        val poster = File(assertNotNull(artwork.posterPath))
        val seriesPoster = File(assertNotNull(artwork.seriesPosterPath))
        assertContentEquals(posterBytes, poster.readBytes())
        assertContentEquals(seriesPosterBytes, seriesPoster.readBytes())
        val directory = storage.offlineArtworkDirectory("srv", "prof", 42)
        assertEquals(directory, poster.parentFile)
        assertEquals(directory, seriesPoster.parentFile)
        assertTrue(directory!!.listFiles()!!.none { it.name.endsWith(".part") })
        assertEquals("EPISODE", artwork.posterThumbhash)
        assertEquals("SERIES", artwork.seriesPosterThumbhash)
        // One manifest request serves tracks and artwork; the backdrop is not fetched.
        assertEquals(1, requested.count { it.endsWith("/manifest") })
        assertEquals(
            listOf("/api/v2/downloads/dl_1/artwork/poster", "/api/v2/downloads/dl_1/artwork/series_poster"),
            requested.filter { it.contains("/artwork/") },
        )
    }

    @Test
    fun aMissingSeriesPosterIsSkipped() = runBlocking {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))

        val artwork = assertNotNull(
            OfflineTrackAssetFetcher(client(manifestBody = movieManifest), storage).fetch("dl_1", "srv", "prof", 42) {},
        ).artwork

        assertNotNull(artwork.posterPath)
        assertNull(artwork.seriesPosterPath)
        assertEquals("MOVIE", artwork.posterThumbhash)
        assertNull(artwork.seriesPosterThumbhash)
        assertEquals(listOf("/api/v2/downloads/dl_1/artwork/poster"), requested.filter { it.contains("/artwork/") })
    }

    @Test
    fun aFailedArtworkFetchKeepsTheRestOfTheCapture() = runBlocking {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))
        val client = client(
            artwork = { kind ->
                if (kind == "poster") HttpStatusCode.NotFound to ByteArray(0) else HttpStatusCode.OK to seriesPosterBytes
            },
        )

        val assets = assertNotNull(OfflineTrackAssetFetcher(client, storage).fetch("dl_1", "srv", "prof", 42) {})

        assertNull(assets.artwork.posterPath)
        assertNotNull(assets.artwork.seriesPosterPath)
        assertEquals(2, assets.tracks.audioTracks.size)
        assertEquals(2, assets.tracks.subtitles.size)
        val directory = storage.offlineArtworkDirectory("srv", "prof", 42)!!
        assertFalse(File(directory, "poster").exists())
        assertFalse(File(directory, "poster.part").exists())
    }

    @Test
    fun oversizedArtworkIsDiscarded() = runBlocking {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))
        val tooLarge = ByteArray((OfflineTrackAssetFetcher.MAX_ARTWORK_BYTES + 1).toInt())
        val client = client(
            manifestBody = movieManifest,
            artwork = { HttpStatusCode.OK to tooLarge },
        )

        val artwork = assertNotNull(OfflineTrackAssetFetcher(client, storage).fetch("dl_1", "srv", "prof", 42) {}).artwork

        assertNull(artwork.posterPath)
        val directory = storage.offlineArtworkDirectory("srv", "prof", 42)!!
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun foreignArtworkUrlsAreNotFollowed() = runBlocking {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))
        val foreign = """
            {"artwork_urls": {
              "poster": "https://elsewhere.example/poster.jpg",
              "series_poster": "/api/v2/downloads/dl_1/artwork/poster"
            }}
        """.trimIndent()

        val artwork = assertNotNull(
            OfflineTrackAssetFetcher(client(manifestBody = foreign), storage).fetch("dl_1", "srv", "prof", 42) {},
        ).artwork

        assertNull(artwork.posterPath)
        // A series_poster URL that names a different kind is rejected too.
        assertNull(artwork.seriesPosterPath)
        assertTrue(requested.none { it.contains("/artwork/") || it.contains("poster.jpg") })
    }

    @Test
    fun artworkRedirectsAreNotFollowed() = runBlocking {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))
        val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                requested += path
                when {
                    path.endsWith("/manifest") -> respond(movieManifest)
                    path.contains("/artwork/") -> respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://elsewhere.example/poster.jpg"),
                    )
                    else -> respond(posterBytes)
                }
            },
        ) {
            install(HttpTimeout)
            defaultRequest { url("https://silo.example/") }
        }

        val artwork = assertNotNull(OfflineTrackAssetFetcher(client, storage).fetch("dl_1", "srv", "prof", 42) {}).artwork

        assertNull(artwork.posterPath)
        assertTrue(requested.none { it.contains("poster.jpg") })
    }

    @Test
    fun aRecaptureReplacesThePreviousArtwork() = runBlocking {
        val storage = DownloadStorage(tmp.newFolder("filesDir"))
        OfflineTrackAssetFetcher(client(), storage).fetch("dl_1", "srv", "prof", 42) {}

        val artwork = assertNotNull(
            OfflineTrackAssetFetcher(client(manifestBody = movieManifest), storage).fetch("dl_1", "srv", "prof", 42) {},
        ).artwork

        assertNull(artwork.seriesPosterPath)
        val directory = storage.offlineArtworkDirectory("srv", "prof", 42)!!
        assertEquals(listOf("poster"), directory.listFiles()!!.map { it.name })
    }

    @Test
    fun onlyVideoDownloadsCaptureTracks() {
        assertTrue(OfflineTrackAssetFetcher.appliesTo("movie"))
        assertTrue(OfflineTrackAssetFetcher.appliesTo("tv"))
        assertFalse(OfflineTrackAssetFetcher.appliesTo("audiobook"))
        assertFalse(OfflineTrackAssetFetcher.appliesTo("ebook"))
    }
}
