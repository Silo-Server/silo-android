package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.PlaybackMarkerSegment
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.network.PlaybackRealtimeEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** A complete effective snapshot; an empty collection clears the file's markers. */
data class PlaybackMarkersUpdate(
    val intro: TimeRange?,
    val credits: TimeRange?,
    val recap: TimeRange? = null,
    val preview: TimeRange? = null,
    val markerSegments: List<PlaybackMarkerSegment> = legacyMarkerSegments(intro, credits, recap, preview),
    val fileId: Int? = null,
)

/** Accepts legacy realtime ranges and the v2 per-file marker response. */
fun decodeMarkersUpdate(payload: JsonObject): PlaybackMarkersUpdate {
    fun number(obj: JsonObject, key: String): Double? =
        (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    fun range(key: String): TimeRange? {
        val obj = payload[key] as? JsonObject ?: return null
        val start = number(obj, "start_seconds") ?: number(obj, "start") ?: return null
        val end = number(obj, "end_seconds") ?: number(obj, "end") ?: return null
        return TimeRange(start, end).takeIf { start >= 0.0 && end > start }
    }

    val segments = if (payload.containsKey("marker_segments")) {
        validMarkerSegments((payload["marker_segments"] as? JsonArray).orEmpty().mapNotNull { value ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val start = number(obj, "start_seconds") ?: return@mapNotNull null
            val end = number(obj, "end_seconds") ?: return@mapNotNull null
            PlaybackMarkerSegment(kind, start, end)
        })
    } else {
        legacyMarkerSegments(range("intro"), range("credits"), range("recap"), range("preview"))
    }
    return PlaybackMarkersUpdate(
        intro = segments.firstOrNull { it.kind == "intro" }?.range,
        credits = segments.lastOrNull { it.kind == "credits" }?.range,
        recap = segments.firstOrNull { it.kind == "recap" }?.range,
        preview = segments.firstOrNull { it.kind == "preview" }?.range,
        markerSegments = segments,
        fileId = (payload["file_id"] as? JsonPrimitive)?.intOrNull,
    )
}

fun decodeMarkersUpdate(event: PlaybackRealtimeEvent.ServerEvent): PlaybackMarkersUpdate =
    decodeMarkersUpdate(event.payload)
