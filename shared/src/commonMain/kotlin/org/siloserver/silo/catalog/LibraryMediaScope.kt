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

/**
 * Section sources reject type overlays. Page their original order and filter locally.
 * Shelves with profile overrides keep their scoped inline slice: a refill would page the admin definition.
 */
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
    if (initial.size >= target) return result(initial)
    // Recently Added/Released report total_count = len(items) after LIMIT item_limit, so the count only
    // proves "more" when it exceeds the slice. Only an under-filled slice proves the shelf is exhausted.
    val exhausted = section.totalCount <= section.items.size &&
        section.itemLimit > 0 && section.items.size < section.itemLimit
    if (exhausted) return result(initial)
    // The catalog section source pages the stored admin definition, not the profile-resolved one, so a
    // customized or profile-added shelf cannot be refilled without dropping the profile's overrides.
    if (section.customized || section.isCustom) return result(initial, incomplete = true)

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
                // Inline items come first: a changing shelf (Random) can page a sample without them, and
                // they are the start of an ordered shelf anyway, so the trim to target never evicts them.
                // They also count toward the target, so a page that completes the shelf ends the paging.
                val merged = (initial + visible.values).distinctBy { it.contentId }
                if (merged.size >= target || !page.data.hasMore) return result(merged)
                val next = page.data.continuation
                if (next == null || next === continuation || page.data.items.isEmpty())
                    return result(merged, true)
                continuation = next
            }
            else -> return result((initial + visible.values).distinctBy { it.contentId }, true)
        }
    }
    return result((initial + visible.values).distinctBy { it.contentId }, true)
}
