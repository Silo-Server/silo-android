package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO
import org.siloserver.silo.model.playback.videoQualityPreference

fun selectPlaybackVersion(
    versions: List<FileVersion>,
    lastFileId: Int?,
    preferredQuality: String?,
): FileVersion {
    require(versions.isNotEmpty()) { "versions must not be empty" }

    if (lastFileId != null) {
        versions.firstOrNull { it.fileId == lastFileId }?.let { return it }
    }

    val target = videoQualityPreference(preferredQuality) ?: PLAYBACK_QUALITY_AUTO
    if (target == PLAYBACK_QUALITY_AUTO) {
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
    val normalized = value?.trim()?.lowercase().orEmpty()
    val numericHeight = RESOLUTION_HEIGHT_REGEX
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    return when {
        numericHeight != null -> numericHeight
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

private val RESOLUTION_HEIGHT_REGEX = Regex("""\b(\d{3,4})(?:p|i)?\b""")
