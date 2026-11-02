package org.siloserver.silo.tv.ui.shell

import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.tv.ui.navigation.TvMainRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class TvMediaDestinationsTest {

    private fun library(id: Int, type: String, sortOrder: Int = 0) =
        UserLibrary(id = id, name = "Lib $id", type = type, sortOrder = sortOrder)

    // Skyline content-type-first shell (§3.1): Home first, then one tab per
    // library type the profile can see (in enum order), then Calendar.
    @Test
    fun rootsAreHomePresentTypesThenCalendar() {
        val libraries = listOf(
            library(1, "music"),
            library(2, "movies"),
            library(3, "series"),
        )
        assertEquals(
            listOf(
                TvRootDestination.Home,
                TvRootDestination.LibraryType(TvLibraryTabType.Movies),
                TvRootDestination.LibraryType(TvLibraryTabType.Series),
                TvRootDestination.LibraryType(TvLibraryTabType.Music),
                TvRootDestination.ForYou,
                TvRootDestination.Calendar,
            ),
            visibleTvRoots(libraries),
        )
    }

    @Test
    fun rootsAreHomeAndCalendarWhenNoLibraries() {
        assertEquals(
            listOf(TvRootDestination.Home, TvRootDestination.ForYou, TvRootDestination.Calendar),
            visibleTvRoots(emptyList()),
        )
    }

    @Test
    fun onlyPresentTypesYieldTabs() {
        val libraries = listOf(library(1, "audiobook"))
        // tvOS parity: the Audiobooks tab is OPT-IN (hidden by default) even
        // when an audiobook library exists.
        assertEquals(
            listOf(
                TvRootDestination.Home,
                TvRootDestination.ForYou,
                TvRootDestination.Calendar,
            ),
            visibleTvRoots(libraries),
        )
        assertEquals(
            listOf(
                TvRootDestination.Home,
                TvRootDestination.LibraryType(TvLibraryTabType.Audiobooks),
                TvRootDestination.ForYou,
                TvRootDestination.Calendar,
            ),
            visibleTvRoots(libraries, showAudiobooks = true),
        )
    }

    @Test
    fun firstRouteIsAlwaysHome() {
        assertEquals(TvMainRoute.Home.route, firstTvRoute())
    }
}
