package org.siloserver.silo.tv.ui.screens.settings

import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.settings.NavigationShortcuts
import org.siloserver.silo.model.settings.PrimaryMenu
import org.siloserver.silo.model.settings.PrimaryMenuBuiltin
import org.siloserver.silo.model.settings.PrimaryMenuItem
import org.siloserver.silo.model.settings.UiCustomizationCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvCustomizationLimitsTest {
    private val home = PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.HOME)

    @Test
    fun visibleMenuMoveWeavesAroundUnsupportedRootsWithoutMovingThem() {
        val section = PrimaryMenuItem.Section(7, "recent", "Recent")
        val movies = PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.MOVIES)
        val authored = listOf(home, section, movies)

        assertEquals(listOf(home, movies), visibleTvMenuItems(authored, emptySet()))
        assertEquals(
            listOf(movies, section, home),
            moveVisibleTvMenuItem(
                items = authored,
                identity = "builtin:movies",
                offset = -1,
                visibleLibraryIds = emptySet(),
            ),
        )
    }

    @Test
    fun editorHidesLibraryPinsAbsentFromTheTvVisibleLibraryList() {
        // A family=tv document authored by a client that does expose ebooks:
        // library 9 is a reading library, so it never reaches the TV-visible
        // library list and must not become a reorderable editor row.
        val ebookPin = PrimaryMenuItem.Library(9, "Books")
        val moviesPin = PrimaryMenuItem.Library(1, "Movies")
        val authored = listOf(home, ebookPin, moviesPin)
        val visibleLibraryIds = setOf(1)

        assertEquals(
            listOf(home, moviesPin),
            visibleTvMenuItems(authored, visibleLibraryIds),
        )
        assertFalse(ebookPin in visibleTvMenuItems(authored, visibleLibraryIds))

        // The hidden pin keeps its exact slot while a visible row weaves past it.
        assertEquals(
            listOf(moviesPin, ebookPin, home),
            moveVisibleTvMenuItem(
                items = authored,
                identity = UiCustomizationCodec.identity(moviesPin),
                offset = -1,
                visibleLibraryIds = visibleLibraryIds,
            ),
        )
        // And it can never be moved itself.
        assertNull(
            moveVisibleTvMenuItem(
                items = authored,
                identity = UiCustomizationCodec.identity(ebookPin),
                offset = -1,
                visibleLibraryIds = visibleLibraryIds,
            ),
        )
    }

    @Test
    fun visibleMenuMoveRejectsProjectionBoundariesAndUnsupportedIdentities() {
        val authored = listOf(
            home,
            PrimaryMenuItem.Section(7, "recent", "Recent"),
            PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.MOVIES),
        )

        assertNull(moveVisibleTvMenuItem(authored, "builtin:home", -1, emptySet()))
        assertNull(moveVisibleTvMenuItem(authored, "builtin:movies", 1, emptySet()))
        assertNull(moveVisibleTvMenuItem(authored, "section:7:recent", -1, emptySet()))
        assertNull(moveVisibleTvMenuItem(authored, "builtin:movies", 0, emptySet()))
    }

    @Test
    fun visibleMenuMovePreservesAFullContractSizedDocument() {
        val movies = PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.MOVIES)
        val hidden = List(TvPrimaryMenuMaxItems - 2) { index ->
            PrimaryMenuItem.Section(7, "section-$index", "Section $index")
        }
        val authored = listOf(home) + hidden + movies

        val moved = assertNotNull(
            moveVisibleTvMenuItem(authored, "builtin:movies", -1, emptySet()),
        )

        assertEquals(TvPrimaryMenuMaxItems, moved.size)
        assertEquals(movies, moved.first())
        assertEquals(hidden, moved.subList(1, moved.lastIndex))
        assertEquals(home, moved.last())
    }

    @Test
    fun primaryMenuAdditionStopsAtContractLimit() {
        val existing = buildList {
            add(home)
            repeat(TvPrimaryMenuMaxItems - 1) { index ->
                add(PrimaryMenuItem.Library(index + 1, "Library ${index + 1}"))
            }
        }

        assertNull(
            prepareTvMenuItemAddition(
                menuItems = existing,
                currentShortcuts = NavigationShortcuts.EMPTY,
                item = PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
        )
    }

    @Test
    fun primaryMenuAdditionCanReachButNotExceedContractLimit() {
        val existing = buildList {
            add(home)
            repeat(TvPrimaryMenuMaxItems - 2) { index ->
                add(PrimaryMenuItem.Library(index + 1, "Library ${index + 1}"))
            }
        }

        val addition = assertNotNull(
            prepareTvMenuItemAddition(
                menuItems = existing,
                currentShortcuts = NavigationShortcuts.EMPTY,
                item = PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
        )

        assertEquals(TvPrimaryMenuMaxItems, addition.primaryMenu.items.size)
        assertNull(addition.shortcuts)
    }

    @Test
    fun newLibraryIsRejectedBeforeEitherWriteWhenShortcutsAreAtLimit() {
        val shortcuts = NavigationShortcuts(
            List(TvNavigationShortcutsMaxItems) { index ->
                PrimaryMenuItem.Library(index + 1, "Library ${index + 1}")
            },
        )

        assertNull(
            prepareTvMenuItemAddition(
                menuItems = listOf(home),
                currentShortcuts = shortcuts,
                item = PrimaryMenuItem.Library(10_000, "New Library"),
            ),
        )
    }

    @Test
    fun unpinFreesShortcutCapacityWithoutChangingTheMenu() {
        val pinnedMenuItem = PrimaryMenuItem.Library(1, "Pinned Library")
        val menu = PrimaryMenu(listOf(home, pinnedMenuItem))
        val fullShortcuts = NavigationShortcuts(
            List(TvNavigationShortcutsMaxItems) { index ->
                PrimaryMenuItem.Library(index + 1, "Library ${index + 1}")
            },
        )
        val unpinnedShortcuts = NavigationShortcuts(
            fullShortcuts.items.filterNot {
                UiCustomizationCodec.identity(it) ==
                    UiCustomizationCodec.identity(pinnedMenuItem)
            },
        )

        val addition = assertNotNull(
            prepareTvMenuItemAddition(
                menuItems = menu.items,
                currentShortcuts = unpinnedShortcuts,
                item = PrimaryMenuItem.Library(10_000, "New Library"),
            ),
        )

        assertEquals(listOf(home, pinnedMenuItem), menu.items)
        assertEquals(TvNavigationShortcutsMaxItems, addition.shortcuts?.items?.size)
    }

    @Test
    fun shortcutAdditionCanReachButNotExceedContractLimit() {
        val shortcuts = NavigationShortcuts(
            List(TvNavigationShortcutsMaxItems - 1) { index ->
                PrimaryMenuItem.Library(index + 1, "Library ${index + 1}")
            },
        )

        val addition = assertNotNull(
            prepareTvMenuItemAddition(
                menuItems = listOf(home),
                currentShortcuts = shortcuts,
                item = PrimaryMenuItem.Library(10_000, "New Library"),
            ),
        )

        assertEquals(TvNavigationShortcutsMaxItems, addition.shortcuts?.items?.size)
    }

    @Test
    fun existingShortcutDoesNotConsumeCapacityWhenLibraryJoinsMenu() {
        val existingLibrary = PrimaryMenuItem.Library(1, "Library 1")
        val shortcuts = NavigationShortcuts(
            buildList {
                add(existingLibrary)
                repeat(TvNavigationShortcutsMaxItems - 1) { index ->
                    add(PrimaryMenuItem.Library(index + 2, "Library ${index + 2}"))
                }
            },
        )

        val addition = assertNotNull(
            prepareTvMenuItemAddition(
                menuItems = listOf(home),
                currentShortcuts = shortcuts,
                item = existingLibrary,
            ),
        )

        assertEquals(listOf(home, existingLibrary), addition.primaryMenu.items)
        assertNull(addition.shortcuts)
    }

    @Test
    fun audiobookToggleCannotGrowAFullPrimaryMenu() {
        val fullMenu = buildList {
            add(home)
            repeat(TvPrimaryMenuMaxItems - 1) { index ->
                add(PrimaryMenuItem.Library(index + 1, "Library ${index + 1}"))
            }
        }

        assertFalse(
            canEnableTvAudiobooksTab(menuItems = fullMenu),
        )
    }

    @Test
    fun audiobookToggleCanStayEnabledWhenItsMenuItemAlreadyExists() {
        val fullMenu = buildList {
            add(home)
            add(PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.AUDIOBOOKS))
            repeat(TvPrimaryMenuMaxItems - 2) { index ->
                add(PrimaryMenuItem.Library(index + 1, "Library ${index + 1}"))
            }
        }

        assertTrue(
            canEnableTvAudiobooksTab(menuItems = fullMenu),
        )
    }

    @Test
    fun effectiveMenuWithoutAudiobooksOverridesEnabledLegacyFallback() {
        val effectiveMenu = minimalTvMenu()

        assertFalse(
            resolvedTvAudiobooksTab(
                effectiveMenu = effectiveMenu,
                legacyFallback = true,
            ),
        )
    }

    @Test
    fun inheritedEffectiveMenuWithAudiobooksOverridesDisabledLegacyFallback() {
        val inheritedEffectiveMenu = PrimaryMenu(
            listOf(
                home,
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.FOR_YOU),
            ),
        )

        assertTrue(
            resolvedTvAudiobooksTab(
                effectiveMenu = inheritedEffectiveMenu,
                legacyFallback = false,
            ),
        )
    }

    @Test
    fun legacyAudiobookFlagIsUsedOnlyWithoutAnEffectiveMenu() {
        assertTrue(
            resolvedTvAudiobooksTab(
                effectiveMenu = null,
                legacyFallback = true,
            ),
        )
        assertFalse(
            resolvedTvAudiobooksTab(
                effectiveMenu = null,
                legacyFallback = false,
            ),
        )
    }

    @Test
    fun olderServerKeepsAudiobookToggleLocalEnabledAndIgnoresCachedMenu() {
        val cachedMenu = minimalTvMenu()
        val mutation = prepareTvAudiobookToggleMutation(
            customizationAvailable = false,
            currentOverride = cachedMenu,
            inheritedMenu = null,
            enabled = true,
        )
        val state = TvSettingsViewModel.UiState(
            uiCustomizationSupport = false,
            legacyShowAudiobooksTab = mutation.legacyValue,
            primaryMenuOverride = cachedMenu,
        )

        assertTrue(state.showAudiobooksTab)
        assertTrue(tvAudiobookToggleEnabled(state))
        assertNull(mutation.primaryMenu)
    }

    @Test
    fun supportedServerRoutesAudiobookToggleThroughSyncedMenu() {
        val current = minimalTvMenu()
        val mutation = prepareTvAudiobookToggleMutation(
            customizationAvailable = true,
            currentOverride = current,
            inheritedMenu = current,
            enabled = true,
        )
        val menuWrite = assertNotNull(mutation.primaryMenu)
        val state = TvSettingsViewModel.UiState(
            uiCustomizationSupport = true,
            customizationLibrariesResolved = true,
            legacyShowAudiobooksTab = mutation.legacyValue,
            primaryMenuOverride = menuWrite,
            menuItems = menuWrite.items,
        )

        assertTrue(state.showAudiobooksTab)
        assertTrue(tvAudiobookToggleEnabled(state))
        assertTrue(
            PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.AUDIOBOOKS) in menuWrite.items,
        )
    }

    @Test
    fun unresolvedCapabilityKeepsAudiobookToggleDisabled() {
        val state = TvSettingsViewModel.UiState(
            uiCustomizationSupport = null,
            legacyShowAudiobooksTab = true,
        )

        assertTrue(state.showAudiobooksTab)
        assertFalse(tvAudiobookToggleEnabled(state))
    }

    @Test
    fun generalInitialFocusDefersOnlyWhileCapabilityIsUnknown() {
        assertEquals(
            TvGeneralInitialFocusTarget.DEFER,
            tvGeneralInitialFocusTarget(
                TvSettingsViewModel.UiState(uiCustomizationSupport = null),
            ),
        )
        assertEquals(
            TvGeneralInitialFocusTarget.READ_ONLY,
            tvGeneralInitialFocusTarget(
                TvSettingsViewModel.UiState(uiCustomizationSupport = null),
                allowUnresolvedReadOnly = true,
            ),
        )
        assertEquals(
            TvGeneralInitialFocusTarget.AUDIOBOOKS,
            tvGeneralInitialFocusTarget(
                TvSettingsViewModel.UiState(uiCustomizationSupport = false),
            ),
        )
        assertEquals(
            TvGeneralInitialFocusTarget.CARD_PRESET,
            tvGeneralInitialFocusTarget(
                TvSettingsViewModel.UiState(uiCustomizationSupport = true),
            ),
        )
    }

    @Test
    fun generalInitialFocusChoosesOneEnabledSupportedRow() {
        val supported = TvSettingsViewModel.UiState(
            uiCustomizationSupport = true,
            customizationLibrariesResolved = true,
        )
        assertEquals(
            TvGeneralInitialFocusTarget.AUDIOBOOKS,
            tvGeneralInitialFocusTarget(supported),
        )

        val fullMenu = List(TvPrimaryMenuMaxItems) { index ->
            PrimaryMenuItem.Library(index + 1, "Library ${index + 1}")
        }
        assertEquals(
            TvGeneralInitialFocusTarget.LAYOUT_PRESET,
            tvGeneralInitialFocusTarget(supported.copy(menuItems = fullMenu)),
        )

        // The sync action precedes Top Menu and must be the sole requester
        // even when the audiobook row would otherwise also be enabled.
        assertEquals(
            TvGeneralInitialFocusTarget.SYNC,
            tvGeneralInitialFocusTarget(
                supported.copy(cardPresentationUsesDeviceOverride = true),
            ),
        )
    }

    @Test
    fun toggleDirectionChangesTheEffectiveMenuInsteadOfTheContradictingLegacyFlag() {
        val effectiveMenu = minimalTvMenu()
        val checked = resolvedTvAudiobooksTab(
            effectiveMenu = effectiveMenu,
            legacyFallback = true,
        )

        val write = assertNotNull(
            prepareTvAudiobookMenuWrite(
                currentOverride = effectiveMenu,
                inheritedMenu = effectiveMenu,
                enabled = !checked,
            ),
        )

        assertTrue(resolvedTvAudiobooksTab(write, legacyFallback = true))
        assertTrue(
            write.items.contains(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
            ),
        )
    }

    @Test
    fun disablingAnAuthoredAudiobookItemChangesTheEffectiveMenu() {
        val effectiveMenu = PrimaryMenu(
            listOf(
                home,
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.FOR_YOU),
            ),
        )
        val checked = resolvedTvAudiobooksTab(
            effectiveMenu = effectiveMenu,
            legacyFallback = false,
        )

        val write = assertNotNull(
            prepareTvAudiobookMenuWrite(
                currentOverride = effectiveMenu,
                inheritedMenu = effectiveMenu,
                enabled = !checked,
            ),
        )

        assertFalse(resolvedTvAudiobooksTab(write, legacyFallback = false))
    }

    @Test
    fun unresolvedLibrariesCannotMaterializeAProfileMenuPreset() {
        assertNull(
            prepareTvNavigationPresetMutation(
                preset = TvSettingsViewModel.NavigationPreset.MEDIA_FIRST,
                libraries = emptyList(),
                showAudiobooks = false,
                librariesResolved = false,
            ),
        )
    }

    @Test
    fun standardPresetClearsTheOverrideInsteadOfFreezingCurrentLibraries() {
        assertEquals(
            TvNavigationPresetMutation.ResetPrimaryMenu,
            prepareTvNavigationPresetMutation(
                preset = TvSettingsViewModel.NavigationPreset.STANDARD,
                libraries = listOf(
                    UserLibrary(id = 1, name = "Movies", type = "movies", sortOrder = 0),
                ),
                showAudiobooks = false,
                librariesResolved = true,
            ),
        )
    }

    @Test
    fun resolvedMediaFirstPresetIncludesAvailableMediaBeforeHome() {
        val mutation = assertIs<TvNavigationPresetMutation.SetPrimaryMenu>(
            prepareTvNavigationPresetMutation(
                preset = TvSettingsViewModel.NavigationPreset.MEDIA_FIRST,
                libraries = listOf(
                    UserLibrary(id = 1, name = "Movies", type = "movies", sortOrder = 0),
                ),
                showAudiobooks = false,
                librariesResolved = true,
            ),
        )

        assertEquals(
            listOf(
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.MOVIES),
                home,
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.FOR_YOU),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
            mutation.value.items,
        )
    }

    @Test
    fun inheritedAudiobookToggleMaterializesAFamilyMenu() {
        val inherited = PrimaryMenu(
            listOf(
                home,
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.FOR_YOU),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
        )

        val write = assertNotNull(
            prepareTvAudiobookMenuWrite(
                currentOverride = null,
                inheritedMenu = inherited,
                enabled = true,
            ),
        )

        assertEquals(
            listOf(
                home,
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.AUDIOBOOKS),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.FOR_YOU),
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.CALENDAR),
            ),
            write.items,
        )
    }

    @Test
    fun inheritedDisabledAudiobookStateIsStillMaterializedForFamilySync() {
        val inherited = PrimaryMenu(
            listOf(
                home,
                PrimaryMenuItem.Builtin(PrimaryMenuBuiltin.FOR_YOU),
            ),
        )

        assertEquals(
            inherited,
            prepareTvAudiobookMenuWrite(
                currentOverride = null,
                inheritedMenu = inherited,
                enabled = false,
            ),
        )
    }
}
