package org.siloserver.silo.common.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A typed, coalescible entry for the offline-first sync outbox (Track B).
 *
 * Op kinds the drain can replay today: SET_POSITION (PersonalDataApi.syncProgress),
 * SET_WATCHED/SET_RATING/SET_FAVORITE (PersonalDataApi mutations), and
 * SET_EBOOK_PROGRESS (EbookReaderApi.saveProgress, monotonic-guarded in the drain).
 * SET_TRACK_SELECTION has no server projection API (local-only) so it is not an op.
 *
 * [coalesceKey] lets a newer op replace older un-synced ops of the same kind+target
 * (e.g. repeated SET_POSITION). Pure (JSON payloads) so it is unit-tested.
 */
data class OutboxOperation(
    val kind: String,
    val coalesceKey: String,
    val payloadJson: String,
    val createdAtMs: Long,
) {
    /** Payload for [SET_EBOOK_PROGRESS]; serialized (CFI [location] needs escaping). */
    @Serializable
    data class EbookProgress(val fileId: Int, val location: String, val progress: Double)

    /** Resume row cleared optimistically by a watched-state mutation. */
    @Serializable
    data class ClearedPlaybackProgress(
        val fileId: Int,
        val positionSeconds: Double,
        val previousClientUpdatedAtMs: Long,
        val clearedAtMs: Long,
    )

    /**
     * Watched payload v2. Primitive boolean payloads remain readable for rows
     * created by older app versions.
     */
    @Serializable
    data class WatchedPayload(
        val watched: Boolean,
        val clearedProgress: List<ClearedPlaybackProgress> = emptyList(),
        val requiresReplay: Boolean = false,
    )

    companion object {
        const val SET_POSITION = "SET_POSITION"
        const val SET_WATCHED = "SET_WATCHED"
        const val SET_RATING = "SET_RATING"
        const val SET_FAVORITE = "SET_FAVORITE"
        const val SET_EBOOK_PROGRESS = "SET_EBOOK_PROGRESS"

        private val json = Json

        fun setPosition(
            profileId: String,
            contentId: String,
            positionSeconds: Double,
            durationSeconds: Double?,
            atMs: Long,
        ): OutboxOperation = OutboxOperation(
            kind = SET_POSITION,
            // Content-level: syncProgress is keyed by content id, so positions for
            // an item collapse to the latest regardless of which file produced them.
            coalesceKey = "$profileId|$contentId|$SET_POSITION",
            payloadJson = encodePositionPayload(positionSeconds, durationSeconds),
            createdAtMs = atMs,
        )

        fun setWatched(profileId: String, contentId: String, watched: Boolean, atMs: Long): OutboxOperation =
            OutboxOperation(SET_WATCHED, "$profileId|$contentId|$SET_WATCHED", JsonPrimitive(watched).toString(), atMs)

        fun setRating(profileId: String, contentId: String, rating: Int?, atMs: Long): OutboxOperation =
            OutboxOperation(
                SET_RATING,
                "$profileId|$contentId|$SET_RATING",
                if (rating == null) "null" else JsonPrimitive(rating).toString(),
                atMs,
            )

        fun setFavorite(profileId: String, contentId: String, favorite: Boolean, atMs: Long): OutboxOperation =
            OutboxOperation(SET_FAVORITE, "$profileId|$contentId|$SET_FAVORITE", JsonPrimitive(favorite).toString(), atMs)

        fun decodeBooleanPayload(payloadJson: String): Boolean {
            val element = json.parseToJsonElement(payloadJson)
            return if (element is JsonPrimitive) element.boolean
            else json.decodeFromJsonElement<WatchedPayload>(element).watched
        }

        fun encodeWatchedPayload(payload: WatchedPayload): String = json.encodeToString(payload)

        fun decodeWatchedPayload(payloadJson: String): WatchedPayload {
            val element = json.parseToJsonElement(payloadJson)
            return if (element is JsonPrimitive) WatchedPayload(watched = element.boolean)
            else json.decodeFromJsonElement(element)
        }

        /** Rating payload is the int value, or null for "clear rating". */
        fun decodeRatingPayload(payloadJson: String): Int? =
            json.parseToJsonElement(payloadJson).let { (it as JsonPrimitive).intOrNull }

        /** Position payload is `{"position":<sec>,"duration":<sec|null>}`. */
        fun encodePositionPayload(positionSeconds: Double, durationSeconds: Double?): String =
            """{"position":$positionSeconds,"duration":${durationSeconds ?: "null"}}"""

        /** Decodes [encodePositionPayload] → (positionSeconds, durationSeconds?). */
        fun decodePositionPayload(payloadJson: String): Pair<Double, Double?> {
            val obj = json.parseToJsonElement(payloadJson).jsonObject
            val position = (obj.getValue("position") as JsonPrimitive).double
            val duration = (obj["duration"] as? JsonPrimitive)?.doubleOrNull
            return position to duration
        }

        fun encodeEbookProgressPayload(fileId: Int, location: String, progress: Double): String =
            json.encodeToString(EbookProgress(fileId, location, progress))

        fun decodeEbookProgressPayload(payloadJson: String): EbookProgress =
            json.decodeFromString(payloadJson)
    }
}
