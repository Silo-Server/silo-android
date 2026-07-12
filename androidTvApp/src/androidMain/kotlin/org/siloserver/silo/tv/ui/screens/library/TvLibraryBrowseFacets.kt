package org.siloserver.silo.tv.ui.screens.library

import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogQueryRule
import org.siloserver.silo.model.navigation.isAudiobookLikeLibraryType

/**
 * Personalized watch-status facet, mirroring tvOS `WatchStatusFilter`. Each
 * lowers to one boolean server rule (see [TvCatalogFacetSelection.toQueryGroups]).
 */
enum class TvWatchStatusFilter(val wireValue: String, val label: String) {
    Unwatched("unwatched", "Unwatched"),
    InProgress("in_progress", "In Progress"),
    Watched("watched", "Watched"),
    Favorited("favorited", "Favorited"),
    Watchlist("watchlist", "Watchlist"),
}

/**
 * A facet dimension the library filter panel can filter on, mirroring tvOS
 * `CatalogFacet`. Server-vocabulary facets read their options from
 * `/catalog/filters`; decade / dynamic range / watch status are fixed
 * client-side vocabularies.
 */
enum class TvCatalogFacet(val title: String) {
    Genre("Genre"),
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

    companion object {
        /** Facets offered for a library media type, in tvOS display order. */
        fun availableFor(libraryType: String): List<TvCatalogFacet> =
            if (isAudiobookLikeLibraryType(libraryType)) {
                listOf(Genre, Author, Narrator, SeriesName, Decade, WatchStatus)
            } else {
                listOf(
                    Genre, Decade, WatchStatus, ContentRating, Resolution,
                    DynamicRange, Studio, Network, Country,
                    AudioLanguage, SubtitleLanguage, OriginalLanguage,
                )
            }

        /** The decade options offered in the panel (newest first, tvOS ladder). */
        val DecadeLadder: List<Int> = listOf(2020, 2010, 2000, 1990, 1980, 1970, 1960)
    }

    /**
     * (value, label) options for this facet — fixed vocab for derived facets,
     * server vocab otherwise. Facets whose list resolves empty are hidden.
     */
    fun optionPairs(options: CatalogFiltersResponse?): List<Pair<String, String>> = when (this) {
        Decade -> DecadeLadder.map { it.toString() to "${it}s" }
        DynamicRange -> listOf("hdr" to "HDR", "dolby_vision" to "Dolby Vision")
        WatchStatus -> TvWatchStatusFilter.entries.map { it.wireValue to it.label }
        Genre -> options?.genres.orEmpty().map { it to it }
        ContentRating -> options?.contentRatings.orEmpty().map { it to it }
        Resolution -> options?.resolutions.orEmpty().map { it to it }
        Studio -> options?.studios.orEmpty().map { it to it }
        Network -> options?.networks.orEmpty().map { it to it }
        Country -> options?.countries.orEmpty().map { it to it }
        AudioLanguage -> options?.audioLanguages.orEmpty().map { it to it }
        SubtitleLanguage -> options?.subtitleLanguages.orEmpty().map { it to it }
        OriginalLanguage -> options?.originalLanguages.orEmpty().map { it to it }
        Author -> options?.authors.orEmpty().map { it to it }
        Narrator -> options?.narrators.orEmpty().map { it to it }
        SeriesName -> options?.series.orEmpty().map { it to it }
    }
}

/**
 * The applied facet selections of the library Browse filter panel — the
 * Kotlin port of the facet half of tvOS `CatalogFilterState` (sort/order/
 * namePrefix stay on [TvLibraryBrowseFilter]). Lowers to the same structured
 * `groups[…]` query the Apple `CatalogQueryBuilder` emits.
 */
