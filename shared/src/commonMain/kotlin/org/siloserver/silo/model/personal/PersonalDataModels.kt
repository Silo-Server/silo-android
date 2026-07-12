package org.siloserver.silo.model.personal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.QueryDefinition

/**
 * Favorite items reuse [BrowseItem] from the catalog models.
 * This typealias makes intent clear at call sites.
 */
typealias FavoriteItem = BrowseItem

@Serializable
data class Collection(
    val id: String,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("creator_profile_id") val creatorProfileId: String? = null,
    val name: String,
    val description: String? = null,
    @SerialName("collection_type") val collectionType: String = "manual",
    @SerialName("is_shared") val isShared: Boolean = false,
    @SerialName("allowed_profile_ids") val allowedProfileIds: List<String> = emptyList(),
    @SerialName("query_definition") val queryDefinition: QueryDefinition? = null,
    @SerialName("sort_config") val sortConfig: Map<String, JsonElement>? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("item_count") val itemCount: Int? = null,
    @SerialName("include_in_server_collections") val includeInServerCollections: Boolean? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class CollectionGroup(
    val id: String,
    val name: String,
    val slug: String = "",
    @SerialName("default_sort_mode") val defaultSortMode: String = "manual",
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class CollectionItem(
    @SerialName("collection_id") val collectionId: String,
    @SerialName("media_item_id") val mediaItemId: String,
    val position: Int = 0,
    @SerialName("added_at") val addedAt: String? = null
)

@Serializable
data class CreateCollectionRequest(
    val name: String,
    @SerialName("collection_type") val collectionType: String? = null,
    @SerialName("is_shared") val isShared: Boolean? = null,
    @SerialName("allowed_profile_ids") val allowedProfileIds: List<String>? = null,
    @SerialName("query_definition") val queryDefinition: QueryDefinition? = null,
    @SerialName("sort_config") val sortConfig: Map<String, JsonElement>? = null
)

@Serializable
data class UpdateCollectionRequest(
    val name: String? = null,
    val description: String? = null,
    @SerialName("collection_type") val collectionType: String? = null,
    @SerialName("is_shared") val isShared: Boolean? = null,
    @SerialName("allowed_profile_ids") val allowedProfileIds: List<String>? = null,
    @SerialName("query_definition") val queryDefinition: QueryDefinition? = null,
    @SerialName("sort_config") val sortConfig: Map<String, JsonElement>? = null,
    @SerialName("include_in_server_collections") val includeInServerCollections: Boolean? = null,
    // group_id deliberately omitted — use [CollectionApi.moveCollectionToGroup]
    // so the JSON `null` literal is preserved (the shared Json drops nulls).
)

@Serializable
data class CreateCollectionGroupRequest(
    val name: String,
    val slug: String? = null,
    @SerialName("default_sort_mode") val defaultSortMode: String? = null,
)

@Serializable
data class UpdateCollectionGroupRequest(
    val name: String? = null,
    val slug: String? = null,
    @SerialName("default_sort_mode") val defaultSortMode: String? = null,
)

@Serializable
data class ReorderCollectionsRequest(
    @SerialName("ordered_ids") val orderedIds: List<String>,
    @SerialName("group_id") val groupId: String? = null,
)

@Serializable
data class ReorderCollectionGroupsRequest(
    @SerialName("ordered_ids") val orderedIds: List<String>,
)

@Serializable
data class ProgressEntry(
    @SerialName("media_item_id") val mediaItemId: String,
    @SerialName("position_seconds") val positionSeconds: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val completed: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class ProgressListResponse(
    val progress: List<ProgressEntry> = emptyList()
)

@Serializable
data class SyncProgressRequest(
    val items: List<SyncProgressItem>
)

@Serializable
data class SyncProgressItem(
    @SerialName("media_item_id") val mediaItemId: String,
    val position: Double,
    val duration: Double,
    @SerialName("force_overwrite") val forceOverwrite: Boolean = false
)

@Serializable
data class UserLibrary(
    val id: Int,
    val name: String,
    val type: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("poster_url") val posterUrl: String? = null
)

@Serializable
data class LibraryPlaybackPreference(
    @SerialName("profile_id") val profileId: String,
    @SerialName("library_id") val libraryId: Int,
    @SerialName("audio_language") val audioLanguage: String? = null,
    @SerialName("subtitle_language") val subtitleLanguage: String? = null,
    @SerialName("subtitle_mode") val subtitleMode: String? = null,
    @SerialName("show_forced_subtitles") val showForcedSubtitles: Boolean? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class RatingEntry(
    @SerialName("media_item_id") val mediaItemId: String? = null,
    val rating: Double,
    @SerialName("rated_at") val updatedAt: String? = null
)

@Serializable
data class RatingsResponse(
    val ratings: List<RatingEntry>
)

@Serializable
data class CollectionsResponse(
    val collections: List<Collection> = emptyList(),
    val groups: List<CollectionGroup> = emptyList(),
)

@Serializable
data class SetRatingRequest(
    val rating: Int
)

@Serializable
data class ContinueWatchingDismissalRequest(
    @SerialName("progress_updated_at") val progressUpdatedAt: String
)

@Serializable
data class NextUpDismissalRequest(
    @SerialName("series_id") val seriesId: String
)
