package org.siloserver.silo.android.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileMediaTabsTest {

    // The mobile shell is fixed from its first frame; content and capability
    // hydration must not insert a tab later and reflow the navigation bar.
    private val fixedLabels = listOf("Home", "Libraries", "For You", "Calendar", "Downloads")

    @Test
    fun fixedTabsAlwaysIncludeDownloads() {
        val tabs = visibleMobileTabs()

        assertEquals(fixedLabels, tabs.map { it.label })
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
