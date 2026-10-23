package org.siloserver.silo.common.downloads

import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadStatus
import androidx.work.WorkInfo
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

    @Test
    fun `only a system stop keeps the partial for the next attempt`() {
        for ((reason, keeps) in listOf(
            // User cancel (notification action, remove) or identity purge,
            // reported this way on every API level.
            WorkInfo.STOP_REASON_CANCELLED_BY_APP to false,
            // A cancellation that was not a WorkManager stop.
            WorkInfo.STOP_REASON_NOT_STOPPED to false,
            // A JobScheduler stop before Android 12 carries no reason; it is
            // retried, and a later removal deletes the slot.
            WorkInfo.STOP_REASON_UNKNOWN to true,
            // Background work time limit, lost network, quota: retried by WorkManager.
            WorkInfo.STOP_REASON_TIMEOUT to true,
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY to true,
            WorkInfo.STOP_REASON_QUOTA to true,
        )) {
            assertEquals(keeps, downloadStopKeepsPartial(reason), "stopReason=$reason")
        }
    }
}
