package org.siloserver.silo.tv.ui.shell

import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.tv.ui.navigation.TvMainRoute

/**
 * Skyline content-type-first shell (§3.1): a fixed root order of `Home`, then
 * one tab per [TvLibraryTabType] the profile can actually see (a library of
 * that type exists), then For You and `Calendar`. Search sits left of the
 * tabs; Requests lives in the profile menu.
 *
 * Mirrors tvOS `TVRootDestination.visibleRoots` and the Roku top bar.
 */
fun visibleTvRoots(
    libraries: List<UserLibrary>,
    /** tvOS navPrefs.showAudiobooks parity: the Audiobooks tab is opt-in
     *  (hidden by default) even when an audiobook library exists. */
    showAudiobooks: Boolean = false,
): List<TvRootDestination> = buildList {
    add(TvRootDestination.Home)
    TvLibraryTabType.entries
        .filter { type -> libraries.any { type.matches(it) } }
        .filter { type -> type != TvLibraryTabType.Audiobooks || showAudiobooks }
        .forEach { type -> add(TvRootDestination.LibraryType(type)) }
    // tvOS root order: libraries, then For You, then Calendar.
    add(TvRootDestination.ForYou)
    add(TvRootDestination.Calendar)
}

fun firstTvRoute(): String = TvMainRoute.Home.route

fun TvRootDestination.isVisibleIn(destinations: List<TvRootDestination>): Boolean =
    this in destinations
