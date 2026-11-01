package org.siloserver.silo.tv.watchnext

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.section.SECTION_ROW_MAX_ITEMS
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
    // The layout names the rows without their cards, so only the rows the
    // launcher mirrors are downloaded. The full Home list can be over 1 MB, and
    // this sync also runs during playback, where a read that size can exhaust
    // the heap on a low-memory TV.
    val layout = sections.getHomeLayout(owner)
    if (!current()) return true
    if (layout !is ApiResult.Success) return false

    val fields = mutableListOf<WatchNextProgramFields>()
    val artworkBySeries = mutableMapOf<String, ItemDetail>()
    for (row in layout.data.filter { it.sectionType in WATCH_NEXT_SECTION_TYPES }) {
        val read = sections.getHomeSectionItems(row.id, owner)
        if (!current()) return true
        if (read !is ApiResult.Success) return false
        // A row read answers with the row's configured limit (up to 100 by
        // default), not the capped Home list's; keep the cards a Home row draws.
        // An empty row is the server's answer, even when hide-watched filtering
        // left total_count above zero.
        for (item in read.data.items.take(SECTION_ROW_MAX_ITEMS)) {
            var mapped = WatchNextProgramMapper.map(item, row.sectionType, resolvedPrefs)
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
                    mapped = WatchNextProgramMapper.map(item, row.sectionType, resolvedPrefs, artworkBySeries[seriesId])
                }
            }
            mapped?.let(fields::add)
        }
    }
    if (!current()) return true
    apply(fields, run, ::authority)
    return true
}

/** The Home rows the launcher's Watch Next row mirrors. */
private val WATCH_NEXT_SECTION_TYPES = setOf("continue_watching", "next_up")
