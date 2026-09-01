package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.model.navigation.MediaMode
import org.siloserver.silo.model.navigation.MediaModeCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileMediaTabsTest {

    // Apple-aligned shell stays structurally fixed while capabilities and
    // download records hydrate, so the selected pill never shifts underneath
    // the user after the first authenticated frame.
    private val baseLabels = listOf("Home", "Libraries", "For You", "Calendar", "Downloads")

    @Test
    fun fixedTabsIncludeDownloadsWhenPresent() {
        val tabs = visibleMobileTabs(
            capabilities = MediaModeCapabilities(listOf(MediaMode.Video)),
            showDownloads = true,
        )

        assertEquals(baseLabels, tabs.map { it.label })
    }

    @Test
    fun tabsAreFixedRegardlessOfLibraryTypes() {
        assertEquals(
            baseLabels,
            visibleMobileTabs(MediaModeCapabilities(listOf(MediaMode.Audio)), showDownloads = false)
                .map { it.label },
        )
        assertEquals(
            baseLabels,
            visibleMobileTabs(MediaModeCapabilities(listOf(MediaMode.Reading)), showDownloads = false)
                .map { it.label },
        )
        assertEquals(
            baseLabels,
            visibleMobileTabs(MediaModeCapabilities(emptyList()), showDownloads = false)
                .map { it.label },
        )
    }

    @Test
    fun downloadsKeepsItsStableSlotBeforeRecordsHydrate() {
        val tabs = visibleMobileTabs(
            capabilities = MediaModeCapabilities(listOf(MediaMode.Video, MediaMode.Audio)),
            showDownloads = false,
        )

        assertEquals(baseLabels, tabs.map { it.label })
        assertTrue(Tab.Downloads in tabs)
    }

    @Test
    fun choosesFirstVisibleMediaTabBeforeDownloads() {
        assertEquals(
            Tab.Home,
            fallbackMobileTab(
                visibleTabs = listOf(Tab.Home, Tab.Libraries, Tab.Downloads),
                defaultTab = Tab.ForYou,
            ),
        )
    }

    @Test
    fun keepsCurrentTabWhenStillVisible() {
        assertEquals(
            Tab.Downloads,
            fallbackMobileTab(
                visibleTabs = listOf(Tab.Home, Tab.Downloads),
                defaultTab = Tab.Downloads,
            ),
        )
        assertTrue(Tab.Downloads.isUtilityTab)
    }
}
