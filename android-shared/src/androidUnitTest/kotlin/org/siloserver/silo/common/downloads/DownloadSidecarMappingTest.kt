package org.siloserver.silo.common.downloads

import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadSidecar
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadSidecarMappingTest {
    private fun sidecar(contentId: String, episodeId: String?) = DownloadSidecar(
        record = DownloadRecord(
            id = "dl-1",
            contentId = contentId,
            episodeId = episodeId,
            mediaFileId = 7,
            kind = "queued",
            status = "ready",
            createdAt = "2026-09-28T10:00:00Z",
        ),
        title = "Show",
        seriesContentId = "series-1",
        updatedAtMs = 0L,
    )

    @Test
    fun `an episode is stored under the episode it plays, not its series`() {
        // The server keys the entry by series + episode; offline playback and
        // the Downloads list look it up by the episode.
        val entity = sidecar(contentId = "series-1", episodeId = "episode-2").toEntity("server", "profile")

        assertEquals("episode-2", entity.contentId)
        assertEquals("series-1", entity.seriesContentId)
    }

    @Test
    fun `a movie keeps its own content id`() {
        val entity = sidecar(contentId = "movie-1", episodeId = null).toEntity("server", "profile")

        assertEquals("movie-1", entity.contentId)
    }
}
