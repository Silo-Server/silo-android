package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
    saveError: String? = null,
) {
    SettingsSection(title = "Spoilers", modifier = modifier) {
        SettingsSwitchRow(
            label = "Blur unwatched episode images",
            description = "Blur an episode's thumbnail until you start watching it, so the image does not give away the story.",
            checked = hideImages,
            onCheckedChange = onHideImagesChanged,
        )
        SettingsSwitchRow(
            label = "Hide unwatched episode descriptions",
            description = "Hide an episode's description until you start watching it.",
            checked = hideOverviews,
            onCheckedChange = onHideOverviewsChanged,
        )
        SettingsProse(
            body = "Applies to episodes you have not started, on every device that uses this profile.",
        )
        saveError?.let { SettingsProse(body = it) }
    }
}
