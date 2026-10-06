package org.siloserver.silo.common.downloads

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadSidecar
import org.siloserver.silo.model.download.OfflineSubtitleFile
import org.siloserver.silo.model.download.OfflineTrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadSidecarOfflineTracksMappingTest {

    private val sidecar = DownloadSidecar(
        record = DownloadRecord(
            id = "dl_1",
            contentId = "mv_1",
            mediaFileId = 42,
            kind = "queued",
            status = "completed",
            createdAt = "2026-09-29T00:00:00Z",
        ),
        title = "Example",
        updatedAtMs = 1L,
    )

    @Test
    fun offlineTracksSurviveTheRoomRow() {
        val tracks = OfflineTrackInfo(
            audioTracks = listOf(
                AudioTrack(index = 0, language = "eng", codec = "aac", channels = 2, isDefault = true),
                AudioTrack(index = 1, language = "jpn", codec = "aac", channels = 2),
            ),
            selectedAudioTrackIndex = 1,
            subtitles = listOf(
                OfflineSubtitleFile(path = "/data/subs/0.ass", format = "ass", language = "eng", forced = true),
            ),
        )

        val restored = sidecar.copy(offlineTracks = tracks).toEntity("srv", "prof").toSidecar()

        assertEquals(tracks, restored.offlineTracks)
    }

    @Test
    fun rowsWithoutOfflineTracksStillLoad() {
        val entity = sidecar.toEntity("srv", "prof")

        assertNull(entity.offlineTracksJson)
        assertNull(entity.toSidecar().offlineTracks)
        assertNull(entity.copy(offlineTracksJson = "{not json").toSidecar().offlineTracks)
    }
}
