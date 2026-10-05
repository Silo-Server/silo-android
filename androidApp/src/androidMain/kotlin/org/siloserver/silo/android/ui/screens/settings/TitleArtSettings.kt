package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import org.siloserver.silo.common.settings.TitleArtState
import org.siloserver.silo.common.settings.TitleArtStore

/**
 * "Show title art" and its "Apply to all devices" companion, as the Title
 * Pages group of the Interface page. Renders nothing until the server has
 * confirmed the key (settings revision 16); older servers keep logos on, as
 * before. The switches stay disabled until this session's read lands.
 * [SettingsInterfaceScreen] refreshes the store when it opens.
 */
@Composable
internal fun TitleArtSettingsSection(store: TitleArtStore) {
    val state by store.state.collectAsState()
    val saveError by store.saveError.collectAsState()
    if (!state.isSupported) return

    val device = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "tablet" else "phone"
    SettingsSection(
        title = "Title Pages",
        footer = "Use logo artwork as the title when available. " + titleArtScopeFooter(state, device),
    ) {
        SettingsSwitchRow(
            label = "Show title art",
            checked = state.showTitleArt,
            onCheckedChange = store::setShowTitleArt,
            enabled = state.canEdit,
        )
        SettingsSwitchRow(
            label = "Apply to all devices",
            checked = state.appliesToAllDevices,
            onCheckedChange = store::setAppliesToAllDevices,
            enabled = state.canEdit,
        )
        saveError?.let { SettingsProse(body = it) }
    }
}

internal fun titleArtScopeFooter(state: TitleArtState, device: String): String =
    if (state.appliesToAllDevices) {
        "Title art is ${if (state.showTitleArt) "on" else "off"} on every device signed into " +
            "this profile. Changing it here changes it everywhere. Turn off “Apply to all " +
            "devices” to choose for this $device only."
    } else {
        "This choice only affects this $device. Your other devices keep their own setting."
    }
