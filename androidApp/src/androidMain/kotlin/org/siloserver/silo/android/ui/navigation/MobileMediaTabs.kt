package org.siloserver.silo.android.ui.navigation

val Tab.isUtilityTab: Boolean
    get() = this == Tab.Downloads

// Apple-aligned shell: Home · Libraries · For You · Calendar · Downloads.
// Downloads is a stable destination from the first frame; its screen owns the
// empty and unavailable states instead of making the navigation bar reflow
// after capability or local-record hydration completes.
fun visibleMobileTabs(): List<Tab> = buildList {
    add(Tab.Home)
    add(Tab.Libraries)
    add(Tab.ForYou)
    add(Tab.Calendar)
    add(Tab.Downloads)
}

fun fallbackMobileTab(
    visibleTabs: List<Tab>,
    defaultTab: Tab,
): Tab? {
    if (defaultTab in visibleTabs) return defaultTab
    return visibleTabs.firstOrNull { !it.isUtilityTab } ?: visibleTabs.firstOrNull()
}
