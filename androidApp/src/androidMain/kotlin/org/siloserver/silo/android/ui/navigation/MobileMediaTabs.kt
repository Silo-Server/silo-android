package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.model.navigation.MediaModeCapabilities

val Tab.isUtilityTab: Boolean
    get() = this == Tab.Downloads

// Apple-aligned shell: Home · Libraries · For You · Calendar, plus a Downloads
// tab only when the user has downloads. There is no per-media-type tab —
// library content (video / audio / reading) is reached through the Libraries picker.
fun visibleMobileTabs(
    @Suppress("UNUSED_PARAMETER") capabilities: MediaModeCapabilities,
    @Suppress("UNUSED_PARAMETER") showDownloads: Boolean,
): List<Tab> = buildList {
    add(Tab.Home)
    add(Tab.Libraries)
    add(Tab.ForYou)
    add(Tab.Calendar)
    // Keep Downloads present from the first authenticated frame. Capability
    // and local-record hydration may finish later, but navigation must not
    // shift underneath the user while that optional data arrives.
    add(Tab.Downloads)
}

fun fallbackMobileTab(
    visibleTabs: List<Tab>,
    defaultTab: Tab,
): Tab? {
    if (defaultTab in visibleTabs) return defaultTab
    return visibleTabs.firstOrNull { !it.isUtilityTab } ?: visibleTabs.firstOrNull()
}
