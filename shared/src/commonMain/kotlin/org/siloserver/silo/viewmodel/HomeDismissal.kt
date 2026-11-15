package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.isEpisodeItemType
import org.siloserver.silo.model.section.SectionItem

/**
 * Which Home list a card's dismissal goes to, and so what its menu calls it.
 *
 * The server keys the two lists differently. An in-progress card is dismissed
 * on `continue_watching` against its exact `progress_updated_at`, so resuming
 * playback brings it back. A Next Up episode has no progress row; it is
 * dismissed on `next_up`, keyed by its series. Progress wins when a card has
 * both, as on Apple.
 */
class HomeDismissal private constructor(val surface: String, val anchor: String, val menuLabel: String) {
    companion object {
        /** Home rows whose cards can be removed. */
        val sectionTypes = setOf("continue_watching", "in_progress", "next_up")

        /** Null when neither list can represent the card, which then has no remove action. */
        fun of(item: SectionItem): HomeDismissal? {
            item.progressUpdatedAt?.takeIf { it.isNotBlank() }?.let {
                return HomeDismissal("continue_watching", it, "Remove from Continue Watching")
            }
            item.seriesId?.takeIf { it.isNotBlank() }?.let {
                return HomeDismissal("next_up", it, "Remove from Next Up")
            }
            return null
        }
    }
}

/**
 * The show the server drops when this card is dismissed from either list: an
 * episode's series, or the series itself. Movies and books go one card at a
 * time, including an audiobook that belongs to a book series.
 */
internal fun SectionItem.droppedSeriesId(): String? = when {
    type.trim().equals("series", ignoreCase = true) -> contentId
    isEpisodeItemType(type) -> seriesId?.takeIf { it.isNotBlank() }
    else -> null
}
