package org.siloserver.silo.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.network.ApiResult

/**
 * People (cast and crew) matching a main-search query.
 *
 * Only a server that advertises `people_media_scope` scopes people search and
 * filters it by the viewer's library access. An older server would answer an
 * unscoped, unfiltered list, so it is never asked. The capability is read
 * before every lookup rather than cached, because it belongs to whichever
 * server and profile is current.
 *
 * A null entry in [mediaScopes] searches every media scope. Several scopes are
 * searched separately and merged into one list, exact name matches first. A
 * failed lookup yields no people: titles are the primary search result, and
 * the people row simply stays away.
 */
suspend fun CatalogRepository.searchPeopleForQuery(
    query: String,
    mediaScopes: List<String?>,
    limit: Int = PEOPLE_SEARCH_LIMIT,
): List<Person> {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || mediaScopes.isEmpty()) return emptyList()
    val supported = (searchCapabilities() as? ApiResult.Success)?.data?.peopleMediaScope == true
    if (!supported) return emptyList()
    return coroutineScope {
        val searches = mediaScopes.distinct().map { scope -> async { searchPeople(trimmed, scope) } }
        val lists = searches.awaitAll().mapNotNull { (it as? ApiResult.Success)?.data }
        mergePeopleSearchResults(trimmed, lists, limit)
    }
}

const val PEOPLE_SEARCH_LIMIT: Int = 20

/**
 * One list per searched scope, merged. A single list keeps the server's order,
 * which already ranks exact names first. Several lists are deduplicated by
 * person and re-ranked the same way the server ranks one: exact name matches,
 * then name, then id.
 */
internal fun mergePeopleSearchResults(query: String, lists: List<List<Person>>, limit: Int): List<Person> {
    if (lists.size == 1) return lists.single().take(limit)
    val exact = query.trim()
    return lists.flatten()
        .distinctBy { it.id }
        .sortedWith(
            compareBy<Person> { !it.name.trim().equals(exact, ignoreCase = true) }
                .thenBy { it.name.lowercase() }
                .thenBy { it.id },
        )
        .take(limit)
}
