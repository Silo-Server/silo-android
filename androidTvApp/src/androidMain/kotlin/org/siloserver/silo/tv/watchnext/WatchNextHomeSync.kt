package org.siloserver.silo.tv.watchnext

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SectionRepository

/** Actual worker read branch, with provider dispatch injected for synthetic tests. */
internal suspend fun syncWatchNextHome(
    sections: SectionRepository,
    gate: WatchNextWriteGate,
    stopped: () -> Boolean,
    apply: suspend (List<WatchNextProgramFields>, Long, suspend () -> Boolean) -> Unit,
): Boolean {
    val run = gate.capture()
    val owner = sections.captureHomeAuthority() ?: return true
    suspend fun authority(): Boolean {
        val valid = sections.isHomeAuthorityCurrent(owner)
        currentCoroutineContext().ensureActive()
        return valid && !stopped()
    }
    suspend fun current() = gate.allowed(run, ::authority)
    if (!current()) return true
    val response = sections.getHomeSections(owner)
    if (!current()) return true
    if (response !is ApiResult.Success) return false

    val fields = mutableListOf<WatchNextProgramFields>()
    for (section in response.data.sections.filter { it.sectionType in setOf("continue_watching", "next_up") }) {
        var items = section.items
        if (items.isEmpty() && section.totalCount > 0) {
            val fallback = sections.getHomeSectionItems(section.id, owner)
            if (!current()) return true
            if (fallback !is ApiResult.Success) return false
            items = fallback.data.items
            if (items.isEmpty() && (fallback.data.section?.totalCount ?: 0) > 0) return false
        }
        fields += items.mapNotNull { WatchNextProgramMapper.map(it, section.sectionType) }
    }
    if (!current()) return true
    apply(fields, run, ::authority)
    return true
}
