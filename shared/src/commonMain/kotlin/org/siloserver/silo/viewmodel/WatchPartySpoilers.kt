package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.siloserver.silo.model.settings.EpisodeSpoilers
import org.siloserver.silo.model.watchtogether.Suggestion

/**
 * Spoiler protection for a title Watch Party shows: whether it is an episode
 * this profile hasn't started, and where each artwork slot came from. The
 * picker, confirmation page, lobby, and suggestions apply the same rule as
 * every other surface ([EpisodeSpoilers]).
 */
data class WatchPartySpoilers(
    /** An episode this profile hasn't started; false for anything else. */
    val unwatchedEpisode: Boolean,
    val posterIsEpisodeStill: Boolean? = null,
    val backdropIsEpisodeStill: Boolean? = null,
) {
    fun hidesPoster(prefs: EpisodeSpoilerPrefs): Boolean = prefs.hidesImage(unwatchedEpisode, posterIsEpisodeStill)

    /** The backdrop slot shows the poster when there is no backdrop, so it takes that provenance. */
    fun hidesBackdrop(prefs: EpisodeSpoilerPrefs, backdropUrl: String?): Boolean = prefs.hidesImage(
        unwatchedEpisode,
        if (backdropUrl.isNullOrBlank()) posterIsEpisodeStill else backdropIsEpisodeStill,
    )

    fun hidesOverview(prefs: EpisodeSpoilerPrefs): Boolean = prefs.hidesOverview(unwatchedEpisode)

    companion object {
        val NONE = WatchPartySpoilers(unwatchedEpisode = false)

        /**
         * Nothing is known about the title beyond its type: an episode counts
         * as unstarted, and its artwork as its still.
         */
        fun forType(contentType: String) = WatchPartySpoilers(unwatchedEpisode = isEpisode(contentType))

        fun of(item: SectionItem) = WatchPartySpoilers(
            unwatchedEpisode = isEpisode(item.type) && EpisodeSpoilers.isUnwatched(item),
            posterIsEpisodeStill = item.posterIsEpisodeStill,
            backdropIsEpisodeStill = item.backdropIsEpisodeStill,
        )

        fun of(item: BrowseItem) = WatchPartySpoilers(
            unwatchedEpisode = isEpisode(item.type) &&
                EpisodeSpoilers.isUnwatched(item.userState?.played, isInProgress = null, item.positionSeconds),
            posterIsEpisodeStill = item.posterIsEpisodeStill,
            backdropIsEpisodeStill = item.backdropIsEpisodeStill,
        )

        fun of(detail: ItemDetail) = WatchPartySpoilers(
            unwatchedEpisode = isEpisode(detail.type) && EpisodeSpoilers.isUnwatched(detail.userData),
            posterIsEpisodeStill = detail.posterIsEpisodeStill,
            backdropIsEpisodeStill = detail.backdropIsEpisodeStill,
        )

        /** A suggestion carries no watch state or provenance. */
        fun of(suggestion: Suggestion) = forType(suggestion.contentType)

        private fun isEpisode(type: String) = type.trim().equals("episode", ignoreCase = true)
    }
}
