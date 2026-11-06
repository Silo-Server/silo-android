package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.settings.SeekIntervalSettingsUiState
import org.siloserver.silo.model.settings.SeekDirection
import org.siloserver.silo.model.settings.SeekIntervalSupport
import org.siloserver.silo.model.settings.SeekIntervals
import org.siloserver.silo.model.settings.SeekMedia



/**
 * One "Video" or "Audiobooks" group of the profile-wide skip intervals
 * (settings revision 9), with the Apple pages' labels and footers. On an older
 * server the group explains the legacy behavior instead of offering pickers
 * that could not be saved.
 */
@Composable
fun SeekIntervalSettings(
    media: SeekMedia,
    state: SeekIntervalSettingsUiState,
    onIntervalSelected: (SeekMedia, SeekDirection, Int) -> Unit,
    onImportLegacyAudiobook: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (media) {
        SeekMedia.Video -> stringResource(CommonR.string.settings_section_video)
        SeekMedia.Audiobook -> stringResource(CommonR.string.settings_seekIntervals_audiobooks)
    }
    val supported = state.support != SeekIntervalSupport.Unsupported && !state.checking && !state.checkFailed
    val footer = when {
        !supported -> null
        media == SeekMedia.Video -> stringResource(CommonR.string.settings_seekIntervals_videoFooter)
        else -> stringResource(CommonR.string.settings_skipIntervals_footerAudiobook)
    }
    SettingsSection(title = title, footer = footer, modifier = modifier) {
        if (state.support == SeekIntervalSupport.Unsupported) {
            SettingsProse(
                body = when (media) {
                    SeekMedia.Video -> stringResource(CommonR.string.settings_skipIntervals_unsupportedVideo)
                    SeekMedia.Audiobook -> stringResource(CommonR.string.settings_seekIntervals_unsupportedAudiobook)
                },
            )
            return@SettingsSection
        }
        if (state.checking) {
            // No values yet: showing the contract defaults would misstate what
            // the players use until the server answers.
            SettingsProse(body = stringResource(CommonR.string.settings_skipIntervals_checking))
            return@SettingsSection
        }
        if (state.checkFailed) {
            SettingsProse(
                body = when (media) {
                    SeekMedia.Video -> stringResource(CommonR.string.settings_skipIntervals_unavailableVideo)
                    SeekMedia.Audiobook -> stringResource(CommonR.string.settings_seekIntervals_checkFailedAudiobook)
                },
            )
            SettingsNavigationRow(label = stringResource(CommonR.string.settings_skipIntervals_tryAgain), onClick = onRetry)
            return@SettingsSection
        }

        val pair = when (media) {
            SeekMedia.Video -> state.video
            SeekMedia.Audiobook -> state.audiobook
        }
        val options = state.choices.map(SeekIntervals::label)
        SeekDirection.entries.forEach { direction ->
            val value = SeekIntervals.label(pair.seconds(direction))
            SettingsIndexedDropdownRow(
                label = stringResource(
                    if (direction == SeekDirection.Back) CommonR.string.settings_control_skipBack else CommonR.string.settings_control_skipForward,
                ),
                value = value,
                options = options,
                selectedIndex = options.indexOf(value),
                onIndexSelected = { onIntervalSelected(media, direction, state.choices[it]) },
            )
        }

        if (media == SeekMedia.Audiobook) {
            if (state.showLegacyImport) {
                SettingsNavigationRow(
                    label = stringResource(CommonR.string.settings_seekIntervals_importLegacy),
                    description = stringResource(CommonR.string.settings_skipIntervals_importFooter, state.legacyAudiobookSummary),
                    value = if (state.importInProgress) stringResource(CommonR.string.settings_seekIntervals_importing) else null,
                    onClick = onImportLegacyAudiobook,
                    enabled = !state.importInProgress,
                )
            }
            state.importMessage?.let { SettingsProse(body = it) }
        }
        state.saveErrorFor(media)?.let { SettingsProse(body = it) }
    }
}
