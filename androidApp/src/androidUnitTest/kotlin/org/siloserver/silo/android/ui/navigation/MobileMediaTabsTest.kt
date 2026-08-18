package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.model.navigation.MediaMode
import org.siloserver.silo.model.navigation.MediaModeCapabilities
import org.siloserver.silo.model.settings.PrimaryMenu
import org.siloserver.silo.model.settings.PrimaryMenuBuiltin
import org.siloserver.silo.model.settings.PrimaryMenuItem
import org.siloserver.silo.model.settings.effectivePrimaryMenuForSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileMediaTabsTest {

    @Test
    fun emptyAuthoredMenuFallsBackToAFocusableHomeTab() {
        assertEquals(listOf(Tab.Home), projectedMobileTabs(PrimaryMenu(emptyList())))
    }
    private val capabilities = MediaModeCapabilities(listOf(MediaMode.Video))
    private val baseLabels = listOf("Home", "Libraries", "For You", "Calendar")

    @Test
    fun inheritedTabsAppendDynamicDownloadsWhenPresent() {
        assertEquals(
            baseLabels + "Downloads",
            visibleMobileTabs(capabilities, showDownloads = true).map { it.label },
        )
    }

    @Test
    fun confirmedUnsupportedServerUsesNativeTabsButUnknownKeepsOfflineCache() {
        val cachedMinimal = requireNotNull(
            primaryMenuForMobilePreset(MobileNavigationPreset.MINIMAL),
        )

        assertEquals(
            baseLabels,
            visibleMobileTabs(
                capabilities,
                showDownloads = false,
                primaryMenu = effectivePrimaryMenuForSupport(cachedMinimal, false),
            ).map { it.label },
        )
        assertEquals(
            listOf("Home", "For You"),
            visibleMobileTabs(
                capabilities,
                showDownloads = false,
                primaryMenu = effectivePrimaryMenuForSupport(cachedMinimal, null),
            ).map { it.label },
        )
    }

    @Test
    fun richWireDestinationsCollapseToOneLibrariesTabAtTheirFirstPosition() {
        val menu = PrimaryMenu(
            listOf(
                builtin(PrimaryMenuBuiltin.CALENDAR),
                PrimaryMenuItem.Section(7, "recent", "Recently Added"),
                builtin(PrimaryMenuBuiltin.FOR_YOU),
                PrimaryMenuItem.Collection("favorites", "Favorites", libraryId = 7),
                builtin(PrimaryMenuBuiltin.HOME),
                PrimaryMenuItem.Library(8, "Series"),
            ),
        )

        assertEquals(
            listOf(Tab.Calendar, Tab.Libraries, Tab.ForYou, Tab.Home),
            projectedMobileTabs(menu),
        )
    }

    @Test
    fun movingAggregateLibrariesKeepsEveryUnderlyingWireItemInRelativeOrder() {
        val section = PrimaryMenuItem.Section(7, "recent", "Recently Added")
        val collection = PrimaryMenuItem.Collection("favorites", "Favorites", libraryId = 7)
        val library = PrimaryMenuItem.Library(8, "Series")
        val home = builtin(PrimaryMenuBuiltin.HOME)
        val forYou = builtin(PrimaryMenuBuiltin.FOR_YOU)
        val calendar = builtin(PrimaryMenuBuiltin.CALENDAR)
        val menu = PrimaryMenu(listOf(home, section, forYou, collection, calendar, library))

        val moved = moveMobileTab(menu, Tab.Libraries, offset = 2)

        assertEquals(
            listOf(Tab.Home, Tab.ForYou, Tab.Calendar, Tab.Libraries),
            projectedMobileTabs(moved),
        )
        assertEquals(listOf(home, forYou, calendar, section, collection, library), moved.items)
    }

    @Test
    fun hidingUnrelatedTabPreservesPinnedSectionsAndCollectionsExactly() {
        val section = PrimaryMenuItem.Section(7, "recent", "Recently Added")
        val collection = PrimaryMenuItem.Collection("favorites", "Favorites", libraryId = 7)
        val menu = PrimaryMenu(
            listOf(
                builtin(PrimaryMenuBuiltin.HOME),
                section,
                builtin(PrimaryMenuBuiltin.FOR_YOU),
                collection,
                builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
        )

        val hidden = hideMobileTab(menu, Tab.ForYou)

        assertEquals(
            listOf(builtin(PrimaryMenuBuiltin.HOME), section, collection, builtin(PrimaryMenuBuiltin.CALENDAR)),
            hidden.items,
        )
        assertEquals(listOf(Tab.Home, Tab.Libraries, Tab.Calendar), projectedMobileTabs(hidden))
    }

    @Test
    fun homeCannotBeHiddenAndMissingContentTabsCanBeShownAgain() {
        val minimal = requireNotNull(primaryMenuForMobilePreset(MobileNavigationPreset.MINIMAL))

        assertEquals(minimal, hideMobileTab(minimal, Tab.Home))
        val restored = showMobileTab(
            showMobileTab(minimal, Tab.Libraries),
            Tab.Calendar,
        )
        assertEquals(
            listOf(Tab.Home, Tab.ForYou, Tab.Libraries, Tab.Calendar),
            projectedMobileTabs(restored),
        )
        assertEquals(4, restored.items.count { item ->
            item is PrimaryMenuItem.Builtin && item.destination in setOf(
                PrimaryMenuBuiltin.MOVIES,
                PrimaryMenuBuiltin.SERIES,
                PrimaryMenuBuiltin.MUSIC,
                PrimaryMenuBuiltin.AUDIOBOOKS,
            )
        })
    }

    @Test
    fun presetsHaveStableAggregateLayoutsAndStandardMeansInherit() {
        assertEquals(null, primaryMenuForMobilePreset(MobileNavigationPreset.STANDARD))
        assertEquals(
            listOf(Tab.Libraries, Tab.Home, Tab.ForYou, Tab.Calendar),
            projectedMobileTabs(
                primaryMenuForMobilePreset(MobileNavigationPreset.MEDIA_FIRST),
            ),
        )
        assertEquals(
            listOf(Tab.Home, Tab.ForYou),
            projectedMobileTabs(primaryMenuForMobilePreset(MobileNavigationPreset.MINIMAL)),
        )
    }

    @Test
    fun mediaFirstPresetReordersRichMenuWithoutReplacingPinnedItems() {
        val home = builtin(PrimaryMenuBuiltin.HOME)
        val forYou = builtin(PrimaryMenuBuiltin.FOR_YOU)
        val calendar = builtin(PrimaryMenuBuiltin.CALENDAR)
        val section = PrimaryMenuItem.Section(7, "recent", "My Recent Additions")
        val collection = PrimaryMenuItem.Collection(
            collectionId = "favorites",
            label = "Family Favorites",
            libraryId = 7,
        )
        val library = PrimaryMenuItem.Library(8, "Kids Series")
        val current = PrimaryMenu(
            listOf(home, forYou, section, calendar, collection, library),
        )

        val reordered = requireNotNull(
            primaryMenuForMobilePreset(
                preset = MobileNavigationPreset.MEDIA_FIRST,
                currentMenu = current,
            ),
        )

        assertEquals(
            listOf(section, collection, library, home, forYou, calendar),
            reordered.items,
        )
        assertEquals(
            listOf(Tab.Libraries, Tab.Home, Tab.ForYou, Tab.Calendar),
            projectedMobileTabs(reordered),
        )
    }

    @Test
    fun downloadsStaysFixedAtTheEndAndHiddenWhenEmpty() {
        val menu = requireNotNull(primaryMenuForMobilePreset(MobileNavigationPreset.MEDIA_FIRST))
        val withoutDownloads = visibleMobileTabs(capabilities, false, menu)
        val withDownloads = visibleMobileTabs(capabilities, true, menu)

        assertFalse(Tab.Downloads in withoutDownloads)
        assertEquals(Tab.Downloads, withDownloads.last())
    }

    @Test
    fun choosesFirstVisibleContentTabBeforeDownloads() {
        assertEquals(
            Tab.Home,
            fallbackMobileTab(
                visibleTabs = listOf(Tab.Home, Tab.Libraries, Tab.Downloads),
                defaultTab = Tab.ForYou,
            ),
        )
        assertTrue(Tab.Downloads.isUtilityTab)
    }

    @Test
    fun hidingLibrariesIsRefusedWhilePinnedDestinationsExist() {
        val section = PrimaryMenuItem.Section(7, "recent", "Recently Added")
        val collection = PrimaryMenuItem.Collection("favorites", "Favorites", libraryId = 7)
        val library = PrimaryMenuItem.Library(8, "Kids Series")
        val menu = PrimaryMenu(
            listOf(
                builtin(PrimaryMenuBuiltin.HOME),
                builtin(PrimaryMenuBuiltin.MOVIES),
                section,
                builtin(PrimaryMenuBuiltin.SERIES),
                collection,
                builtin(PrimaryMenuBuiltin.FOR_YOU),
                library,
                builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
        )

        assertFalse(canHideMobileTab(menu, Tab.Libraries))
        // Every pin survives, and so do the media builtins: a partial hide
        // would strip them while the tab stayed on screen, leaving no way to
        // restore them from the mobile editor.
        assertEquals(menu, hideMobileTab(menu, Tab.Libraries))
        assertEquals(
            listOf(section, collection, library),
            hideMobileTab(menu, Tab.Libraries).items.filterNot {
                it is PrimaryMenuItem.Builtin
            },
        )
    }

    @Test
    fun hidingACalendarTabKeepsEveryPinnedDestinationByteForByte() {
        val section = PrimaryMenuItem.Section(7, "recent", "Recently Added")
        val collection = PrimaryMenuItem.Collection("favorites", "Favorites", libraryId = 7)
        val library = PrimaryMenuItem.Library(8, "Kids Series")
        val menu = PrimaryMenu(
            listOf(
                builtin(PrimaryMenuBuiltin.HOME),
                section,
                builtin(PrimaryMenuBuiltin.FOR_YOU),
                collection,
                builtin(PrimaryMenuBuiltin.CALENDAR),
                library,
            ),
        )

        assertTrue(canHideMobileTab(menu, Tab.Calendar))
        val hidden = hideMobileTab(menu, Tab.Calendar)

        assertEquals(
            listOf(
                builtin(PrimaryMenuBuiltin.HOME),
                section,
                builtin(PrimaryMenuBuiltin.FOR_YOU),
                collection,
                library,
            ),
            hidden.items,
        )
        assertEquals(listOf(Tab.Home, Tab.Libraries, Tab.ForYou), projectedMobileTabs(hidden))
    }

    @Test
    fun hidingAndShowingATrailingTabRoundTripsTheExactDocument() {
        val menu = defaultMobilePrimaryMenu()

        val hidden = hideMobileTab(menu, Tab.Calendar)

        assertEquals(
            menu.items.filterNot { it == builtin(PrimaryMenuBuiltin.CALENDAR) },
            hidden.items,
        )
        assertEquals(menu, showMobileTab(hidden, Tab.Calendar))
    }

    @Test
    fun hidingLibrariesWithoutPinsRemovesOnlyMediaBuiltinsAndCanBeRestored() {
        val menu = defaultMobilePrimaryMenu()
        val mediaBuiltins = listOf(
            builtin(PrimaryMenuBuiltin.MOVIES),
            builtin(PrimaryMenuBuiltin.SERIES),
            builtin(PrimaryMenuBuiltin.MUSIC),
            builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
        )

        assertTrue(canHideMobileTab(menu, Tab.Libraries))
        val hidden = hideMobileTab(menu, Tab.Libraries)

        assertEquals(
            listOf(
                builtin(PrimaryMenuBuiltin.HOME),
                builtin(PrimaryMenuBuiltin.FOR_YOU),
                builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
            hidden.items,
        )
        // Restoring appends the same media builtins, matching Apple's
        // `addAvailableShortcut`; the aggregate simply lands last.
        assertEquals(
            hidden.items + mediaBuiltins,
            showMobileTab(hidden, Tab.Libraries).items,
        )
    }

    @Test
    fun hideAffordanceMatchesWhatHidingCanActuallyHonour() {
        val pinned = PrimaryMenu(
            defaultMobilePrimaryMenu().items + PrimaryMenuItem.Library(8, "Kids Series"),
        )

        assertFalse(canHideMobileTab(null, Tab.Home))
        assertFalse(canHideMobileTab(null, Tab.Downloads))
        assertTrue(canHideMobileTab(null, Tab.Libraries))
        assertTrue(canHideMobileTab(defaultMobilePrimaryMenu(), Tab.Libraries))
        assertFalse(canHideMobileTab(pinned, Tab.Libraries))
        assertTrue(canHideMobileTab(pinned, Tab.ForYou))
        assertTrue(canHideMobileTab(pinned, Tab.Calendar))
    }

    @Test
    fun minimalPresetKeepsAPinnedLibrariesBucketWholeIncludingItsBuiltins() {
        val section = PrimaryMenuItem.Section(7, "recent", "Recently Added")
        val library = PrimaryMenuItem.Library(8, "Kids Series")
        val current = PrimaryMenu(
            listOf(
                builtin(PrimaryMenuBuiltin.HOME),
                builtin(PrimaryMenuBuiltin.MOVIES),
                section,
                builtin(PrimaryMenuBuiltin.FOR_YOU),
                library,
                builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
        )

        val minimal = requireNotNull(
            primaryMenuForMobilePreset(MobileNavigationPreset.MINIMAL, current),
        )

        // The pins keep Libraries in the projection, so stripping MOVIES would
        // be unrecoverable from the mobile editor. Calendar holds only builtins
        // and is dropped as the preset intends — Add Menu Item can restore it.
        assertEquals(
            listOf(
                builtin(PrimaryMenuBuiltin.HOME),
                builtin(PrimaryMenuBuiltin.FOR_YOU),
                builtin(PrimaryMenuBuiltin.MOVIES),
                section,
                library,
            ),
            minimal.items,
        )
    }

    @Test
    fun minimalPresetOverAPinnedDocumentPreservesEveryMediaBuiltinAndPin() {
        val section = PrimaryMenuItem.Section(7, "recent", "Recently Added")
        val library = PrimaryMenuItem.Library(8, "Kids Series")
        val current = PrimaryMenu(
            listOf(
                builtin(PrimaryMenuBuiltin.HOME),
                builtin(PrimaryMenuBuiltin.MOVIES),
                section,
                builtin(PrimaryMenuBuiltin.SERIES),
                builtin(PrimaryMenuBuiltin.MUSIC),
                builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
                builtin(PrimaryMenuBuiltin.FOR_YOU),
                library,
                builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
        )

        val minimal = requireNotNull(
            primaryMenuForMobilePreset(MobileNavigationPreset.MINIMAL, current),
        )

        assertEquals(
            listOf(
                builtin(PrimaryMenuBuiltin.HOME),
                builtin(PrimaryMenuBuiltin.FOR_YOU),
                builtin(PrimaryMenuBuiltin.MOVIES),
                section,
                builtin(PrimaryMenuBuiltin.SERIES),
                builtin(PrimaryMenuBuiltin.MUSIC),
                builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
                library,
            ),
            minimal.items,
        )
        assertEquals(
            listOf(Tab.Home, Tab.ForYou, Tab.Libraries),
            projectedMobileTabs(minimal),
        )
    }

    @Test
    fun minimalPresetWithoutPinsStillDropsMediaBuiltinsAndTheyCanBeRestored() {
        val current = defaultMobilePrimaryMenu()

        val minimal = requireNotNull(
            primaryMenuForMobilePreset(MobileNavigationPreset.MINIMAL, current),
        )

        assertEquals(
            listOf(builtin(PrimaryMenuBuiltin.HOME), builtin(PrimaryMenuBuiltin.FOR_YOU)),
            minimal.items,
        )
        assertEquals(listOf(Tab.Home, Tab.ForYou), projectedMobileTabs(minimal))

        // Dropping is only acceptable because the tab leaves the projection and
        // Add Menu Item can bring the whole bucket back.
        val restored = showMobileTab(minimal, Tab.Libraries)

        assertEquals(
            minimal.items + listOf(
                builtin(PrimaryMenuBuiltin.MOVIES),
                builtin(PrimaryMenuBuiltin.SERIES),
                builtin(PrimaryMenuBuiltin.MUSIC),
                builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
            ),
            restored.items,
        )
        assertEquals(
            listOf(Tab.Home, Tab.ForYou, Tab.Libraries),
            projectedMobileTabs(restored),
        )
    }

    @Test
    fun minimalThenStandardRoundTripsAPinnedDocumentToTheSameItemSet() {
        val section = PrimaryMenuItem.Section(7, "recent", "Recently Added")
        val library = PrimaryMenuItem.Library(8, "Kids Series")
        val current = PrimaryMenu(
            listOf(
                builtin(PrimaryMenuBuiltin.HOME),
                builtin(PrimaryMenuBuiltin.MOVIES),
                section,
                builtin(PrimaryMenuBuiltin.SERIES),
                builtin(PrimaryMenuBuiltin.MUSIC),
                builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
                builtin(PrimaryMenuBuiltin.FOR_YOU),
                library,
                builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
        )

        val minimal = requireNotNull(
            primaryMenuForMobilePreset(MobileNavigationPreset.MINIMAL, current),
        )
        val backToStandard = requireNotNull(
            primaryMenuForMobilePreset(MobileNavigationPreset.STANDARD, minimal),
        )

        assertEquals(current.items.toSet(), backToStandard.items.toSet())
        assertEquals(current.items.size, backToStandard.items.size)
        assertEquals(
            listOf(Tab.Home, Tab.Libraries, Tab.ForYou, Tab.Calendar),
            projectedMobileTabs(backToStandard),
        )
    }

    private fun builtin(destination: PrimaryMenuBuiltin) =
        PrimaryMenuItem.Builtin(destination)
}
