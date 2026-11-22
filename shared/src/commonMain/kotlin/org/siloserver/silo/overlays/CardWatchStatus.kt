package org.siloserver.silo.overlays

import org.siloserver.silo.model.catalog.MediaItemUserState

/**
 * What a poster's top-right corner shows (web `CardWatchStatus`): a check on a
 * watched title, the episodes a started, unfinished series or season has
 * left, or nothing. It is not a setting: every poster card draws it, whether
 * or not badges are on, and wide cards never do. Sized by
 * [WATCH_STATUS_SIZE] and inset by [EDGE_INSET].
 */
sealed interface CardWatchStatus {
    /** Nothing to draw. */
    data object None : CardWatchStatus

    /** A check mark. */
    data object Watched : CardWatchStatus

    /** The number of episodes left, [count] > 0. */
    data class EpisodesLeft(val count: Int) : CardWatchStatus

    companion object {
        /**
         * The status for [userState]. A watched title gets the check; a series
         * or season the profile has started (`watched_count` > 0) and not
         * finished (`unplayed_count` > 0) gets its count (web
         * `remainingEpisodes`); an untouched one shows nothing, so a library
         * of new shows is not covered in numbers.
         */
        fun of(userState: MediaItemUserState?): CardWatchStatus {
            if (userState == null) return None
            if (userState.played) return Watched
            val watched = userState.watchedCount ?: 0
            val left = userState.unplayedCount ?: 0
            return if (watched > 0 && left > 0) EpisodesLeft(left) else None
        }
    }
}
