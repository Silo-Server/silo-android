package org.siloserver.silo.tv.ui.shell

import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.settings.PrimaryMenu
import org.siloserver.silo.model.settings.PrimaryMenuBuiltin
import org.siloserver.silo.model.settings.PrimaryMenuItem
import org.siloserver.silo.model.settings.effectivePrimaryMenuForSupport
import org.siloserver.silo.tv.ui.navigation.TvMainRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun unrenderableAuthoredMenuStillProvidesAFocusableHomeRoot() {
        val menu = PrimaryMenu(
            listOf(PrimaryMenuItem.Section(7, "recent", "Recent")),
        )

        assertEquals(
            listOf(TvRootDestination.Home),
            visibleTvRoots(emptyList(), primaryMenu = menu),
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

    @Test
    fun customizedOrderAndPinnedLibraryBecomeExactFocusableRoots() {
        val libraries = listOf(library(7, "movies"), library(8, "series"))
        val menu = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.CALENDAR),
                PrimaryMenuItem.Library(7, "Family Movies"),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.SERIES),
            ),
        )

        assertEquals(
            listOf(
                TvRootDestination.Calendar,
                TvRootDestination.LibraryType(
                    TvLibraryTabType.Movies,
                    libraryId = 7,
                    customLabel = "Family Movies",
                ),
                TvRootDestination.Home,
                TvRootDestination.LibraryType(TvLibraryTabType.Series),
            ),
            visibleTvRoots(libraries, primaryMenu = menu),
        )
    }

    @Test
    fun unavailableAndUnsupportedCustomItemsAreOmittedWithoutChangingOrder() {
        val menu = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Library(999, "Gone"),
                PrimaryMenuItem.Section(7, "recent", "Recent"),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.MOVIES),
            ),
        )

        assertEquals(
            listOf(
                TvRootDestination.Home,
                TvRootDestination.LibraryType(TvLibraryTabType.Movies),
            ),
            visibleTvRoots(listOf(library(7, "movies")), primaryMenu = menu),
        )
    }

    @Test
    fun pinnedLibraryKeepsItsSharedMediaRouteVisible() {
        val pinned = TvRootDestination.LibraryType(
            TvLibraryTabType.Movies,
            libraryId = 7,
            customLabel = "Family Movies",
        )

        assertTrue(
            TvRootDestination.LibraryType(TvLibraryTabType.Movies)
                .isVisibleIn(listOf(TvRootDestination.Home, pinned)),
        )
    }

    @Test
    fun explicitFamilyMenuOverridesTheLegacyAudiobookVisibilityFlag() {
        val menu = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
            ),
        )

        assertEquals(
            listOf(
                TvRootDestination.Home,
                TvRootDestination.LibraryType(TvLibraryTabType.Audiobooks),
            ),
            visibleTvRoots(
                libraries = listOf(library(9, "audiobooks")),
                showAudiobooks = false,
                primaryMenu = menu,
            ),
        )
    }

    @Test
    fun confirmedUnsupportedServerRestoresLegacyAudiobookRootFromCachedMenu() {
        val libraries = listOf(library(9, "audiobooks"))
        val cachedMinimal = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.FOR_YOU),
            ),
        )

        assertTrue(
            TvRootDestination.LibraryType(TvLibraryTabType.Audiobooks) in visibleTvRoots(
                libraries = libraries,
                showAudiobooks = true,
                primaryMenu = effectivePrimaryMenuForSupport(cachedMinimal, false),
            ),
        )
        assertTrue(
            TvRootDestination.LibraryType(TvLibraryTabType.Audiobooks) !in visibleTvRoots(
                libraries = libraries,
                showAudiobooks = true,
                primaryMenu = effectivePrimaryMenuForSupport(cachedMinimal, null),
            ),
        )
    }

    @Test
    fun audiobookVisibilityUsesSyncedMenuOnlyWhenCapabilityIsNotRejected() {
        val menuWithAudiobooks = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
            ),
        )
        val menuWithoutAudiobooks = PrimaryMenu(
            listOf(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME)),
        )

        assertTrue(resolvedTvAudiobookVisibility(menuWithAudiobooks, true, false))
        assertFalse(resolvedTvAudiobookVisibility(menuWithoutAudiobooks, true, true))
        assertTrue(resolvedTvAudiobookVisibility(menuWithoutAudiobooks, false, true))
        assertFalse(resolvedTvAudiobookVisibility(menuWithoutAudiobooks, null, true))
    }

    @Test
    fun directAudiobookLibraryPlacementEnablesOnlyItsMatchingMediaVisibility() {
        val directAudiobook = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Library(9, "Pinned Audiobooks"),
            ),
        )
        val directMovies = PrimaryMenu(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Library(7, "Pinned Movies"),
            ),
        )
        val libraries = listOf(library(7, "movies"), library(9, "audiobooks"))

        assertTrue(resolvedTvAudiobookVisibility(directAudiobook, true, false, libraries))
        assertFalse(resolvedTvAudiobookVisibility(directMovies, true, false, libraries))
    }

    @Test
    fun exactPinnedAndBuiltinTabsKeepDistinctSelectionIdentity() {
        val builtin = TvRootDestination.LibraryType(TvLibraryTabType.Movies)
        val pinned = TvRootDestination.LibraryType(
            TvLibraryTabType.Movies,
            libraryId = 7,
            customLabel = "Family Movies",
        )
        val destinations = listOf(TvRootDestination.Home, builtin, pinned)

        assertEquals(
            builtin,
            selectedTvRoot(
                routeRoot = builtin,
                destinations = destinations,
                selectedLibraryId = 7,
                exactSelection = builtin,
            ),
        )
        assertEquals(
            pinned,
            selectedTvRoot(
                routeRoot = builtin,
                destinations = destinations,
                selectedLibraryId = 7,
                exactSelection = pinned,
            ),
        )
    }

    @Test
    fun savedSemanticSelectionRestoresTheExactTabWithCurrentLabelsAfterRecreation() {
        val builtin = TvRootDestination.LibraryType(TvLibraryTabType.Movies)
        val pinnedBeforeRecreation = TvRootDestination.LibraryType(
            TvLibraryTabType.Movies,
            libraryId = 7,
            customLabel = "Family Movies",
        )
        val pinnedAfterRecreation = pinnedBeforeRecreation.copy(customLabel = "Movie Night")
        val destinationsAfterRecreation = listOf(
            TvRootDestination.Home,
            builtin,
            pinnedAfterRecreation,
        )

        val savedBuiltin = builtin.saveableSelectionIdentity()
        val savedPinned = pinnedBeforeRecreation.saveableSelectionIdentity()

        assertEquals("builtin:movies", savedBuiltin)
        assertEquals("library:7", savedPinned)
        assertEquals(
            builtin,
            resolveSavedTvLibraryDestination(savedBuiltin, destinationsAfterRecreation),
        )
        assertEquals(
            pinnedAfterRecreation,
            resolveSavedTvLibraryDestination(savedPinned, destinationsAfterRecreation),
            "restoration must resolve the semantic pin against its current authored label",
        )
    }

    @Test
    fun removedAuthoredRootClearsExactSelectionAndFallsBackToAVisibleSibling() {
        val routeRoot = TvRootDestination.LibraryType(TvLibraryTabType.Movies)
        val removedPinned = TvRootDestination.LibraryType(
            TvLibraryTabType.Movies,
            libraryId = 7,
            customLabel = "Family Movies",
        )
        val changedDestinations = listOf(TvRootDestination.Home, routeRoot)

        val restored = resolveSavedTvLibraryDestination(
            removedPinned.saveableSelectionIdentity(),
            changedDestinations,
        )

        assertEquals(null, restored, "a removed authored root must not retain stale exact state")
        assertEquals(
            routeRoot,
            selectedTvRoot(
                routeRoot = routeRoot,
                destinations = changedDestinations,
                selectedLibraryId = 7,
                exactSelection = restored,
            ),
        )
    }

    @Test
    fun missingExactSelectionFallsBackToFirstVisibleRootOfTheSameType() {
        val routeRoot = TvRootDestination.LibraryType(TvLibraryTabType.Movies)
        val firstVisible = TvRootDestination.LibraryType(
            TvLibraryTabType.Movies,
            libraryId = 7,
            customLabel = "Family Movies",
        )
        val secondVisible = TvRootDestination.LibraryType(
            TvLibraryTabType.Movies,
            libraryId = 8,
            customLabel = "Kids Movies",
        )

        assertEquals(
            firstVisible,
            selectedTvRoot(
                routeRoot = routeRoot,
                destinations = listOf(TvRootDestination.Home, firstVisible, secondVisible),
                selectedLibraryId = 999,
                exactSelection = TvRootDestination.LibraryType(
                    TvLibraryTabType.Movies,
                    libraryId = 123,
                ),
            ),
        )
    }

    @Test
    fun explicitPinnedCommitBeatsAStaleSameTypeBuiltinPreview() {
        val builtin = TvRootDestination.LibraryType(TvLibraryTabType.Movies)
        val pinned = TvRootDestination.LibraryType(
            TvLibraryTabType.Movies,
            libraryId = 7,
            customLabel = "Family Movies",
        )

        assertEquals(
            pinned,
            committedTvLibraryDestination(
                type = TvLibraryTabType.Movies,
                libraryId = 7,
                explicitDestination = pinned,
                panelDestination = builtin,
                destinations = listOf(TvRootDestination.Home, builtin, pinned),
            ),
        )
    }

    @Test
    fun cascadeCommitUsesItsPanelIdentityWithoutAnExplicitTabSelection() {
        val builtin = TvRootDestination.LibraryType(TvLibraryTabType.Movies)
        val pinned = TvRootDestination.LibraryType(
            TvLibraryTabType.Movies,
            libraryId = 7,
            customLabel = "Family Movies",
        )

        assertEquals(
            builtin,
            committedTvLibraryDestination(
                type = TvLibraryTabType.Movies,
                libraryId = 7,
                explicitDestination = null,
                panelDestination = builtin,
                destinations = listOf(TvRootDestination.Home, builtin, pinned),
            ),
        )
    }

    @Test
    fun pinnedLibraryCascadeStartsFromItsOwnScope() {
        val pinned = TvRootDestination.LibraryType(
            TvLibraryTabType.Movies,
            libraryId = 7,
            customLabel = "Family Movies",
        )

        assertEquals(7, cascadeCurrentScopeId(pinned, activeLibraryId = 8))
    }

    @Test
    fun builtinLibraryCascadeStartsFromTheActiveScope() {
        val builtin = TvRootDestination.LibraryType(TvLibraryTabType.Movies)

        assertEquals(8, cascadeCurrentScopeId(builtin, activeLibraryId = 8))
    }
}