data class TvCatalogFacetSelection(
    /** Across-facet combination: true → `match=all` (AND), false → OR. */
    val matchAll: Boolean = true,
    val genres: Set<String> = emptySet(),
    val contentRatings: Set<String> = emptySet(),
    val studios: Set<String> = emptySet(),
    val networks: Set<String> = emptySet(),
    val countries: Set<String> = emptySet(),
    val resolutions: Set<String> = emptySet(),
    val audioLanguages: Set<String> = emptySet(),
    val subtitleLanguages: Set<String> = emptySet(),
    val originalLanguages: Set<String> = emptySet(),
    val authors: Set<String> = emptySet(),
    val narrators: Set<String> = emptySet(),
    val seriesNames: Set<String> = emptySet(),
    /** Decade start years (2010 for "2010s") — each lowers to `year between`. */
    val decades: Set<Int> = emptySet(),
    val hdr: Boolean = false,
    val dolbyVision: Boolean = false,
    val watchStatus: TvWatchStatusFilter? = null,
) {

    fun selectedValues(facet: TvCatalogFacet): Set<String> = when (facet) {
        TvCatalogFacet.Genre -> genres
        TvCatalogFacet.ContentRating -> contentRatings
        TvCatalogFacet.Studio -> studios
        TvCatalogFacet.Network -> networks
        TvCatalogFacet.Country -> countries
        TvCatalogFacet.Resolution -> resolutions
        TvCatalogFacet.AudioLanguage -> audioLanguages
        TvCatalogFacet.SubtitleLanguage -> subtitleLanguages
        TvCatalogFacet.OriginalLanguage -> originalLanguages
        TvCatalogFacet.Author -> authors
        TvCatalogFacet.Narrator -> narrators
        TvCatalogFacet.SeriesName -> seriesNames
        TvCatalogFacet.Decade -> decades.map(Int::toString).toSet()
        TvCatalogFacet.DynamicRange -> buildSet {
            if (hdr) add("hdr")
            if (dolbyVision) add("dolby_vision")
        }
        TvCatalogFacet.WatchStatus -> watchStatus?.let { setOf(it.wireValue) } ?: emptySet()
    }

    fun isSelected(facet: TvCatalogFacet, value: String): Boolean =
        value in selectedValues(facet)

    fun toggled(facet: TvCatalogFacet, value: String): TvCatalogFacetSelection = when (facet) {
        TvCatalogFacet.Genre -> copy(genres = genres.toggle(value))
        TvCatalogFacet.ContentRating -> copy(contentRatings = contentRatings.toggle(value))
        TvCatalogFacet.Studio -> copy(studios = studios.toggle(value))
        TvCatalogFacet.Network -> copy(networks = networks.toggle(value))
        TvCatalogFacet.Country -> copy(countries = countries.toggle(value))
        TvCatalogFacet.Resolution -> copy(resolutions = resolutions.toggle(value))
        TvCatalogFacet.AudioLanguage -> copy(audioLanguages = audioLanguages.toggle(value))
        TvCatalogFacet.SubtitleLanguage -> copy(subtitleLanguages = subtitleLanguages.toggle(value))
        TvCatalogFacet.OriginalLanguage -> copy(originalLanguages = originalLanguages.toggle(value))
        TvCatalogFacet.Author -> copy(authors = authors.toggle(value))
        TvCatalogFacet.Narrator -> copy(narrators = narrators.toggle(value))
        TvCatalogFacet.SeriesName -> copy(seriesNames = seriesNames.toggle(value))
        TvCatalogFacet.Decade -> value.toIntOrNull()?.let { decade ->
            copy(decades = if (decade in decades) decades - decade else decades + decade)
        } ?: this
        TvCatalogFacet.DynamicRange -> when (value) {
            "hdr" -> copy(hdr = !hdr)
            "dolby_vision" -> copy(dolbyVision = !dolbyVision)
            else -> this
        }
        // Watch status is single-select: re-picking the active value clears it.
        TvCatalogFacet.WatchStatus -> copy(
            watchStatus = TvWatchStatusFilter.entries
                .firstOrNull { it.wireValue == value }
                ?.takeIf { it != watchStatus },
        )
    }

    fun cleared(facet: TvCatalogFacet): TvCatalogFacetSelection = when (facet) {
        TvCatalogFacet.Genre -> copy(genres = emptySet())
        TvCatalogFacet.ContentRating -> copy(contentRatings = emptySet())
        TvCatalogFacet.Studio -> copy(studios = emptySet())
        TvCatalogFacet.Network -> copy(networks = emptySet())
        TvCatalogFacet.Country -> copy(countries = emptySet())
        TvCatalogFacet.Resolution -> copy(resolutions = emptySet())
        TvCatalogFacet.AudioLanguage -> copy(audioLanguages = emptySet())
        TvCatalogFacet.SubtitleLanguage -> copy(subtitleLanguages = emptySet())
        TvCatalogFacet.OriginalLanguage -> copy(originalLanguages = emptySet())
        TvCatalogFacet.Author -> copy(authors = emptySet())
        TvCatalogFacet.Narrator -> copy(narrators = emptySet())
        TvCatalogFacet.SeriesName -> copy(seriesNames = emptySet())
        TvCatalogFacet.Decade -> copy(decades = emptySet())
        TvCatalogFacet.DynamicRange -> copy(hdr = false, dolbyVision = false)
        TvCatalogFacet.WatchStatus -> copy(watchStatus = null)
    }

    /** Number of active facet dimensions (drives the Filter pill badge). */
    val activeFacetCount: Int
        get() {
            var n = listOf(
                genres, contentRatings, studios, networks, countries, resolutions,
                audioLanguages, subtitleLanguages, originalLanguages,
                authors, narrators, seriesNames,
            ).count { it.isNotEmpty() }
            if (decades.isNotEmpty()) n += 1
            if (hdr || dolbyVision) n += 1
            if (watchStatus != null) n += 1
            return n
        }

    val hasActiveFilters: Boolean get() = activeFacetCount > 0

    val canReset: Boolean get() = hasActiveFilters || !matchAll

    /**
     * Lowers the selection to structured catalog query groups, byte-for-byte
     * the shapes iOS `CatalogQueryBuilder` produces: one `match=any` group per
     * facet (array columns use `contains`, scalar columns `is`), decades as
     * `year between` range rules, dynamic range as OR'd booleans, watch status
     * as a single boolean rule.
     */
    fun toQueryGroups(): List<CatalogQueryGroup> = buildList {
        addValueGroup("genre", "contains", genres)
        addValueGroup("studio", "is", studios)
        addValueGroup("network", "is", networks)
        addValueGroup("country", "is", countries)
        addValueGroup("content_rating", "is", contentRatings)
        addValueGroup("resolution", "is", resolutions)
        addValueGroup("audio_language", "is", audioLanguages)
        addValueGroup("subtitle_language", "is", subtitleLanguages)
        addValueGroup("original_language", "is", originalLanguages)
        addValueGroup("author", "is", authors)
        addValueGroup("narrator", "is", narrators)
        addValueGroup("series", "is", seriesNames)
        if (decades.isNotEmpty()) {
            add(
                CatalogQueryGroup(
                    match = "any",
                    rules = decades.sorted().map { start ->
                        CatalogQueryRule(
                            field = "year",
                            op = "between",
                            value = "",
                            values = listOf(start.toString(), (start + 9).toString()),
                        )
                    },
                ),
            )
        }
        if (hdr || dolbyVision) {
            add(
                CatalogQueryGroup(
                    match = "any",
                    rules = buildList {
                        if (hdr) add(CatalogQueryRule(field = "hdr", op = "is", value = "true"))
                        if (dolbyVision) add(CatalogQueryRule(field = "dolby_vision", op = "is", value = "true"))
                    },
                ),
            )
        }
        watchStatus?.let { status ->
            val rule = when (status) {
                TvWatchStatusFilter.Unwatched -> CatalogQueryRule(field = "watched", op = "is", value = "false")
                TvWatchStatusFilter.Watched -> CatalogQueryRule(field = "watched", op = "is", value = "true")
                TvWatchStatusFilter.InProgress -> CatalogQueryRule(field = "in_progress", op = "is", value = "true")
                TvWatchStatusFilter.Favorited -> CatalogQueryRule(field = "favorited", op = "is", value = "true")
                TvWatchStatusFilter.Watchlist -> CatalogQueryRule(field = "in_watchlist", op = "is", value = "true")
            }
            add(CatalogQueryGroup(match = "all", rules = listOf(rule)))
        }
    }

    private fun MutableList<CatalogQueryGroup>.addValueGroup(
        field: String,
        op: String,
        values: Set<String>,
    ) {
        if (values.isEmpty()) return
        add(
            CatalogQueryGroup(
                match = "any",
                rules = values.sorted().map { CatalogQueryRule(field = field, op = op, value = it) },
            ),
        )
    }

    private fun Set<String>.toggle(value: String): Set<String> =
        if (value in this) this - value else this + value
}
