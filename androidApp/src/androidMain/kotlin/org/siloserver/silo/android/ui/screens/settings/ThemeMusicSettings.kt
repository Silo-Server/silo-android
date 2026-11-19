package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.model.settings.PlaybackSettingsKeys

/**
 * "Theme music" and "Loop theme music" for title pages, as the web app's
 * device settings offer them. Each switch writes this device's value; one
 * set for the profile elsewhere applies until then.
 */
@Composable
internal fun ThemeMusicSettingsSection(store: PlayerSettingsStore) {
    val enabled by store.themeMusicEnabledFlow.collectAsState(initial = false)
    val loop by store.themeMusicLoopFlow.collectAsState(initial = false)
    val overrides by store.deviceOverrideKeysFlow.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    fun overridden(key: String) = PlaybackSettingsKeys.hasDeviceOverride(overrides, key)

    SettingsSection(
        title = stringResource(CommonR.string.settings_section_themeMusic),
        footer = stringResource(CommonR.string.settings_themeMusic_footer),
    ) {
        ProfileLayeredSwitchRow(
            label = stringResource(CommonR.string.settings_themeMusic_enabled),
            checked = enabled,
            overridden = overridden(PlaybackSettingsKeys.ThemeMusicEnabled),
            onCheckedChange = { scope.launch { store.setThemeMusicEnabled(it) } },
            onUseProfileSetting = { scope.launch { store.resetDeviceSetting(PlaybackSettingsKeys.ThemeMusicEnabled) } },
        )
        ProfileLayeredSwitchRow(
            label = stringResource(CommonR.string.settings_control_themeMusicLoop),
            checked = loop,
            overridden = overridden(PlaybackSettingsKeys.ThemeMusicLoop),
            onCheckedChange = { scope.launch { store.setThemeMusicLoop(it) } },
            onUseProfileSetting = { scope.launch { store.resetDeviceSetting(PlaybackSettingsKeys.ThemeMusicLoop) } },
        )
    }
}
