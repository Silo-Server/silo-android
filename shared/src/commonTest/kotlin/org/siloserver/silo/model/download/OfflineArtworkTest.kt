package org.siloserver.silo.model.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineArtworkTest {

    @Test
    fun decodesAnEpisodeManifestWithASeriesPoster() {
        val artwork = assertNotNull(
            decodeOfflineManifestArtwork(
                """
                {
                  "download_id": "dl_1",
                  "audio_tracks": [{"index": 0, "language": "eng"}],
                  "poster_thumbhash": "EPISODE",
                  "backdrop_thumbhash": "BACKDROP",
                  "series_poster_thumbhash": "SERIES",
                  "artwork_urls": {
                    "poster": "/api/v2/downloads/dl_1/artwork/poster",
                    "backdrop": "/api/v2/downloads/dl_1/artwork/backdrop",
                    "logo": "/api/v2/downloads/dl_1/artwork/logo",
                    "series_poster": "/api/v2/downloads/dl_1/artwork/series_poster"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("EPISODE", artwork.posterThumbhash)
        assertEquals("SERIES", artwork.seriesPosterThumbhash)
        assertEquals("/api/v2/downloads/dl_1/artwork/poster", artwork.artworkUrls.poster)
        assertEquals("/api/v2/downloads/dl_1/artwork/series_poster", artwork.artworkUrls.seriesPoster)
    }

    @Test
    fun serversWithoutSeriesPostersDecodeWithoutThem() {
        val artwork = assertNotNull(
            decodeOfflineManifestArtwork(
                """{"poster_thumbhash": "MOVIE", "artwork_urls": {"poster": "/api/v2/downloads/dl_1/artwork/poster"}}""",
            ),
        )

        assertEquals("MOVIE", artwork.posterThumbhash)
        assertNull(artwork.seriesPosterThumbhash)
        assertNull(artwork.artworkUrls.seriesPoster)
    }

    @Test
    fun aManifestWithNoArtworkDecodesEmpty() {
        val artwork = assertNotNull(decodeOfflineManifestArtwork("""{"download_id": "dl_1", "artwork_urls": null}"""))

        assertNull(artwork.posterThumbhash)
        assertNull(artwork.artworkUrls.poster)
        assertNull(decodeOfflineManifestArtwork("not json"))
    }

    @Test
    fun onlySameServerArtworkProxyPathsForTheKindAreFetched() {
        assertTrue(isOfflineArtworkFetchUrl("/api/v2/downloads/dl_1/artwork/poster", OFFLINE_ARTWORK_POSTER))
        assertTrue(isOfflineArtworkFetchUrl(" /api/v2/downloads/dl_1/artwork/series_poster ", OFFLINE_ARTWORK_SERIES_POSTER))

        assertFalse(isOfflineArtworkFetchUrl("https://evil.example/api/v2/downloads/dl_1/artwork/poster", OFFLINE_ARTWORK_POSTER))
        assertFalse(isOfflineArtworkFetchUrl("//evil.example/api/v2/downloads/dl_1/artwork/poster", OFFLINE_ARTWORK_POSTER))
        assertFalse(isOfflineArtworkFetchUrl("/api/v2/downloads/dl_1/artwork/backdrop", OFFLINE_ARTWORK_POSTER))
        assertFalse(isOfflineArtworkFetchUrl("/api/v2/downloads/dl_1/artwork/poster", OFFLINE_ARTWORK_SERIES_POSTER))
        assertFalse(isOfflineArtworkFetchUrl("/api/v2/downloads/dl_1/subtitles/poster", OFFLINE_ARTWORK_POSTER))
        assertFalse(isOfflineArtworkFetchUrl("/api/v2/downloads/../users/artwork/poster", OFFLINE_ARTWORK_POSTER))
        assertFalse(isOfflineArtworkFetchUrl("/api/v2/downloads/a/b/artwork/poster", OFFLINE_ARTWORK_POSTER))
        assertFalse(isOfflineArtworkFetchUrl("/api/v2/downloads//artwork/poster", OFFLINE_ARTWORK_POSTER))
        assertFalse(isOfflineArtworkFetchUrl("/api/v2/downloads/dl_1/artwork/poster?x=1", OFFLINE_ARTWORK_POSTER))
        assertFalse(isOfflineArtworkFetchUrl("/api/v2/downloads/dl_1\\artwork\\poster", OFFLINE_ARTWORK_POSTER))
        assertFalse(isOfflineArtworkFetchUrl("", OFFLINE_ARTWORK_POSTER))
    }
}
