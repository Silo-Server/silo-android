package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.isSpecialsForDisplay

/** A bounded cache; missing seasons never silently join two nonadjacent episode runs. */
internal class TvEpisodeWindow {
    private val pages = mutableMapOf<Int, List<EpisodeListItem>>()

    fun get(season: Int): List<EpisodeListItem>? = pages[season]

    fun put(season: Int, episodes: List<EpisodeListItem>) {
        pages[season] = episodes.sortedBy { it.episodeNumber }
    }

    fun orderedSeasons(seasons: List<Season>): List<Int> =
        seasons.sortedWith(compareBy<Season> { it.isSpecialsForDisplay() }.thenBy { it.seasonNumber })
            .map { it.seasonNumber }.distinct()

    fun retainNear(seasons: List<Season>, selected: Int) {
        val order = orderedSeasons(seasons)
        val index = order.indexOf(selected)
        if (index < 0) return
        val keep = pages.keys.filter { pages[it].orEmpty().isNotEmpty() }
            .sortedBy { kotlin.math.abs(order.indexOf(it) - index) }.take(5).toSet()
        pages.keys.removeAll { pages[it].orEmpty().isNotEmpty() && it !in keep }
    }

    fun snapshot(seasons: List<Season>, selected: Int): EpisodeWindowSnapshot {
        val order = orderedSeasons(seasons)
        val index = order.indexOf(selected)
        if (index < 0 || selected !in pages) return EpisodeWindowSnapshot()
        var first = index
        var last = index
        while (first > 0 && order[first - 1] in pages) first--
        while (last < order.lastIndex && order[last + 1] in pages) last++
        return EpisodeWindowSnapshot(
            episodes = order.subList(first, last + 1).flatMap { pages.getValue(it) }
                .distinctBy { it.contentId },
            previousSeason = order.getOrNull(first - 1),
            nextSeason = order.getOrNull(last + 1),
        )
    }
}

internal data class EpisodeWindowSnapshot(
    val episodes: List<EpisodeListItem> = emptyList(),
    val previousSeason: Int? = null,
    val nextSeason: Int? = null,
)

data class TvEpisodeCarouselJump(
    val contentId: String,
    val revision: Int,
    val requestFocus: Boolean = false,
)
