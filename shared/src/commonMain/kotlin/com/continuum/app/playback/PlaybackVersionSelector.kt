package com.continuum.app.playback

import com.continuum.app.model.catalog.FileVersion

fun selectPlaybackVersion(
    versions: List<FileVersion>,
    lastFileId: Int?,
    preferredQuality: String?,
): FileVersion {
    require(versions.isNotEmpty()) { "versions must not be empty" }

    if (lastFileId != null) {
        versions.firstOrNull { it.fileId == lastFileId }?.let { return it }
    }

    val target = preferredQuality?.trim()?.lowercase().orEmpty()
    if (target.isBlank() || target == "auto" || target == "original") {
        return versions.bestAvailable()
    }

    val preferredRank = playbackResolutionRank(target)
    return versions
        .sortedWith(
            compareByDescending<FileVersion> { playbackResolutionRank(it.resolution) }
                .thenByDescending { it.bitrate }
                .thenByDescending { it.fileSize },
        )
        .firstOrNull { playbackResolutionRank(it.resolution) <= preferredRank }
        ?: versions.bestAvailable()
}

fun playbackResolutionRank(value: String?): Int {
    val normalized = value?.lowercase().orEmpty()
    return when {
        normalized.contains("4320") || normalized.contains("8k") -> 4320
        normalized.contains("2160") || normalized.contains("4k") || normalized.contains("uhd") -> 2160
        normalized.contains("1440") || normalized.contains("qhd") -> 1440
        normalized.contains("1080") || normalized.contains("fhd") -> 1080
        normalized.contains("720") || normalized.contains("hd") -> 720
        normalized.contains("576") -> 576
        normalized.contains("480") || normalized.contains("sd") -> 480
        else -> 0
    }
}

private fun List<FileVersion>.bestAvailable(): FileVersion {
    return maxWithOrNull(
        compareBy<FileVersion> { playbackResolutionRank(it.resolution) }
            .thenBy { it.bitrate }
            .thenBy { it.fileSize },
    ) ?: first()
}
