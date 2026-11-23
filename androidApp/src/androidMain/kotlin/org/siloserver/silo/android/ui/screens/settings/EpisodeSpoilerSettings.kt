package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.settings.SettingsSaveError
import org.siloserver.silo.common.settings.text

/**
 * Spoiler protection for unwatched episodes (see EpisodeSpoilers.MIN_CONTRACT_REVISION). Both
 * switches are profile-wide, so the section says they follow the profile to
 * every device. Shown only when the server supports the keys.
 */
@Composable
fun EpisodeSpoilerSettings(
    hideImages: Boolean,
    hideOverviews: Boolean,
    onHideImagesChanged: (Boolean) -> Unit,
    onHideOverviewsChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    saveError: SettingsSaveError? = null,
) {
    SettingsSection(title = stringResource(CommonR.string.settings_section_spoilers), modifier = modifier) {
        SettingsSwitchRow(
            label = stringResource(CommonR.string.settingsManifest_catalog_hide_unwatched_episode_images_label),
            description = stringResource(CommonR.string.settingsManifest_catalog_hide_unwatched_episode_images_description),
            checked = hideImages,
            onCheckedChange = onHideImagesChanged,
        )
        SettingsSwitchRow(
            label = stringResource(CommonR.string.settingsManifest_catalog_hide_unwatched_episode_overviews_label),
            description = stringResource(CommonR.string.settingsManifest_catalog_hide_unwatched_episode_overviews_description),
            checked = hideOverviews,
            onCheckedChange = onHideOverviewsChanged,
        )
        SettingsProse(
            body = stringResource(CommonR.string.settings_spoilers_footer),
        )
        saveError?.let { SettingsProse(body = it.text()) }
    }
}
