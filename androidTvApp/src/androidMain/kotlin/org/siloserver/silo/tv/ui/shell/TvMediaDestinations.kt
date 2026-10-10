package org.siloserver.silo.tv.ui.shell

import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.tv.ui.navigation.TvMainRoute

/**
 * Skyline content-type-first shell (§3.1): a fixed root order of `Home`, then
 * one tab per [TvLibraryTabType] the profile can actually see (a library of
 * that type exists), then For You and `Calendar`, then Requests while the
 * server has it enabled. Search is a trailing icon button.
 *
 * Mirrors tvOS `TVMainTabView.visibleRoots`.
 */
fun visibleTvRoots(
    libraries: List<UserLibrary>,
    /** tvOS navPrefs.showAudiobooks parity: the Audiobooks tab is opt-in
     *  (hidden by default) even when an audiobook library exists. */
    showAudiobooks: Boolean = false,
    /** Requests is a server capability, not a customizable menu item: it
     *  trails the content tabs whenever the server has it enabled. */
    requestsEnabled: Boolean = false,
): List<TvRootDestination> = buildList {
    add(TvRootDestination.Home)
    TvLibraryTabType.entries
        .filter { type -> libraries.any { type.matches(it) } }
        .filter { type -> type != TvLibraryTabType.Audiobooks || showAudiobooks }
        .forEach { type -> add(TvRootDestination.LibraryType(type)) }
    // tvOS root order: libraries, then For You, then Calendar.
    add(TvRootDestination.ForYou)
    add(TvRootDestination.Calendar)
    if (requestsEnabled) add(TvRootDestination.Requests)
}

fun firstTvRoute(): String = TvMainRoute.Home.route

fun TvRootDestination.isVisibleIn(destinations: List<TvRootDestination>): Boolean =
    this in destinations
