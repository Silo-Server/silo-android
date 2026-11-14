package org.siloserver.silo.common.catalog

import androidx.annotation.StringRes
import org.siloserver.silo.catalog.sort.CatalogSortDirectionStyle
import org.siloserver.silo.catalog.sort.CatalogSortField
import org.siloserver.silo.catalog.sort.CatalogSortRelevance
import org.siloserver.silo.common.R as CommonR

/**
 * This field's name in a sort list for [relevance]. Ebook and manga lists
 * call Date Viewed and Plays "Date Read" and "Reads", as the web client does.
 */
@StringRes
fun CatalogSortField.labelRes(relevance: CatalogSortRelevance = CatalogSortRelevance.Any): Int = when (this) {
    CatalogSortField.Title -> CommonR.string.catalog_sort_title
    CatalogSortField.DateAdded -> CommonR.string.catalog_sort_added
    CatalogSortField.ReleaseDate -> CommonR.string.catalog_sort_released
    CatalogSortField.LatestEpisodeAirDate -> CommonR.string.catalog_sort_latestEpisodeAirDate
    CatalogSortField.LatestEpisodeAdded -> CommonR.string.catalog_sort_latestEpisodeAdded
    CatalogSortField.Year -> CommonR.string.catalog_sort_year
    CatalogSortField.ContentRating -> CommonR.string.catalog_sort_contentRating
    CatalogSortField.Duration -> CommonR.string.catalog_sort_duration
    CatalogSortField.ImdbRating -> CommonR.string.catalog_sort_imdbRating
    CatalogSortField.TmdbRating -> CommonR.string.catalog_sort_tmdbRating
    CatalogSortField.RtCriticRating -> CommonR.string.catalog_sort_rtCriticRating
    CatalogSortField.RtAudienceRating -> CommonR.string.catalog_sort_rtAudienceRating
    CatalogSortField.Resolution -> CommonR.string.catalog_sort_resolution
    CatalogSortField.Bitrate -> CommonR.string.catalog_sort_bitrate
    CatalogSortField.Progress -> CommonR.string.catalog_sort_progress
    CatalogSortField.DateViewed ->
        if (relevance.isReading) CommonR.string.catalog_sort_dateRead else CommonR.string.catalog_sort_dateViewed
    CatalogSortField.Plays ->
        if (relevance.isReading) CommonR.string.catalog_sort_reads else CommonR.string.catalog_sort_plays
    CatalogSortField.Author -> CommonR.string.catalog_sort_author
    CatalogSortField.Narrator -> CommonR.string.catalog_sort_narrator
    CatalogSortField.Series -> CommonR.string.catalog_sort_series
}

/** This field's direction name for [order] ("A–Z", "Newest", "Most", ...). */
@StringRes
fun CatalogSortField.directionLabelRes(order: String): Int {
    val ascending = order == "asc"
    return when (directionStyle) {
        CatalogSortDirectionStyle.Alphabetical ->
            if (ascending) CommonR.string.library_direction_az else CommonR.string.library_direction_za
        CatalogSortDirectionStyle.Chronological ->
            if (ascending) CommonR.string.library_direction_oldest else CommonR.string.library_direction_newest
        CatalogSortDirectionStyle.Length ->
            if (ascending) CommonR.string.library_direction_shortest else CommonR.string.library_direction_longest
        CatalogSortDirectionStyle.Magnitude ->
            if (ascending) CommonR.string.library_direction_lowest else CommonR.string.library_direction_highest
        CatalogSortDirectionStyle.Count ->
            if (ascending) CommonR.string.library_direction_fewest else CommonR.string.library_direction_most
    }
}
