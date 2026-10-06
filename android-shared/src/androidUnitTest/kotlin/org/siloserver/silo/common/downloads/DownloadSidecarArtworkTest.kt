package org.siloserver.silo.common.downloads

import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadSidecar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadSidecarArtworkTest {

    private val sidecar = DownloadSidecar(
        record = DownloadRecord(
            id = "dl_1",
            contentId = "sr_1",
            mediaFileId = 42,
            kind = "queued",
            status = "completed",
            createdAt = "2026-09-29T00:00:00Z",
        ),
        title = "Example",
        posterUrl = "https://silo.example/api/v2/artwork/poster",
        posterThumbhash = "EPISODE",
        updatedAtMs = 1L,
    )

    @Test
    fun savedArtworkSurvivesTheRoomRow() {
        val withArtwork = sidecar.copy(
            offlinePosterPath = "/data/download-assets/srv/prof/42/artwork/poster",
            offlineSeriesPosterPath = "/data/download-assets/srv/prof/42/artwork/series_poster",
            seriesPosterThumbhash = "SERIES",
        )

        val entity = withArtwork.toEntity("srv", "prof")
        assertEquals("/data/download-assets/srv/prof/42/artwork/poster", entity.offlinePosterPath)
        assertEquals("/data/download-assets/srv/prof/42/artwork/series_poster", entity.offlineSeriesPosterPath)
        assertEquals("SERIES", entity.seriesPosterThumbhash)
        assertEquals(withArtwork, entity.toSidecar())
    }

    @Test
    fun rowsWithoutArtworkStillLoad() {
        val restored = sidecar.toEntity("srv", "prof").toSidecar()

        assertNull(restored.offlinePosterPath)
        assertNull(restored.offlineSeriesPosterPath)
        assertNull(restored.seriesPosterThumbhash)
    }

    @Test
    fun movieTilesUseTheSavedPosterThenTheCatalogUrl() {
        val movie = sidecar.copy(mediaType = "movie", posterThumbhash = "MOVIE", offlinePosterPath = "/art/poster")

        assertEquals(DownloadTileArtwork("file:///art/poster", "MOVIE"), movie.tileArtwork { true })
        // A file removed behind the app's back falls back to the catalog URL.
        assertEquals(
            DownloadTileArtwork("https://silo.example/api/v2/artwork/poster", "MOVIE"),
            movie.tileArtwork { false },
        )
    }

    @Test
    fun episodeTilesUseTheSavedSeriesPosterButNeverTheEpisodeStill() {
        val episode = sidecar.copy(
            mediaType = "tv",
            posterThumbhash = null,
            offlinePosterPath = "/art/poster",
            offlineSeriesPosterPath = "/art/series_poster",
            seriesPosterThumbhash = "SERIES",
        )

        assertEquals(DownloadTileArtwork("file:///art/series_poster", "SERIES"), episode.tileArtwork { true })
        // The saved still exists, but the tile falls back to the catalog series poster.
        assertEquals(
            DownloadTileArtwork("https://silo.example/api/v2/artwork/poster", "SERIES"),
            episode.tileArtwork { it == "/art/poster" },
        )
        // Rows from before mediaType was stored count as episodes by series title.
        val legacy = episode.copy(mediaType = "", seriesTitle = "Show", offlineSeriesPosterPath = null)
        assertEquals(
            DownloadTileArtwork("https://silo.example/api/v2/artwork/poster", "SERIES"),
            legacy.tileArtwork { true },
        )
    }

    @Test
    fun groupTilesUseAnyMembersSavedSeriesPoster() {
        val first = sidecar.copy(mediaType = "tv", offlinePosterPath = "/art/1/poster")
        val second = sidecar.copy(
            mediaType = "tv",
            offlinePosterPath = "/art/2/poster",
            offlineSeriesPosterPath = "/art/2/series_poster",
            seriesPosterThumbhash = "SERIES",
        )

        assertEquals(
            DownloadTileArtwork("file:///art/2/series_poster", "SERIES"),
            listOf(first, second).tileArtwork { true },
        )
        // No series poster on disk: the catalog URL, not an episode still.
        assertEquals(
            DownloadTileArtwork("https://silo.example/api/v2/artwork/poster", "SERIES"),
            listOf(first, second).tileArtwork { !it.endsWith("series_poster") },
        )
    }
}
