package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.request.RequestDiscoverySection
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestsDiscoverResponse

/**
 * The hub's discover carousels. A server with an admin-defined Discover
 * layout sends `row_order`: its rows show as they are, in that order, each
 * with a See all page. Rows the app does not draw (the studio, network and
 * genre carousels) and rows with no titles have no section and are skipped.
 * An older server gets the two merged carousels below.
 */
fun discoverCarousels(response: RequestsDiscoverResponse): List<RequestDiscoverySection> {
    if (response.rowOrder.isEmpty()) return mergeDiscoverCarousels(response.sections)
    val sections = response.sections.associateBy { it.key }
    return response.rowOrder.mapNotNull { row -> sections[row.key]?.takeIf { it.results.isNotEmpty() } }
}

/**
 * The page after [last] in a row's See all page. When the server read
 * several source pages to fill one it answers with nextPage, since page + 1
 * would repeat titles; a nextPage past the last page means the end, and one
 * that does not move forward is taken as the end. A page without one read a
 * single source page, so page + 1 follows, up to TMDB's 500.
 */
fun nextDiscoverRowPage(last: RequestDiscoverySection, lastPage: Int): Int? {
    val pageCount = last.totalPages.coerceIn(0, 500)
    val cursor = last.nextPage
    return when {
        cursor != null -> cursor.takeIf { it > lastPage && it <= pageCount }
        lastPage < pageCount -> lastPage + 1
        else -> null
    }
}

/**
 * Merges an older server's fixed per-media-type discover sections
 * (`trending_movies` / `trending_series` / `popular_movies` /
 * `popular_series`) into the two carousels the hub actually shows —
 * "Trending now" and "Crowd favorites" — with movies and series round-robin
 * interleaved and deduped. Mirrors silo-apple's `RequestCarouselMerge`
 * (Apple is authoritative); any other server section (upcoming / on-air) is
 * intentionally dropped, matching iOS.
 */
fun mergeDiscoverCarousels(
    sections: List<RequestDiscoverySection>,
): List<RequestDiscoverySection> {
    fun merged(key: String, title: String, movieKey: String, seriesKey: String): RequestDiscoverySection? {
        val movies = sections.firstOrNull { it.key == movieKey }?.results.orEmpty()
        val series = sections.firstOrNull { it.key == seriesKey }?.results.orEmpty()
        val interleaved = interleave(movies, series)
        if (interleaved.isEmpty()) return null
        return RequestDiscoverySection(
            key = key,
            title = title,
            totalResults = interleaved.size,
            results = interleaved,
        )
    }
    return listOfNotNull(
        merged("trending", "Trending now", "trending_movies", "trending_series"),
        merged("popular", "Crowd favorites", "popular_movies", "popular_series"),
    )
}

private fun interleave(
    first: List<RequestMediaResult>,
    second: List<RequestMediaResult>,
): List<RequestMediaResult> {
    val out = mutableListOf<RequestMediaResult>()
    val seen = mutableSetOf<Pair<String, Int>>()
    fun add(item: RequestMediaResult) {
        if (seen.add(item.mediaType to item.tmdbId)) out += item
    }
    val max = maxOf(first.size, second.size)
    for (index in 0 until max) {
        first.getOrNull(index)?.let(::add)
        second.getOrNull(index)?.let(::add)
    }
    return out
}
