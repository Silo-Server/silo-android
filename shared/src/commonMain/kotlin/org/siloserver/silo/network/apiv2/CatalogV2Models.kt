package org.siloserver.silo.network.apiv2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.siloserver.silo.model.catalog.AudiobookGroup
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogEffectiveSort
import org.siloserver.silo.network.AuthScopeSnapshot

/** The same query is retained for every page; only its opaque cursor changes. */
@Serializable
data class CatalogQueryV2(
    val source: String = "query",
    val scope: String? = null,
    @SerialName("library_id") val libraryId: String? = null,
    @SerialName("collection_id") val collectionId: String? = null,
    @SerialName("person_id") val personId: String? = null,
    @SerialName("section_id") val sectionId: String? = null,
    val type: String? = null,
    val q: String? = null,
    @SerialName("name_prefix") val namePrefix: String? = null,
    val match: String? = null,
    val groups: List<CatalogRuleGroupV2> = emptyList(),
    val sort: String? = null,
    val order: String? = null,
    val group: String? = null,
    @SerialName("query_limit") val queryLimit: Int? = null,
    @SerialName("skip_total") val skipTotal: Boolean = false,
    val limit: Int = 50,
)

@Serializable
data class CatalogRuleGroupV2(val match: String, val rules: List<CatalogRuleV2>)
@Serializable
data class CatalogRuleV2(val field: String, val op: String, val value: JsonElement)

@Serializable
data class CatalogSearchDiagnosticsV2(
    val provider: String,
    val mode: String,
    @SerialName("semantic_used") val semanticUsed: Boolean,
    @SerialName("fallback_reason") val fallbackReason: String? = null,
    @SerialName("result_window_limit") val resultWindowLimit: Int? = null,
    @SerialName("session_expires_at") val sessionExpiresAt: String? = null,
    @SerialName("index_pending_updates") val indexPendingUpdates: Int? = null,
)

@Serializable
data class CatalogSearchCapabilitiesV2(
    val revision: String,
    val state: String,
    val provider: String,
    @SerialName("result_window_limit") val resultWindowLimit: Int? = null,
    @SerialName("session_ttl_seconds") val sessionTTLSeconds: Int? = null,
    @SerialName("max_sessions_per_account") val maxSessionsPerAccount: Int? = null,
)

@Serializable
internal data class CatalogPageWireV2(
    val items: List<BrowseItem>,
    val page: PageInfo,
    val total: Int,
    @SerialName("total_exact") val totalExact: Boolean,
    @SerialName("window_cursor") val windowCursor: String,
    @SerialName("effective_sort") val effectiveSort: CatalogEffectiveSort? = null,
    @SerialName("search_diagnostics") val searchDiagnostics: CatalogSearchDiagnosticsV2? = null,
)

/** Not persisted: a cursor belongs to the query, operation and viewer that read it. */
class CatalogContinuationV2 internal constructor(
    internal val requestKey: String,
    internal val cursor: String,
    internal val scope: AuthScopeSnapshot?,
    internal val seen: Set<String>,
)

data class CatalogPageV2<T>(
    val items: List<T>,
    val total: Int,
    val totalExact: Boolean,
    val continuation: CatalogContinuationV2?,
    val effectiveSort: CatalogEffectiveSort? = null,
    val searchDiagnostics: CatalogSearchDiagnosticsV2? = null,
    /** Opaque seed for explicit window jumps; never an offset or timestamp. */
    val windowCursor: String? = null,
)

@Serializable
internal data class AudiobookGroupsWireV2(
    val items: List<AudiobookGroup>,
    val page: PageInfo,
    val total: Int,
    @SerialName("total_exact") val totalExact: Boolean,
)

@Serializable
data class CatalogTechnicalFiltersV2(
    val resolutions: List<String>,
    @SerialName("audio_languages") val audioLanguages: List<String>,
    @SerialName("subtitle_languages") val subtitleLanguages: List<String>,
)

@Serializable
data class CatalogFiltersV2(
    val genres: List<String>, val studios: List<String>, val networks: List<String>,
    val countries: List<String>,
    @SerialName("original_languages") val originalLanguages: List<String>,
    @SerialName("content_ratings") val contentRatings: List<String>,
    val authors: List<String>, val narrators: List<String>, val series: List<String>,
    val technical: CatalogTechnicalFiltersV2? = null,
)

@Serializable
data class LibraryCollectionCardV2(
    val id: String, val title: String,
    @SerialName("poster_url") val posterUrl: String,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("item_count") val itemCount: Int,
    val featured: Boolean = false,
    @SerialName("creator_profile_id") val creatorProfileId: String? = null,
)

@Serializable
data class CuratedCollectionV2(
    val id: String, val title: String,
    @SerialName("library_id") val libraryId: String,
    @SerialName("library_ids") val libraryIds: List<String>,
    @SerialName("collection_type") val collectionType: String,
    @SerialName("poster_url") val posterUrl: String,
    @SerialName("poster_thumbhash") val posterThumbhash: String? = null,
    @SerialName("item_count") val itemCount: Int,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("group_id") val groupId: String? = null,
)

@Serializable
data class LibraryCollectionGroupV2(
    val id: String, val name: String, val kind: String,
    @SerialName("sort_mode") val sortMode: String,
    @SerialName("sort_order") val sortOrder: Int,
    val collections: List<LibraryCollectionCardV2>,
)
@Serializable
data class LibraryCollectionUngroupedV2(
    @SerialName("sort_order") val sortOrder: Int,
    val collections: List<LibraryCollectionCardV2>,
)
@Serializable
data class LibraryCollectionTabV2(
    @SerialName("library_id") val libraryId: String,
    val collections: List<CuratedCollectionV2>,
    val groups: List<LibraryCollectionGroupV2>,
    val ungrouped: LibraryCollectionUngroupedV2? = null,
)

/** Scope retained by a facet picker; absent scopes are never guessed. */
data class CatalogFacetScopeV2(val libraryId: String? = null, val source: String? = null, val collectionId: String? = null)
@Serializable
data class CatalogFacetMatchesV2(val matches: List<String>, @SerialName("has_more") val hasMore: Boolean)
