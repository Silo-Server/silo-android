package org.siloserver.silo.common.downloads

import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadWorkerProgressTest {
    private val record = DownloadRecord(
        id = "download-1",
        contentId = "content-1",
        mediaFileId = 7,
        fileSize = 10_000,
        bytesSent = 4_000,
        kind = "download",
        status = DownloadStatus.Downloading.wire,
        createdAt = "2026-07-28T00:00:00Z",
    )

    @Test
    fun `permanent failure explicitly clears stale progress`() {
        val failed = record.withWorkerStatus(
            status = DownloadStatus.Failed.wire,
            bytesSent = 0,
            fileSize = 0,
        )

        assertEquals(DownloadStatus.Failed.wire, failed.status)
        assertEquals(0, failed.bytesSent)
        assertEquals(0, failed.fileSize)
    }

    @Test
    fun `omitted progress preserves resume state`() {
        val downloading = record.withWorkerStatus(
            status = DownloadStatus.Downloading.wire,
        )

        assertEquals(4_000, downloading.bytesSent)
        assertEquals(10_000, downloading.fileSize)
    }
}
