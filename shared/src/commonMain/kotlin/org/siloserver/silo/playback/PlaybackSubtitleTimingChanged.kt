package org.siloserver.silo.playback

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.subtitles.SubtitleSyncJob
import org.siloserver.silo.model.subtitles.SubtitleTiming
import org.siloserver.silo.network.PlaybackRealtimeEvent
import org.siloserver.silo.network.SiloJson

/**
 * `subtitle_timing_changed`: the server retimed a subtitle of the file, a
 * stored one or a sidecar (a sync result, or a timing set or reset). The
 * track's URL is unchanged but now serves the new timing, so a player showing
 * it must fetch it again.
 */
data class PlaybackSubtitleTimingChanged(
    val sessionId: String?,
    val mediaFileId: Int?,
    /** The stored subtitle's ID; absent for a sidecar. */
    val subtitleId: Int?,
    /** The track's sync key, as the inventory's `sync_key`; absent from servers that predate sidecar sync. */
    val syncKey: String? = null,
)

fun decodePlaybackSubtitleTimingChanged(event: PlaybackRealtimeEvent.ServerEvent): PlaybackSubtitleTimingChanged =
    decodePlaybackSubtitleTimingChanged(event.payload)

fun decodePlaybackSubtitleTimingChanged(payload: JsonObject): PlaybackSubtitleTimingChanged =
    PlaybackSubtitleTimingChanged(
        sessionId = payload.string("session_id"),
        mediaFileId = (payload["file_id"] as? JsonPrimitive)?.intOrNull,
        subtitleId = (payload["subtitle_id"] as? JsonPrimitive)?.intOrNull,
        syncKey = payload.string("sync_key"),
    )

/**
 * `subtitle_sync_updated`: one step of a sync job of the file (queued, each
 * progress update, and the outcome), with the subtitle's timing after it.
 * Best effort; the sync operations stay authoritative.
 */
data class PlaybackSubtitleSyncUpdated(
    val sessionId: String?,
    val mediaFileId: Int?,
    val syncKey: String,
    val subtitleId: Int?,
    val timing: SubtitleTiming,
    val job: SubtitleSyncJob,
)

/** Null when the payload lacks the sync key, the timing, or a readable job. */
fun decodePlaybackSubtitleSyncUpdated(event: PlaybackRealtimeEvent.ServerEvent): PlaybackSubtitleSyncUpdated? =
    decodePlaybackSubtitleSyncUpdated(event.payload)

fun decodePlaybackSubtitleSyncUpdated(payload: JsonObject): PlaybackSubtitleSyncUpdated? {
    val syncKey = payload.string("sync_key")?.takeIf(String::isNotBlank) ?: return null
    val timing = payload["timing"] as? JsonObject ?: return null
    val job = payload["job"] as? JsonObject ?: return null
    return try {
        PlaybackSubtitleSyncUpdated(
            sessionId = payload.string("session_id"),
            mediaFileId = (payload["file_id"] as? JsonPrimitive)?.intOrNull,
            syncKey = syncKey,
            subtitleId = (payload["subtitle_id"] as? JsonPrimitive)?.intOrNull,
            timing = SiloJson.decodeFromJsonElement(SubtitleTiming.serializer(), timing),
            job = SiloJson.decodeFromJsonElement(SubtitleSyncJob.serializer(), job),
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private val storedSubtitleIdParam = Regex("[?&]downloaded_subtitle_id=([1-9][0-9]*)(?:[&#]|$)")

/**
 * The stored (downloaded or uploaded) subtitle behind this row, or null for
 * embedded, external, and generated-live tracks. Rows merged from the stored
 * list carry it as [PlayerSubtitleInfo.downloadId]; v3 inventory rows carry it
 * only as the `downloaded_subtitle_id` the server pins on their sidecar URL.
 */
fun PlayerSubtitleInfo.storedSubtitleId(): Int? =
    downloadId ?: storedSubtitleIdParam.find(url)?.groupValues?.get(1)?.toIntOrNull()

/**
 * True when [mounted] includes the subtitle this event retimed: by sync key,
 * or by stored subtitle ID from a server that sends no key.
 */
fun PlaybackSubtitleTimingChanged.affects(mounted: List<PlayerSubtitleInfo>): Boolean {
    syncKey?.takeIf(String::isNotBlank)?.let { key -> return mounted.any { it.syncKey == key } }
    val id = subtitleId ?: return false
    return mounted.any { it.storedSubtitleId() == id }
}
