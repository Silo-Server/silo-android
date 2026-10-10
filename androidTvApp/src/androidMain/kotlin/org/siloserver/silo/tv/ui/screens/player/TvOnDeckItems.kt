package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.tv.ui.util.visibleOnTv

/** One On Deck card on the Up Next overlay (tvOS `nextUpCarouselItems` parity). */
data class TvOnDeckItem(
    val contentId: String,
    /** The section item's type, so a pick opens the right player (audiobooks too). */
    val type: String,
    val title: String,
    val subtitle: String?,
    val artUrl: String?,
    val artThumbhash: String?,
    val progressFraction: Float?,
)

/**
 * The phone's On Deck projection: home continue-watching pools minus the
 * current item and its series, deduped, capped at 12, and only items with
 * 16:9 art. Items TV hides (see visibleOnTv) are dropped first, as on TV Home.
 */
internal fun List<ResolvedSection>.toTvOnDeckItems(contentId: String, seriesId: String?): List<TvOnDeckItem> {
    return this
        .visibleOnTv()
        .filter { it.sectionType in setOf("continue_watching", "in_progress", "next_up") }
        .flatMap { it.items }
        .filter { item ->
            item.contentId != contentId &&
                (seriesId == null || item.seriesId != seriesId)
        }
        .filter { !it.backdropUrl.isNullOrBlank() }
        .distinctBy { it.contentId }
        .take(12)
        .map { item ->
            val progress = item.positionSeconds?.let { pos ->
                item.durationSeconds?.takeIf { it > 0 }?.let { dur ->
                    (pos / dur).toFloat().coerceIn(0f, 1f)
                }
            }
            TvOnDeckItem(
                contentId = item.contentId,
                type = item.type,
                title = item.seriesTitle ?: item.title,
                subtitle = when {
                    item.seasonNumber != null && item.episodeNumber != null ->
                        "S${item.seasonNumber}·E${item.episodeNumber}" +
                            (item.title.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                    item.year > 0 -> item.year.toString()
                    else -> null
                },
                artUrl = item.backdropUrl,
                artThumbhash = item.backdropThumbhash,
                progressFraction = progress,
            )
        }
}
