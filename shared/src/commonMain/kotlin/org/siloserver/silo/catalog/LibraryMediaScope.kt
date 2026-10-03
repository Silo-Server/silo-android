package org.siloserver.silo.catalog

import kotlinx.serialization.encodeToString
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.apiv2.CatalogContinuationV2

fun matchesLibraryMediaScope(type: String, scope: String?): Boolean = when (scope) {
    "movie" -> type.trim().lowercase() in setOf("movie", "movies", "film")
    "series" -> type.trim().lowercase() in setOf("series", "show", "shows", "tv", "episode")
    else -> true
}

data class ScopedLibrarySection(val section: ResolvedSection, val incomplete: Boolean = false)

/** Section sources reject type overlays. Page their original order and filter locally. */
suspend fun scopeLibrarySection(
    section: ResolvedSection,
    mediaScope: String?,
    loadPage: suspend (CatalogContinuationV2?) -> ApiResult<CatalogResponse>,
): ScopedLibrarySection {
    if (mediaScope == null) return ScopedLibrarySection(section)
    val initial = section.items.filter { matchesLibraryMediaScope(it.type, mediaScope) }
    val target = (section.itemLimit.takeIf { it > 0 } ?: section.items.size.coerceAtLeast(20)).coerceAtMost(100)
    fun result(items: List<SectionItem>, incomplete: Boolean = false) =
        ScopedLibrarySection(section.copy(items = items.take(target), totalCount = items.size.coerceAtMost(target)), incomplete)
    if (initial.size >= target || section.totalCount <= section.items.size) return result(initial)

    val originals = section.items.associateBy { it.contentId }
    val visible = linkedMapOf<String, SectionItem>()
    var continuation: CatalogContinuationV2? = null
    repeat(8) {
        when (val page = loadPage(continuation)) {
            is ApiResult.Success -> {
                page.data.items.filter { matchesLibraryMediaScope(it.type, mediaScope) }.forEach { item ->
                    visible.putIfAbsent(item.contentId, originals[item.contentId]
                        ?: SiloJson.decodeFromString<SectionItem>(SiloJson.encodeToString(item)))
                }
                if (visible.size >= target || !page.data.hasMore) return result(visible.values.toList())
                val next = page.data.continuation
                if (next == null || next === continuation || page.data.items.isEmpty())
                    return result((visible.values + initial).distinctBy { it.contentId }, true)
                continuation = next
            }
            else -> return result((visible.values + initial).distinctBy { it.contentId }, true)
        }
    }
    return result((visible.values + initial).distinctBy { it.contentId }, true)
}
