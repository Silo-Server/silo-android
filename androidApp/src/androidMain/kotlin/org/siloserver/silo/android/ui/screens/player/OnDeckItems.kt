package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.settings.EpisodeSpoilers
import org.siloserver.silo.common.i18n.LocaleFormat

/** Existing On Deck projection: ordered, bounded cards with usable landscape art. */
internal fun List<ResolvedSection>.toOnDeckItems(contentId: String, seriesId: String?): List<PlayerViewModel.OnDeckItem> {
    return this
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
            PlayerViewModel.OnDeckItem(
                contentId = item.contentId,
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
                imageIsEpisodeStill = item.backdropIsEpisodeStill,
                isUnwatchedEpisode = item.type.equals("episode", ignoreCase = true) &&
                    EpisodeSpoilers.isUnwatched(item),
                year = item.year.takeIf { it > 0 && (item.seasonNumber == null || item.episodeNumber == null) },
                episodeLine = item.seasonNumber?.let { season ->
                    item.episodeNumber?.let { episode ->
                        PlayerViewModel.OnDeckEpisodeLine(season, episode, item.title.takeIf { it.isNotBlank() })
                    }
                },
            )
        }
}
