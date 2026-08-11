package org.siloserver.silo.playback

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.siloserver.silo.model.playback.PlaybackSubtitleInventoryItemV3
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SUBTITLE_DELIVERY_BURN_IN_ONLY
import org.siloserver.silo.model.playback.SUBTITLE_DELIVERY_SIDECAR
import org.siloserver.silo.model.playback.SUBTITLE_SOURCE_DOWNLOADED
import org.siloserver.silo.network.PlaybackRealtimeEvent
import org.siloserver.silo.network.SiloJson

/** Exact authoritative inventory addition carried by `subtitle_ready`. */
data class PlaybackSubtitleReady(
    val sessionId: String?,
    val mediaFileId: Int?,
    val subtitleId: Int?,
    val track: PlaybackSubtitleInventoryItemV3?,
)

fun decodePlaybackSubtitleReady(event: PlaybackRealtimeEvent.ServerEvent): PlaybackSubtitleReady =
    decodePlaybackSubtitleReady(event.payload)

fun decodePlaybackSubtitleReady(payload: JsonObject): PlaybackSubtitleReady {
    val track = payload["track"]?.let { element ->
        runCatching {
            SiloJson.decodeFromJsonElement(PlaybackSubtitleInventoryItemV3.serializer(), element)
        }.getOrNull()
    }
    return PlaybackSubtitleReady(
        sessionId = (payload["session_id"] as? JsonPrimitive)?.contentOrNull,
        mediaFileId = (payload["file_id"] as? JsonPrimitive)?.intOrNull,
        subtitleId = (payload["subtitle_id"] as? JsonPrimitive)?.intOrNull,
        track = track,
    )
}

/**
 * Applies a server-supplied inventory row without manufacturing an ordinal,
 * identity, delivery mode, or URL. Returns null when the event is malformed or
 * would introduce a gap, in which case the caller must obtain a fresh plan.
 */
fun applyAuthoritativeSubtitleReadyTrack(
    existing: List<PlayerSubtitleInfo>,
    update: PlaybackSubtitleReady,
): List<PlayerSubtitleInfo>? {
    val item = update.track ?: return null
    if (item.trackId.isBlank() || item.combinedIndex < 0) return null
    val validDelivery = when (item.delivery) {
        SUBTITLE_DELIVERY_SIDECAR -> !item.url.isNullOrBlank()
        SUBTITLE_DELIVERY_BURN_IN_ONLY -> item.url.isNullOrBlank()
        else -> false
    }
    if (!validDelivery) return null

    val previous = existing.firstOrNull {
        it.serverTrackId == item.trackId || it.index == item.combinedIndex
    }
    val replacement = PlayerSubtitleInfo(
        index = item.combinedIndex,
        language = item.language,
        codec = item.codec,
        label = item.label,
        source = item.source,
        forced = item.forced,
        url = item.url.orEmpty(),
        catalogLabel = previous?.catalogLabel ?: item.label,
        catalogSource = previous?.catalogSource ?: item.source,
        isDefault = item.isDefault,
        downloadId = update.subtitleId.takeIf { item.source == SUBTITLE_SOURCE_DOWNLOADED },
        serverTrackId = item.trackId,
        serverDelivery = item.delivery,
    )
    val rows = (existing.filterNot {
        it.serverTrackId == item.trackId || it.index == item.combinedIndex
    } + replacement).sortedBy(PlayerSubtitleInfo::index)
    if (rows.map(PlayerSubtitleInfo::index) != rows.indices.toList()) return null
    return rows
}
