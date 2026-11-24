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
    /** The series' episode labels: "air_date" when its numbers are synthetic (sports); absent shows numbers. */
    @SerialName("episode_labels") val episodeLabels: String? = null,
    /** An episode card's air date, YYYY-MM-DD, sent when [episodeLabels] is air_date. */
    @SerialName("air_date") val airDate: String? = null,
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
    val overview: String? = null,
    @SerialName("item_source") val itemSource: String? = null,
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("progress_updated_at") val progressUpdatedAt: String? = null,
    @SerialName("poster_is_episode_still") val posterIsEpisodeStill: Boolean? = null,
    @SerialName("backdrop_is_episode_still") val backdropIsEpisodeStill: Boolean? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("backdrop_thumbhash") val backdropThumbhash: String? = null,
    /** Episode cards: the parent series' and season's artwork, never an episode still. Absent on older servers. */
    @SerialName("series_poster_url") val seriesPosterUrl: String? = null,
    @SerialName("series_poster_thumbhash") val seriesPosterThumbhash: String? = null,
    @SerialName("series_backdrop_url") val seriesBackdropUrl: String? = null,
    @SerialName("series_backdrop_thumbhash") val seriesBackdropThumbhash: String? = null,
    @SerialName("season_poster_url") val seasonPosterUrl: String? = null,
    @SerialName("season_poster_thumbhash") val seasonPosterThumbhash: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("user_state") val userState: MediaItemUserState? = null,
    /** This profile's metadata language while the card's description is not available in it yet. */
    @SerialName("pending_translation_language") val pendingTranslationLanguage: String? = null,
    /** Localized fields ("overview", "tagline") whose text was machine-translated by AI. */
    @SerialName("machine_translated_fields") val machineTranslatedFields: List<String> = emptyList(),
)

@Serializable
data class ResolvedSection(
    val id: String,
    @SerialName("section_type") val sectionType: String,
    val title: String,
    /**
     * The shared catalog key whose English is [title] (`home.section.*`),
     * present only while the title is the server's own text, with its named
     * arguments. A client that has the key shows its translation; otherwise
     * [title]. Absent on servers that predate it and for titles someone typed.
     */
    @SerialName("title_key") val titleKey: String? = null,
    @SerialName("title_args") val titleArgs: Map<String, String>? = null,
    val featured: Boolean = false,
    @SerialName("item_limit") val itemLimit: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("is_custom") val isCustom: Boolean = false,
    val customized: Boolean = false,
    val items: List<SectionItem> = emptyList(),
    /**
     * A row of collections' cards ([isCollectionRow]), in the row's order.
     * Such a row has no [items], its [totalCount] counts these cards, and
     * [itemLimit] does not apply. Empty on every other row.
     */
    val collections: List<SectionCollectionCard> = emptyList(),
) {
    /** Convenience alias for [sectionType] used by domain layer. */
    val type: String get() = sectionType

    /** A row whose cards are collections, not titles (`collection_posters`). */
    val isCollectionRow: Boolean get() = sectionType == COLLECTION_POSTERS_SECTION_TYPE

    /**
     * Whether the row has anything to show: its collections for a row of
     * collections, its titles for every other row.
     */
    val hasCards: Boolean get() = if (isCollectionRow) collections.isNotEmpty() else items.isNotEmpty()
}

/** `section_type` of a row of collections. */
const val COLLECTION_POSTERS_SECTION_TYPE = "collection_posters"

/**
 * One card of a row of collections. A [SOURCE_LIBRARY] card opens the server
 * collection [id] in library [libraryId]; a [SOURCE_USER] card opens the
 * personal collection [id], made by [creatorProfileId].
 */
@Serializable
data class SectionCollectionCard(
    // Defaults keep one malformed card from failing the whole Home read;
    // [isOpenable] then leaves it out.
    val id: String = "",
    val source: String = "",
    val title: String = "",
    /** The assigned poster or the viewer's collage; blank means the collection placeholder. */
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("poster_is_collage") val posterIsCollage: Boolean = false,
    /**
     * Server collections with a backdrop: presigned at the size a collection
     * page's hero uses. Absent on servers before silo-server#4415, on a
     * collection without one, and always on a personal collection.
     */
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("backdrop_thumbhash") val backdropThumbhash: String? = null,
    /** The titles the viewer sees in the collection. */
    @SerialName("item_count") val itemCount: Int = 0,
    /** Server collections: a library the viewer can open that holds it. */
    @SerialName("library_id") val libraryId: String? = null,
    /** Personal collections: the profile that made it. */
    @SerialName("creator_profile_id") val creatorProfileId: String? = null,
) {
    val isUserCollection: Boolean get() = source == SOURCE_USER

    /** [libraryId] as this client's library id; null for a personal collection. */
    val libraryIdInt: Int? get() = libraryId?.toIntOrNull()

    /** Unique within a row: a server and a personal collection may share an id. */
    val rowKey: String get() = "$source:$id"

    /** Whether this client can open the card: a known source, and a library for a server collection. */
    val isOpenable: Boolean
        get() = id.isNotBlank() && when (source) {
            SOURCE_LIBRARY -> libraryIdInt != null
            SOURCE_USER -> true
            else -> false
        }

    /** The card as a library collection, for the collection cards and routes that take one. */
    fun toLibraryCollection(): LibraryCollection = LibraryCollection(
        id = id,
        name = title,
        itemCount = itemCount,
        posterUrl = posterUrl?.takeIf { it.isNotBlank() },
        posterThumbhash = posterThumbhash?.takeIf { it.isNotBlank() },
        kind = if (isUserCollection) "user_collections" else "regular",
        creatorProfileId = creatorProfileId,
    )

    companion object {
        const val SOURCE_LIBRARY = "library"
        const val SOURCE_USER = "user"
    }
}

@Serializable
data class SectionsResponse(
    val sections: List<ResolvedSection> = emptyList()
)

/** One Home row from `GET /api/v2/home/layout`: which row it is, without its cards. */
data class HomeLayoutRow(
    val id: String,
    val sectionType: String,
)

@Serializable
data class HomeSectionItemsResponse(
    val section: ResolvedSection? = null,
    val items: List<SectionItem> = emptyList()
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
