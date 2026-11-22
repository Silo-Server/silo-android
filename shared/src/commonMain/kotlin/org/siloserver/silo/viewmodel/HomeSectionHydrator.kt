package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.section.HomeSectionItemsResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Complete-or-partial result shared by startup warmup and [HomeViewModel]. */
data class HomeSectionHydration(
    val sections: List<ResolvedSection>,
    val fullyResolved: Boolean,
    val hasTransientFailure: Boolean,
)

internal fun ApiResult<*>.isTransientHomeReadFailure(): Boolean = when (this) {
    is ApiResult.Error -> code == 408 || code == 429 || code in 500..599
    is ApiResult.NetworkError -> true
    is ApiResult.Success -> false
}

/**
 * Keeps aggregate Home sections that already contain items and resolves only
 * genuinely missing section payloads, with bounded fallback concurrency.
 */
suspend fun hydrateHomeSections(
    sections: List<ResolvedSection>,
    maxConcurrency: Int = 4,
    fetchItems: suspend (String) -> ApiResult<HomeSectionItemsResponse>,
): HomeSectionHydration {
    require(maxConcurrency > 0) { "maxConcurrency must be positive" }

    // A row of collections arrives whole, its cards in `collections`, so its
    // empty `items` never means "not inlined".
    val unresolved = sections.filter { !it.isCollectionRow && it.items.isEmpty() && it.totalCount > 0 }
    val semaphore = Semaphore(maxConcurrency)
    val fallbacks = coroutineScope {
        unresolved.map { section ->
            async {
                section to semaphore.withPermit { fetchItems(section.id) }
            }
        }.awaitAll()
    }
    val fallbackById = fallbacks.associate { (section, result) ->
        section.id to (result as? ApiResult.Success)?.let { resolveHomeSectionItems(section, it.data) }
    }

    return HomeSectionHydration(
        sections = sections.mapNotNull { section ->
            when {
                section.hasCards -> section
                section.isCollectionRow || section.totalCount == 0 -> null
                else -> fallbackById[section.id]?.takeIf { it.items.isNotEmpty() }
            }
        },
        fullyResolved = unresolved.all { fallbackById[it.id] != null },
        hasTransientFailure = fallbacks.any { (_, result) -> result.isTransientHomeReadFailure() },
    )
}

private fun resolveHomeSectionItems(
    original: ResolvedSection,
    response: HomeSectionItemsResponse,
): ResolvedSection? {
    val responseSection = response.section
    return when {
        responseSection != null && responseSection.items.isNotEmpty() -> responseSection
        responseSection != null && responseSection.totalCount == 0 -> responseSection
        responseSection != null && response.items.isNotEmpty() ->
            responseSection.copy(items = response.items)
        response.items.isNotEmpty() -> original.copy(items = response.items)
        else -> null
    }
}
