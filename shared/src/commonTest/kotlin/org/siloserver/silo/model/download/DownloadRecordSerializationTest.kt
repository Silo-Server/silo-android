package org.siloserver.silo.model.download

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadRecordSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `DownloadRecord round-trips full server shape`() {
        val source = """
            {
              "id": "dl_abc123",
              "content_id": "tt0816692",
              "episode_id": "tt6470762",
              "batch_id": "batch_xyz",
              "media_file_id": 2390488,
              "file_size": 5368709120,
              "bytes_sent": 1342177280,
              "kind": "queued",
              "status": "downloading",
              "created_at": "2026-05-24T01:02:03Z",
              "completed_at": null
            }
        """.trimIndent()

        val r = json.decodeFromString<DownloadRecord>(source)
        assertEquals("dl_abc123", r.id)
        assertEquals("tt0816692", r.contentId)
        assertEquals("tt6470762", r.episodeId)
        assertEquals("batch_xyz", r.batchId)
        assertEquals(2390488, r.mediaFileId)
        assertEquals(5_368_709_120L, r.fileSize)
        assertEquals(1_342_177_280L, r.bytesSent)
        assertEquals(DownloadKind.Queued, r.kindEnum())
        assertEquals(DownloadStatus.Downloading, r.statusEnum())
        assertEquals("2026-05-24T01:02:03Z", r.createdAt)
        assertNull(r.completedAt)
    }

    @Test
    fun `DownloadRecord decodes when optional fields are absent`() {
        val source = """
            {
              "id": "dl_xyz",
              "content_id": "tt12345",
              "media_file_id": 1,
              "file_size": 1024,
              "bytes_sent": 0,
              "kind": "queued",
              "status": "queued",
              "created_at": "2026-05-24T00:00:00Z"
            }
        """.trimIndent()

        val r = json.decodeFromString<DownloadRecord>(source)
        assertNull(r.episodeId)
        assertNull(r.batchId)
        assertNull(r.completedAt)
        assertEquals(DownloadStatus.Queued, r.statusEnum())
        // Quality fields are absent on older servers / pre-quality sidecars.
        assertNull(r.quality)
        assertNull(r.effectiveQuality)
        assertNull(r.deliveryFormat)
        assertNull(r.targetBitrateKbps)
    }

    @Test
    fun `DownloadRecord round-trips quality fields`() {
        val source = """
            {
              "id": "dl_q",
              "content_id": "tt1",
              "media_file_id": 9,
              "file_size": 1024,
              "bytes_sent": 0,
              "kind": "queued",
              "status": "preparing",
              "created_at": "2026-07-01T00:00:00Z",
              "quality": "5mbps",
              "effective_quality": "2mbps",
              "delivery_format": "mp4",
              "target_bitrate_kbps": 2000
            }
        """.trimIndent()

        val r = json.decodeFromString<DownloadRecord>(source)
        assertEquals("5mbps", r.quality)
        assertEquals("2mbps", r.effectiveQuality)
        assertEquals("mp4", r.deliveryFormat)
        assertEquals(2000, r.targetBitrateKbps)
        assertEquals(DownloadStatus.Preparing, r.statusEnum())

        val reencoded = json.decodeFromString<DownloadRecord>(json.encodeToString(DownloadRecord.serializer(), r))
        assertEquals(r, reencoded)
    }

    @Test
    fun `DownloadStatus fromWire is lenient on unknown values`() {
        // A newer server adds a status the client doesn't know about — the
        // record must still decode, mapping to Unknown for downstream
        // ignore/dead-letter handling.
        val source = """
            {
              "id": "dl_future",
              "content_id": "tt1",
              "media_file_id": 1,
              "file_size": 1,
              "bytes_sent": 0,
              "kind": "queued",
              "status": "paused_by_quota",
              "created_at": "2026-05-24T00:00:00Z"
            }
        """.trimIndent()

        val r = json.decodeFromString<DownloadRecord>(source)
        assertEquals(DownloadStatus.Unknown, r.statusEnum())
        assertEquals("paused_by_quota", r.status)
    }
}
