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
    /** Curated landing surface. Always present, but user-orderable. */
    data object Home : TvRootDestination()

    /**
     * A library content-type tab (Movies / Series / Music / Audiobooks).
     * [libraryId] and [customLabel] turn it into a direct pinned-library tab
     * while reusing the same content route and native focus/cascade graph.
     */
    data class LibraryType(
        val type: TvLibraryTabType,
        val libraryId: Int? = null,
        val customLabel: String? = null,
    ) : TvRootDestination() {
        val title: String get() = customLabel ?: type.title
    }

    /** Personal recommendations ("For You") — tvOS `.recommendations`. */
    data object ForYou : TvRootDestination()

    /** Upcoming releases by week. Always the last tab. */
    data object Calendar : TvRootDestination()
}
