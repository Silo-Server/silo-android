package org.siloserver.silo.android.ui.screens.detail

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadStatus
import org.siloserver.silo.model.download.statusEnum

data class DetailDownloadState(
    val isDownloaded: Boolean = false,
    val progress: Float? = null,
    val needsLocalRecovery: Boolean = false,
)

internal enum class DetailDownloadTapAction {
    Start,
    Cancel,
    Ignore,
    ReplaceAndStart,
}

internal fun detailDownloadStateFor(
    version: FileVersion?,
    records: List<DownloadRecord>,
    hasLocalMedia: Boolean? = null,
): DetailDownloadState = detailDownloadStateForFile(version?.fileId, records, hasLocalMedia)

/**
 * Download state for a specific media file id — used for per-episode indicators
 * in the episode list (the episode's downloadable file), mirroring the movie /
 * audiobook path which keys off [FileVersion.fileId].
 */
internal fun detailDownloadStateForFile(
    fileId: Int?,
    records: List<DownloadRecord>,
    hasLocalMedia: Boolean? = null,
): DetailDownloadState {
    val record = fileId?.let { f -> records.firstOrNull { it.mediaFileId == f } }
    val status = record?.statusEnum()
    val progress = record
        ?.takeIf {
            status == DownloadStatus.Downloading ||
                status == DownloadStatus.Queued ||
                status == DownloadStatus.Preparing ||
                status == DownloadStatus.Ready
        }
        ?.let { rec ->
            if (rec.fileSize > 0) {
                (rec.bytesSent.toFloat() / rec.fileSize.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    val isCompleted = status == DownloadStatus.Completed
    val isMissingLocal = isCompleted && hasLocalMedia == false
    return DetailDownloadState(
        isDownloaded = isCompleted && !isMissingLocal,
        progress = progress,
        needsLocalRecovery = isMissingLocal,
    )
}

internal fun detailDownloadTapAction(
    status: DownloadStatus?,
    forceRedownloadMissingLocal: Boolean,
): DetailDownloadTapAction = when (status) {
    DownloadStatus.Queued,
    // Preparing (server transcoding) and Ready (artifact serveable, GET not
    // started) are in-flight: cancellable like Queued.
    DownloadStatus.Preparing,
    DownloadStatus.Ready,
    DownloadStatus.Downloading,
    -> DetailDownloadTapAction.Cancel
    DownloadStatus.Completed -> {
        if (forceRedownloadMissingLocal) {
            DetailDownloadTapAction.ReplaceAndStart
        } else {
            DetailDownloadTapAction.Ignore
        }
    }
    else -> DetailDownloadTapAction.Start
}
