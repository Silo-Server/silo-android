package org.siloserver.silo.network.apiv2

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.network.AuthScopeSnapshot

@Serializable
data class HistoryWatchV2(
    @SerialName("media_item_id") val mediaItemId: String,
    @SerialName("watched_at") val watchedAt: String,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val completed: Boolean,
    val source: String? = null,
)

/** The wire flattens the catalog card alongside its required watch metadata. */
@Serializable(with = HistoryEntryV2Serializer::class)
data class HistoryEntryV2(val item: BrowseItem, val watch: HistoryWatchV2)

internal object HistoryEntryV2Serializer : KSerializer<HistoryEntryV2> {
    override val descriptor = buildClassSerialDescriptor("HistoryEntryV2")
    override fun deserialize(decoder: Decoder): HistoryEntryV2 {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Expected JSON history card.")
        val body = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("Expected history card object.")
        val watch = body["watch"] ?: throw SerializationException("Missing history watch metadata.")
        return HistoryEntryV2(json.json.decodeFromJsonElement(BrowseItem.serializer(), body),
            json.json.decodeFromJsonElement(HistoryWatchV2.serializer(), watch))
    }
    override fun serialize(encoder: Encoder, value: HistoryEntryV2) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Expected JSON history card.")
        val fields = json.json.encodeToJsonElement(BrowseItem.serializer(), value.item).jsonObject.toMutableMap()
        fields["watch"] = json.json.encodeToJsonElement(HistoryWatchV2.serializer(), value.watch)
        json.encodeJsonElement(JsonObject(fields))
    }
}

@Serializable
internal data class HistoryPageWireV2(val items: List<HistoryEntryV2>, val page: PageInfo)

data class HistoryPageV2(val items: List<HistoryEntryV2>, val continuation: HistoryContinuationV2?)

/** Process-local continuation bound to the viewer and request, never an offset. */
class HistoryContinuationV2 internal constructor(
    internal val cursor: String,
    internal val scope: AuthScopeSnapshot?,
    internal val limit: Int,
    internal val imageSize: String?,
    internal val seenCursors: Set<String>,
    internal val seenContentIds: Set<String>,
)
