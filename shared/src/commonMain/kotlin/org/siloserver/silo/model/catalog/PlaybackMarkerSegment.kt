package org.siloserver.silo.model.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackMarkerSegment(
    val kind: String,
    @SerialName("start_seconds") val startSeconds: Double,
    @SerialName("end_seconds") val endSeconds: Double,
) {
    val range: TimeRange get() = TimeRange(startSeconds, endSeconds)
}
