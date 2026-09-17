package org.siloserver.silo.model.catalog

import kotlin.math.roundToInt

/** Runtime for the media file that the detail screen will actually play. */
fun selectedMediaRuntimeMinutes(
    detail: ItemDetail,
    selectedVersion: FileVersion?,
): Int {
    val selectedFileId = selectedVersion?.fileId
    val selectedVariant = selectedFileId?.let { fileId ->
        detail.playbackVariants.firstOrNull { variant ->
            variant.parts.any { part -> part.versions.any { it.fileId == fileId } }
        }
    }
    val isMultipart = selectedVariant != null &&
        (selectedVariant.partCount > 1 || selectedVariant.parts.size > 1)
    val seconds = if (isMultipart) {
        selectedVariant.totalDuration?.takeIf { it.isFinite() && it > 0.0 }
    } else {
        selectedVersion?.duration?.takeIf { it.isFinite() && it > 0.0 }
    }

    return seconds?.div(60.0)?.roundToInt() ?: detail.runtime
}
