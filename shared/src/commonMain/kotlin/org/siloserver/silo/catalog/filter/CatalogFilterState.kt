package org.siloserver.silo.catalog.filter

import kotlinx.serialization.Serializable
import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogQueryRule

/**
 * The mobile browse facet system, mirroring silo-apple's
 * `CatalogFilterState` / `CatalogFacets` / `CatalogQueryBuilder` — Apple is
 * authoritative and the wire contract is pinned against silo-server's
 * catalog_parser.go.
 */
enum class BrowseFacetMediaType { Video, Audiobook }

/**
 * One filter dimension. [WatchStatus] is single-select; everything else is
 * multi-select. [Decade], [DynamicRange], and [WatchStatus] have fixed
 * client-side vocabularies; the rest come from `/catalog/filters`. [label] is
 * English and no screen shows it: screens name a facet from the catalog
 * (`filters.facet.*`).
 */
enum class CatalogFacet(val label: String) {
    Genre("Genres"),
    Decade("Decade"),
    WatchStatus("Watch Status"),
    ContentRating("Content Rating"),
    Resolution("Resolution"),
    DynamicRange("Dynamic Range"),
    Studio("Studio"),
    Network("Network"),
    Country("Country"),
    AudioLanguage("Audio Language"),
    SubtitleLanguage("Subtitle Language"),
    OriginalLanguage("Original Language"),
    Author("Author"),
    Narrator("Narrator"),
    SeriesName("Series");

    val hasFixedVocabulary: Boolean
        get() = this == Decade || this == DynamicRange || this == WatchStatus

    companion object {
        /** iOS `CatalogFacet.available(for:)`. */
        fun available(mediaType: BrowseFacetMediaType): List<CatalogFacet> = when (mediaType) {
            BrowseFacetMediaType.Video -> listOf(
                Genre, Decade, WatchStatus, ContentRating, Resolution, DynamicRange,
                Studio, Network, Country, AudioLanguage, SubtitleLanguage, OriginalLanguage,
            )
            BrowseFacetMediaType.Audiobook -> listOf(
                Genre, Author, Narrator, SeriesName, Decade, WatchStatus,
            )
        }

        /** iOS `CatalogFacets.decadeLadder`. */
        val DECADE_LADDER = listOf(2020, 2010, 2000, 1990, 1980, 1970, 1960)

        /** value → label pairs for the fixed-vocabulary watch status facet. */
        val WATCH_STATUS_OPTIONS: List<Pair<String, UiText>> = listOf(
            "unwatched" to UiText.Key(SharedString.FilterUnwatched),
            "inProgress" to UiText.Key(SharedString.FilterInProgress),
            "watched" to UiText.Key(SharedString.FilterWatched),
            "favorited" to UiText.Key(SharedString.FilterFavorited),
            "watchlist" to UiText.Key(SharedString.FilterWatchlist),
        )

        val DYNAMIC_RANGE_OPTIONS: List<Pair<String, UiText>> = listOf(
            "hdr" to UiText.Key(SharedString.FilterHdr),
            "dolby_vision" to UiText.Key(SharedString.FilterDolbyVision),
        )
    }
}

/**
 * value → label option pairs for [facet] (iOS `optionPairs(for:)`). Values
 * from the server's vocabulary are their own labels.
 */
fun facetOptionPairs(
    facet: CatalogFacet,
    vocabulary: CatalogFiltersResponse?,
    hasProfile: Boolean = true,
): List<Pair<String, UiText>> = when (facet) {
    CatalogFacet.Decade -> CatalogFacet.DECADE_LADDER.map { it.toString() to decadeLabel(it) }
    CatalogFacet.WatchStatus -> if (hasProfile) CatalogFacet.WATCH_STATUS_OPTIONS else emptyList()
    CatalogFacet.DynamicRange -> CatalogFacet.DYNAMIC_RANGE_OPTIONS
    CatalogFacet.Genre -> vocabulary?.genres.orEmpty().serverOptions()
    CatalogFacet.ContentRating -> vocabulary?.contentRatings.orEmpty().serverOptions()
    CatalogFacet.Resolution -> vocabulary?.resolutions.orEmpty().serverOptions()
    CatalogFacet.Studio -> vocabulary?.studios.orEmpty().serverOptions()
    CatalogFacet.Network -> vocabulary?.networks.orEmpty().serverOptions()
    CatalogFacet.Country -> vocabulary?.countries.orEmpty().serverOptions()
    CatalogFacet.AudioLanguage -> vocabulary?.audioLanguages.orEmpty().serverOptions()
    CatalogFacet.SubtitleLanguage -> vocabulary?.subtitleLanguages.orEmpty().serverOptions()
    CatalogFacet.OriginalLanguage -> vocabulary?.originalLanguages.orEmpty().serverOptions()
    CatalogFacet.Author -> vocabulary?.authors.orEmpty().serverOptions()
    CatalogFacet.Narrator -> vocabulary?.narrators.orEmpty().serverOptions()
    CatalogFacet.SeriesName -> vocabulary?.series.orEmpty().serverOptions()
}

