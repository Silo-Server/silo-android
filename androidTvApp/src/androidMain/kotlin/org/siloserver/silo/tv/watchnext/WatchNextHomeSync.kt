package org.siloserver.silo.tv.watchnext

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.siloserver.silo.model.settings.EpisodeSpoilers

/**
 * Actual worker read branch, with provider dispatch injected for synthetic tests.
 * [seriesArtwork] answers null only when the lookup failed; the run then retries
 * instead of dropping the tile.
 */
internal suspend fun syncWatchNextHome(
    sections: SectionRepository,
    gate: WatchNextWriteGate,
    stopped: () -> Boolean,
    spoilerPreferences: suspend () -> EpisodeSpoilerPrefs? = { EpisodeSpoilerPrefs.NONE },
    preferencesCurrent: (EpisodeSpoilerPrefs) -> Boolean = { true },
    seriesArtwork: suspend (String) -> ItemDetail? = { null },
    /** A phone's cast identity is the current scope; the launcher row is never its to write. */
    borrowedIdentity: suspend () -> Boolean = { false },
    apply: suspend (List<WatchNextProgramFields>, Long, suspend () -> Boolean) -> Unit,
): Boolean {
    val run = gate.capture()
    if (borrowedIdentity()) return true
    val owner = sections.captureHomeAuthority() ?: return true
    var prefs: EpisodeSpoilerPrefs? = null
    suspend fun authority(): Boolean {
        // The suspending identity lookup first, so the owner is validated last.
        val valid = !borrowedIdentity() && sections.isHomeAuthorityCurrent(owner)
        currentCoroutineContext().ensureActive()
        return valid && !stopped() && (prefs?.let(preferencesCurrent) ?: true)
    }
    suspend fun current() = gate.allowed(run, ::authority)
    if (!current()) return true
    val resolvedPrefs = spoilerPreferences() ?: return false
    prefs = resolvedPrefs
    if (!current()) return true
    val response = sections.getHomeSections(owner)
    if (!current()) return true
    if (response !is ApiResult.Success) return false

    val fields = mutableListOf<WatchNextProgramFields>()
    val artworkBySeries = mutableMapOf<String, ItemDetail>()
    for (section in response.data.sections.filter { it.sectionType in setOf("continue_watching", "next_up") }) {
        var items = section.items
        if (items.isEmpty() && section.totalCount > 0) {
            val fallback = sections.getHomeSectionItems(section.id, owner)
            if (!current()) return true
            if (fallback !is ApiResult.Success) return false
            items = fallback.data.items
            if (items.isEmpty() && (fallback.data.section?.totalCount ?: 0) > 0) return false
        }
        for (item in items) {
            var mapped = WatchNextProgramMapper.map(item, section.sectionType, resolvedPrefs)
            if (mapped == null && item.type.equals("episode", ignoreCase = true) &&
                resolvedPrefs.hidesImage(EpisodeSpoilers.isUnwatched(item))) {
                val seriesId = item.seriesId?.takeIf { it.isNotBlank() }
                if (seriesId != null) {
                    if (seriesId !in artworkBySeries) {
                        val series = seriesArtwork(seriesId)
                        if (!current()) return true
                        // A failed lookup is not "no safe artwork": publishing
                        // without the tile would remove it from the launcher.
                        artworkBySeries[seriesId] = series ?: return false
                    }
                    mapped = WatchNextProgramMapper.map(item, section.sectionType, resolvedPrefs, artworkBySeries[seriesId])
                }
            }
            mapped?.let(fields::add)
        }
    }
    if (!current()) return true
    apply(fields, run, ::authority)
    return true
}
