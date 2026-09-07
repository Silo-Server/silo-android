package org.siloserver.silo.network.apiv2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.audiobook.*
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.network.DurableLoginAuthority
import kotlin.math.abs

const val BOUND_CLIENT_TIMELINE_FEATURE = "bound_client_timeline"

@Serializable
data class PlaybackManifestPartV2(
    @SerialName("file_id") val fileId: String,
    @SerialName("offset_seconds") val offsetSeconds: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
)

@Serializable
data class PlaybackManifestV2(
    @SerialName("installation_id") val installationId: String,
    @SerialName("timeline_id") val timelineId: String,
    @SerialName("media_item_id") val mediaItemId: String,
    @SerialName("edition_id") val editionId: String,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val parts: List<PlaybackManifestPartV2>,
) {
    fun validate() {
        require(installationId.isNotBlank() && timelineId.matches(Regex("[0-9a-f]{64}")))
        require(mediaItemId.isNotBlank() && editionId.isNotBlank() && parts.size in 1..4096)
        require(durationSeconds.isFinite() && durationSeconds > 0)
        require(parts.map { it.fileId }.toSet().size == parts.size)
        var offset = 0.0
        parts.forEach {
            require(it.fileId.toIntOrNull()?.let { id -> id > 0 && id.toString() == it.fileId } == true)
            require(it.offsetSeconds == offset && it.durationSeconds.isFinite() && it.durationSeconds > 0)
            val end = offset + it.durationSeconds
            require(end.isFinite() && end > offset)
            offset = end
        }
        require(offset == durationSeconds)
    }
    fun select(fileId: Int): PlaybackProgressTimelineV2 {
        validate()
        val part = requireNotNull(parts.singleOrNull { it.fileId == fileId.toString() })
        return PlaybackProgressTimelineV2(timelineId, mediaItemId, part.fileId, part.offsetSeconds, part.durationSeconds, durationSeconds)
    }
    /** Detail chapters are labels within trusted spans; they cannot change part selection or duration. */
    fun audiobookTimeline(versions: List<FileVersion>): AudiobookTimeline {
        validate()
        val tracks = parts.mapIndexed { index, part -> AudioPlaybackTrack(index, part.fileId.toInt(), part.durationSeconds, part.offsetSeconds) }
        val chapters = tracks.flatMap { track ->
            versions.firstOrNull { it.fileId == track.fileId }?.chapters.orEmpty()
                .filter { it.startSeconds.isFinite() && it.endSeconds.isFinite() && it.startSeconds >= 0 &&
                    it.endSeconds >= it.startSeconds && it.endSeconds <= track.durationSeconds }
                .map { AudioPlaybackChapter(0, it.title, track.startOffsetSeconds + it.startSeconds,
                    track.startOffsetSeconds + it.endSeconds, track.index) }
        }.sortedBy { it.startSeconds }.mapIndexed { index, chapter -> chapter.copy(index = index) }
        return AudiobookTimeline(tracks, chapters, durationSeconds)
    }
}

@Serializable
data class PlaybackProgressTimelineV2(
    @SerialName("timeline_id") val timelineId: String,
    @SerialName("media_item_id") val mediaItemId: String,
    @SerialName("file_id") val fileId: String,
    @SerialName("part_offset_seconds") val partOffsetSeconds: Double,
    @SerialName("part_duration_seconds") val partDurationSeconds: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
) {
    fun accepts(sample: PlaybackSampleV2): Boolean = sample.timelineId == timelineId &&
        sample.position.isFinite() && sample.position in 0.0..partDurationSeconds &&
        sample.itemPosition?.let { it.isFinite() && abs(it - (partOffsetSeconds + sample.position)) <= 0.000001 } == true
}

/** Captured only on explicit discovery. No command retry refreshes this authority or manifest. */
data class CapturedPlaybackManifest(val manifest: PlaybackManifestV2, val authority: DurableLoginAuthority)
