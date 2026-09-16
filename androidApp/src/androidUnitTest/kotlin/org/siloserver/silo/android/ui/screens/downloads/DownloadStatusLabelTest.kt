package org.siloserver.silo.android.ui.screens.downloads

import org.siloserver.silo.model.download.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadStatusLabelTest {

    @Test
    fun localCompletionIsReadyDespiteServerAdmissionStatus() {
        for (server in listOf(DownloadStatus.Ready, DownloadStatus.Queued)) {
            val status = downloadItemDisplayStatus(DownloadStatus.Completed, server)
            val state = downloadItemFileState(status, hasLocalMedia = true)
            val progress = downloadItemDisplayProgress(status, rawProgress = 0f, hasLocalMedia = true)
            assertEquals(true, state.isComplete)
            assertEquals(1f, progress)
            assertEquals("Ready", downloadStatusLabel(status, progress))
        }
    }

    @Test
    fun serverReadyCannotHideMissingCompletedLocalFile() {
        val status = downloadItemDisplayStatus(DownloadStatus.Completed, DownloadStatus.Ready)
        val state = downloadItemFileState(status, hasLocalMedia = false)
        assertEquals(false, state.isComplete)
        assertEquals("Missing file", downloadStatusLabel(status, 0f, isMissingLocal = state.isMissingLocal))
    }

    @Test
    fun incompleteLocalTransferStillUsesServerStatus() {
        assertEquals(DownloadStatus.Downloading, downloadItemDisplayStatus(DownloadStatus.Queued, DownloadStatus.Downloading))
        assertEquals(DownloadStatus.Queued, downloadItemDisplayStatus(DownloadStatus.Queued, null))
    }

    @Test
    fun queuedWithNoBytesShowsQueued() {
        assertEquals("Queued", downloadStatusLabel(DownloadStatus.Queued, progress = 0f))
    }

    @Test
    fun downloadingWithNoBytesShowsDownloading() {
        assertEquals("Downloading", downloadStatusLabel(DownloadStatus.Downloading, progress = 0f))
    }

    @Test
    fun downloadingWithProgressShowsPercentage() {
        assertEquals("Downloading · 42%", downloadStatusLabel(DownloadStatus.Downloading, progress = 0.42f))
    }

    @Test
    fun completedShowsReady() {
        assertEquals("Ready", downloadStatusLabel(DownloadStatus.Completed, progress = 1f))
    }

    @Test
    fun completedWithMissingLocalMediaShowsMissingFile() {
        assertEquals(
            "Missing file",
            downloadStatusLabel(
                status = DownloadStatus.Completed,
                progress = 1f,
                isMissingLocal = true,
            ),
        )
    }

    @Test
    fun completedWithoutLocalMediaIsNotCompleteAndNeedsAttention() {
        val state = downloadItemFileState(
            status = DownloadStatus.Completed,
            hasLocalMedia = false,
        )

        assertEquals(false, state.isComplete)
        assertEquals(true, state.isMissingLocal)
        assertEquals(true, state.isFailed)
    }

    @Test
    fun activeDownloadWithoutLocalMediaIsStillInProgress() {
        val state = downloadItemFileState(
            status = DownloadStatus.Downloading,
            hasLocalMedia = false,
        )

        assertEquals(false, state.isComplete)
        assertEquals(false, state.isMissingLocal)
        assertEquals(false, state.isFailed)
    }

    @Test
    fun completedMissingLocalMediaDoesNotContributeCompletedProgress() {
        assertEquals(
            0f,
            downloadItemDisplayProgress(
                status = DownloadStatus.Completed,
                rawProgress = 1f,
                hasLocalMedia = false,
            ),
        )
    }

    @Test
    fun activeAndReadyDownloadsKeepTheirProgress() {
        assertEquals(
            0.42f,
            downloadItemDisplayProgress(
                status = DownloadStatus.Downloading,
                rawProgress = 0.42f,
                hasLocalMedia = false,
            ),
        )
        assertEquals(
            1f,
            downloadItemDisplayProgress(
                status = DownloadStatus.Completed,
                rawProgress = 1f,
                hasLocalMedia = true,
            ),
        )
    }

    @Test
    fun failedShowsFailed() {
        assertEquals("Failed", downloadStatusLabel(DownloadStatus.Failed, progress = 0.18f))
    }

    @Test
    fun cancelledShowsCancelled() {
        assertEquals("Cancelled", downloadStatusLabel(DownloadStatus.Cancelled, progress = 0.18f))
    }

    @Test
    fun unknownShowsNeedsAttention() {
        assertEquals("Needs attention", downloadStatusLabel(DownloadStatus.Unknown, progress = 0f))
    }
}
