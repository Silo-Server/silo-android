package org.siloserver.silo.model.section

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.model.catalog.OverlaySummary

@Serializable
data class SectionItem(
    @SerialName("content_id") val contentId: String,
    val type: String,
    val title: String,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("series_title") val seriesTitle: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val year: Int = 0,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    @SerialName("rating_imdb") val ratingImdb: Double? = null,
    @SerialName("rating_tmdb") val ratingTmdb: Double? = null,
    @SerialName("rating_rt_critic") val ratingRtCritic: Int? = null,
    @SerialName("rating_rt_audience") val ratingRtAudience: Int? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    val runtime: Int? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    val studios: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    @SerialName("show_status") val showStatus: String? = null,
    @SerialName("overlay_summary") val overlaySummary: OverlaySummary? = null,
    val tagline: String? = null,
    val overview: String? = null,
    @SerialName("item_source") val itemSource: String? = null,
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("progress_updated_at") val progressUpdatedAt: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("backdrop_thumbhash") val backdropThumbhash: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("user_state") val userState: MediaItemUserState? = null
)

@Serializable
data class ResolvedSection(
    val id: String,
    @SerialName("section_type") val sectionType: String,
    val title: String,
    val featured: Boolean = false,
    @SerialName("item_limit") val itemLimit: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("is_custom") val isCustom: Boolean = false,
    val customized: Boolean = false,
    val items: List<SectionItem> = emptyList()
) {
    /** Convenience alias for [sectionType] used by domain layer. */
    val type: String get() = sectionType
}

@Serializable
data class SectionsResponse(
    val sections: List<ResolvedSection> = emptyList()
)

@Serializable
data class SectionLayout(
    val id: String,
    @SerialName("section_type") val sectionType: String,
    val title: String,
    val featured: Boolean = false,
    @SerialName("item_limit") val itemLimit: Int = 0,
    @SerialName("is_custom") val isCustom: Boolean = false,
    val customized: Boolean = false
)

@Serializable
data class HomeLayoutResponse(
    val sections: List<SectionLayout> = emptyList()
)

@Serializable
data class HomeSectionItemsResponse(
    val section: ResolvedSection? = null,
    val items: List<SectionItem> = emptyList()
)

@Serializable
data class TextlessPosterResponse(
    @SerialName("poster_url") val posterUrl: String? = null,
)

@Serializable
data class LibraryCollection(
    val id: String,
    val name: String,
    @SerialName("collection_type") val collectionType: String? = null,
    @SerialName("item_count") val itemCount: Int? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    /** "regular" or "user_collections" — only present when the collection
     *  was decoded from a [LibraryTabGroup]; flat-response items leave this
     *  null and should be treated as regular. */
    val kind: String? = null,
    /** Creator profile id for user-collection-kind entries. Null for
     *  admin-curated library collections. */
    @SerialName("creator_profile_id") val creatorProfileId: String? = null,
)

/**
 * Library tab response shape. The server returns either a flat
 * `{ collections: [...] }` payload (for non-Postgres deployments) or a
 * grouped `{ groups: [...], ungrouped?: {...} }` payload. The parser in
 * [org.siloserver.silo.network.api.SectionApi] normalizes both into this type
 * by synthesizing a single anonymous section when only flat data is
 * available.
 */
@Serializable
data class LibraryCollectionsResponse(
    /** Flat view, retained for backward compatibility with older callers. */
    val collections: List<LibraryCollection> = emptyList(),
    /** Ordered groups; each may be empty. */
    val groups: List<LibraryCollectionGroup> = emptyList(),
    /** Anonymous bucket for ungrouped collections plus its render position. */
    val ungrouped: LibraryUngroupedSection? = null,
)

@Serializable
data class LibraryCollectionGroup(
    val id: String,
    val name: String,
    /** "regular" or "user_collections". */
    val kind: String = "regular",
    @SerialName("sort_mode") val sortMode: String = "manual",
    @SerialName("sort_order") val sortOrder: Int = 0,
    val collections: List<LibraryCollection> = emptyList(),
)

@Serializable
data class LibraryUngroupedSection(
    @SerialName("sort_order") val sortOrder: Int = Int.MAX_VALUE,
    val collections: List<LibraryCollection> = emptyList(),
)
