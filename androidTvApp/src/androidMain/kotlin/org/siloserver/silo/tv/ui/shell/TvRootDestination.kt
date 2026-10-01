package org.siloserver.silo.tv.ui.shell

/**
 * Identifies which top-level destination the menu bar selects/highlights.
 *
 * Migrated to a sealed hierarchy (Skyline §3.1) so the bar is content-type-first
 * — `Home`, one tab per visible [TvLibraryTabType], then `Calendar`. Search and
 * For You are no longer root tabs (Search is a trailing icon button; For You
 * becomes a Home row in a later stage). Mirrors tvOS `TVRootDestination`.
 */
sealed class TvRootDestination {
    /** Curated landing surface. Always the first tab. */
    data object Home : TvRootDestination()

    /** A library content-type tab (Movies / Series / Music / Audiobooks). */
    data class LibraryType(val type: TvLibraryTabType) : TvRootDestination()

    /** Personal recommendations ("For You") — tvOS `.recommendations`. */
    data object ForYou : TvRootDestination()

    /** Upcoming releases by week. */
    data object Calendar : TvRootDestination()

    /**
     * Media requests, tvOS `.requests`. Present only while the server has
     * requests enabled for this profile; it trails the content tabs.
     */
    data object Requests : TvRootDestination()
}
