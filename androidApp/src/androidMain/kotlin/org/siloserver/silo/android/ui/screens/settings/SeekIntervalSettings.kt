package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.siloserver.silo.common.settings.SeekIntervalSettingsUiState
import org.siloserver.silo.model.settings.SeekDirection
import org.siloserver.silo.model.settings.SeekIntervalSupport
import org.siloserver.silo.model.settings.SeekIntervals
import org.siloserver.silo.model.settings.SeekMedia

private const val CHECKING_SERVER = "Checking whether this server stores skip intervals for this profile…"

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
        SeekMedia.Video -> "Video"
        SeekMedia.Audiobook -> "Audiobooks"
    }
    val supported = state.support != SeekIntervalSupport.Unsupported && !state.checking && !state.checkFailed
    val footer = when {
        !supported -> null
        media == SeekMedia.Video ->
            "Used by the on-screen skip buttons and double-tap. Applies to every device signed in to this profile."
        else -> "Used by the audiobook player's skip buttons. Applies to every device signed in to this profile."
    }
    SettingsSection(title = title, footer = footer, modifier = modifier) {
        if (state.support == SeekIntervalSupport.Unsupported) {
            SettingsProse(
                body = when (media) {
                    SeekMedia.Video ->
                        "This server uses fixed skip intervals for video. Update the server to choose them " +
                            "for this profile."
                    SeekMedia.Audiobook ->
                        "This server does not sync skip intervals. Set them from the audiobook player; " +
                            "they stay on this device."
                },
            )
            return@SettingsSection
        }
        if (state.checking) {
            // No values yet: showing the contract defaults would misstate what
            // the players use until the server answers.
            SettingsProse(body = CHECKING_SERVER)
            return@SettingsSection
        }
        if (state.checkFailed) {
            SettingsProse(
                body = when (media) {
                    SeekMedia.Video ->
                        "Couldn't check whether this server stores skip intervals for this profile. " +
                            "Players use the standard intervals until it answers."
                    SeekMedia.Audiobook ->
                        "Couldn't check whether this server stores skip intervals for this profile. " +
                            "Until it answers, intervals set in the audiobook player stay on this device."
                },
            )
            SettingsNavigationRow(label = "Try Again", onClick = onRetry)
            return@SettingsSection
        }

        val pair = when (media) {
            SeekMedia.Video -> state.video
            SeekMedia.Audiobook -> state.audiobook
        }
        val options = state.choices.map(SeekIntervals::label)
        SeekDirection.entries.forEach { direction ->
            SettingsDropdownRow(
                label = if (direction == SeekDirection.Back) "Skip Back" else "Skip Forward",
                value = SeekIntervals.label(pair.seconds(direction)),
                options = options,
                onOptionSelected = { label ->
                    state.choices.firstOrNull { SeekIntervals.label(it) == label }
                        ?.let { onIntervalSelected(media, direction, it) }
                },
            )
        }

        if (media == SeekMedia.Audiobook) {
            if (state.showLegacyImport) {
                SettingsNavigationRow(
                    label = "Use This Device's Audiobook Intervals",
                    description = "${state.legacyAudiobookSummary}. Uploads them to this profile.",
                    value = if (state.importInProgress) "Importing" else null,
                    onClick = onImportLegacyAudiobook,
                    enabled = !state.importInProgress,
                )
            }
            state.importMessage?.let { SettingsProse(body = it) }
        }
        state.saveErrorFor(media)?.let { SettingsProse(body = it) }
    }
}