/** "2020s" for the decade starting in [year]. */
fun decadeLabel(year: Int): UiText = UiText.Key(SharedString.FilterDecade, listOf(UiText.Number(year)))

private fun List<String>.serverOptions(): List<Pair<String, UiText>> = map { it to UiText.Raw(it) }

/**
 * Applied filter + sort state. [matchAll] combines *across* facets (values
 * within one facet are always OR'd). Persisted per server/profile/library.
 */
@Serializable
data class CatalogFilterState(
    val selections: Map<CatalogFacet, Set<String>> = emptyMap(),
    val matchAll: Boolean = true,
    val sort: String = "added_at",
    val order: String = "desc",
) {
    /** Each non-empty facet dimension counts once (iOS `activeFacetCount`). */
    val activeFacetCount: Int
        get() = selections.count { it.value.isNotEmpty() }

    val hasActiveFilters: Boolean
        get() = activeFacetCount > 0

    val canResetFilters: Boolean
        get() = hasActiveFilters || !matchAll

    fun valuesFor(facet: CatalogFacet): Set<String> = selections[facet].orEmpty()

    fun toggle(facet: CatalogFacet, value: String): CatalogFilterState {
        val current = valuesFor(facet)
        val next = when {
            value in current -> current - value
            // Watch status is single-select (iOS parity).
            facet == CatalogFacet.WatchStatus -> setOf(value)
            else -> current + value
        }
        return copy(selections = (selections + (facet to next)).filterValues { it.isNotEmpty() })
    }

    fun clear(facet: CatalogFacet): CatalogFilterState =
        copy(selections = selections - facet)

    /** Clears facets + matchAll but keeps sort and order (iOS `resetFilters`). */
    fun resetFilters(): CatalogFilterState =
        copy(selections = emptyMap(), matchAll = true)
}

/**
 * State → wire query, mirroring iOS `CatalogQueryBuilder`: one group per
 * facet (`match=any` inside), top-level `match` combining groups; values
 * sorted before emit for a stable cache key.
 */
object CatalogFilterQueryBuilder {

    fun matchParam(state: CatalogFilterState): String =
        if (state.matchAll) "all" else "any"

    fun buildGroups(state: CatalogFilterState): List<CatalogQueryGroup> {
        val groups = mutableListOf<CatalogQueryGroup>()
        for (facet in CatalogFacet.entries) {
            val values = state.valuesFor(facet).sorted()
            if (values.isEmpty()) continue
            groups += when (facet) {
                CatalogFacet.Genre -> CatalogQueryGroup(
                    match = "any",
                    rules = values.map { CatalogQueryRule(field = "genre", op = "contains", value = it) },
                )
                CatalogFacet.Decade -> CatalogQueryGroup(
                    match = "any",
                    rules = values.mapNotNull { start ->
                        val year = start.toIntOrNull() ?: return@mapNotNull null
                        CatalogQueryRule(
                            field = "year",
                            op = "between",
                            value = "",
                            values = listOf(year.toString(), (year + 9).toString()),
                        )
                    },
                )
                CatalogFacet.DynamicRange -> CatalogQueryGroup(
                    match = "any",
                    rules = values.map { CatalogQueryRule(field = it, op = "is", value = true) },
                )
                CatalogFacet.WatchStatus -> {
                    val (field, value) = when (values.first()) {
                        "unwatched" -> "watched" to false
                        "watched" -> "watched" to true
                        "inProgress" -> "in_progress" to true
                        "favorited" -> "favorited" to true
                        "watchlist" -> "in_watchlist" to true
                        else -> continue
                    }
                    CatalogQueryGroup(
                        match = "all",
                        rules = listOf(CatalogQueryRule(field = field, op = "is", value = value)),
                    )
                }
                else -> CatalogQueryGroup(
                    match = "any",
                    rules = values.map {
                        CatalogQueryRule(field = scalarField(facet), op = "is", value = it)
                    },
                )
            }
        }
        return groups
    }

    private fun scalarField(facet: CatalogFacet): String = when (facet) {
        CatalogFacet.ContentRating -> "content_rating"
        CatalogFacet.Resolution -> "resolution"
        CatalogFacet.Studio -> "studio"
        CatalogFacet.Network -> "network"
        CatalogFacet.Country -> "country"
        CatalogFacet.AudioLanguage -> "audio_language"
        CatalogFacet.SubtitleLanguage -> "subtitle_language"
        CatalogFacet.OriginalLanguage -> "original_language"
        CatalogFacet.Author -> "author"
        CatalogFacet.Narrator -> "narrator"
        CatalogFacet.SeriesName -> "series"
        else -> error("no scalar field for $facet")
    }
}
