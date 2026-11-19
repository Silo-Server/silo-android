package org.siloserver.silo.android.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.settings.TitleArtState
import org.siloserver.silo.common.settings.TitleArtStore
import org.siloserver.silo.common.settings.text

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

    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    SettingsSection(
        title = stringResource(CommonR.string.settings_titleArt_title),
        footer = stringResource(titleArtFooter(state, tablet)),
    ) {
        SettingsSwitchRow(
            label = stringResource(CommonR.string.settingsManifest_ui_title_art_label),
            checked = state.showTitleArt,
            onCheckedChange = store::setShowTitleArt,
            enabled = state.canEdit,
        )
        SettingsSwitchRow(
            label = stringResource(CommonR.string.settings_titleArt_applyToAll),
            checked = state.appliesToAllDevices,
            onCheckedChange = store::setAppliesToAllDevices,
            enabled = state.canEdit,
        )
        saveError?.let { SettingsProse(body = it.text()) }
    }
}

/** The footer for where the choice applies, naming this device a phone or a [tablet]. */
@StringRes
internal fun titleArtFooter(state: TitleArtState, tablet: Boolean): Int = when {
    !state.appliesToAllDevices ->
        if (tablet) CommonR.string.settings_titleArt_footer_thisTablet else CommonR.string.settings_titleArt_footer_thisPhone
    state.showTitleArt ->
        if (tablet) CommonR.string.settings_titleArt_footer_allOnTablet else CommonR.string.settings_titleArt_footer_allOnPhone
    else ->
        if (tablet) CommonR.string.settings_titleArt_footer_allOffTablet else CommonR.string.settings_titleArt_footer_allOffPhone
}
