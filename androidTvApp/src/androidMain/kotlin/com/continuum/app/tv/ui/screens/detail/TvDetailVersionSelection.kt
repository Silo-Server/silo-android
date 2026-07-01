package com.continuum.app.tv.ui.screens.detail

import com.continuum.app.model.catalog.FileVersion
import com.continuum.app.playback.selectPlaybackVersion

internal fun selectTvDetailDisplayVersion(
    versions: List<FileVersion>,
    selectedFileId: Int?,
    lastFileId: Int?,
    preferredQuality: String?,
): FileVersion? {
    if (versions.isEmpty()) return null
    if (selectedFileId != null) {
        versions.firstOrNull { it.fileId == selectedFileId }?.let { return it }
    }
    return selectPlaybackVersion(
        versions = versions,
        lastFileId = lastFileId,
        preferredQuality = preferredQuality,
    )
}
