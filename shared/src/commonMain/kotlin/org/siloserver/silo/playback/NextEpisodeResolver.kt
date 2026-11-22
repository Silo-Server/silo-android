package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.EpisodeListItem

/**
 * Pure next-episode resolution for auto-advance / "next up". Given the current
 * episode's (season, episode) and a pool of episodes (typically the current
 * season plus the next), returns the episode immediately after the current one
 * in (season, episode) order, or null at the end of the available range.
 *
 * Ordering is by season then episode, so it rolls over into the next season.
 * Episodes at or before the current position are never returned, so it can't
 * advance backwards or replay the current episode. (Specials in season 0 sort
 * before season 1, so they're only "next" when the current episode is itself a
 * special — a regular episode never auto-advances into specials.)
 *
 * Caller note (B4): build the pool in PLAYBACK order — the current season's
 * episodes plus the next regular season's. Don't merge `Season.isSpecials`
 * seasons into the pool from the display list (which lists Specials first);
 * this resolver orders by numeric season, not display position, so a Specials
 * season numbered above the current one would wrongly become "next". Ties on
 * (season, episode) resolve to the first matching input element (catalog
 * tuples are expected unique).
 */
fun nextEpisodeAfter(
    episodes: List<EpisodeListItem>,
    currentSeasonNumber: Int,
    currentEpisodeNumber: Int,
): EpisodeListItem? =
    episodes
        .filter {
            it.seasonNumber > currentSeasonNumber ||
                (it.seasonNumber == currentSeasonNumber && it.episodeNumber > currentEpisodeNumber)
        }
        .minWithOrNull(compareBy({ it.seasonNumber }, { it.episodeNumber }))
