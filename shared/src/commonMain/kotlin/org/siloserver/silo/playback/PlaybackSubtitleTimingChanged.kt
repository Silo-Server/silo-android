package org.siloserver.silo.playback

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.network.PlaybackRealtimeEvent

/**
 * `subtitle_timing_changed`: the server retimed a stored subtitle of the file
 * (a sync result or a timing reset). The track's sidecar URL is unchanged but
 * now serves the new timing, so a player showing it must fetch it again.
 */
data class PlaybackSubtitleTimingChanged(
    val sessionId: String?,
    val mediaFileId: Int?,
    val subtitleId: Int?,
)

fun decodePlaybackSubtitleTimingChanged(event: PlaybackRealtimeEvent.ServerEvent): PlaybackSubtitleTimingChanged =
    decodePlaybackSubtitleTimingChanged(event.payload)

fun decodePlaybackSubtitleTimingChanged(payload: JsonObject): PlaybackSubtitleTimingChanged =
    PlaybackSubtitleTimingChanged(
        sessionId = (payload["session_id"] as? JsonPrimitive)?.contentOrNull,
        mediaFileId = (payload["file_id"] as? JsonPrimitive)?.intOrNull,
        subtitleId = (payload["subtitle_id"] as? JsonPrimitive)?.intOrNull,
    )

private val storedSubtitleIdParam = Regex("[?&]downloaded_subtitle_id=([1-9][0-9]*)(?:[&#]|$)")

/**
 * The stored (downloaded or uploaded) subtitle behind this row, or null for
 * embedded, external, and generated-live tracks. Rows merged from the stored
 * list carry it as [PlayerSubtitleInfo.downloadId]; v3 inventory rows carry it
 * only as the `downloaded_subtitle_id` the server pins on their sidecar URL.
 */
fun PlayerSubtitleInfo.storedSubtitleId(): Int? =
    downloadId ?: storedSubtitleIdParam.find(url)?.groupValues?.get(1)?.toIntOrNull()

/** True when [mounted] includes the stored subtitle this event retimed. */
fun PlaybackSubtitleTimingChanged.affects(mounted: List<PlayerSubtitleInfo>): Boolean {
    val id = subtitleId ?: return false
    return mounted.any { it.storedSubtitleId() == id }
}
