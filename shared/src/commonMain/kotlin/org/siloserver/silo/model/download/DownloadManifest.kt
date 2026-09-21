package org.siloserver.silo.model.download

import org.siloserver.silo.model.catalog.PlaybackMarkerSegment
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.playback.legacyMarkerSegments
import org.siloserver.silo.playback.validMarkerSegments

/** Marker metadata retained with a managed download for offline playback. */
data class DownloadManifest(
    val downloadId: String,
    val mediaFileId: Int,
    val durationSeconds: Double = 0.0,
    val intro: TimeRange? = null,
    val credits: TimeRange? = null,
    val recap: TimeRange? = null,
    val preview: TimeRange? = null,
    val markerSegments: List<PlaybackMarkerSegment>? = null,
)

/** Null means no inventory was supplied; an empty list explicitly clears it. */
fun DownloadManifest.effectiveMarkerSegments(): List<PlaybackMarkerSegment>? =
    markerSegments?.let(::validMarkerSegments)
        ?: if (intro != null || credits != null || recap != null || preview != null) {
            legacyMarkerSegments(intro, credits, recap, preview)
        } else {
            null
        }
