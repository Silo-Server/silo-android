package org.siloserver.silo.model.recommendation

import org.siloserver.silo.model.section.SectionItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DiscoverRow(
    val type: String,
    val label: String,
    @SerialName("section_kind") val sectionKind: String? = null,
    @SerialName("section_key") val sectionKey: String? = null,
    val items: List<SectionItem> = emptyList(),
)

@Serializable
data class DiscoverResponse(
    val rows: List<DiscoverRow> = emptyList(),
)

/**
 * `/api/v2/recommendations/similar/{itemId}` returns scored references —
 * each carries only the media item ID, a relevance score, and a short reason.
 * Clients resolve each ID to a catalog item to render poster cards.
 */
@Serializable
data class ScoredItemRef(
    @SerialName("media_item_id") val mediaItemId: String,
    val score: Double? = null,
    val reason: String? = null,
)

@Serializable
data class ScoredItemsResponse(
    val items: List<ScoredItemRef> = emptyList(),
)

@Serializable
data class TasteProfile(
    @SerialName("top_genres") val topGenres: List<String> = emptyList(),
    @SerialName("favorite_directors") val favoriteDirectors: List<String> = emptyList(),
    @SerialName("signal_counts") val signalCounts: Map<String, Int> = emptyMap(),
    @SerialName("updated_at") val updatedAt: String? = null,
)
