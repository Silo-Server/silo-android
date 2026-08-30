package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.siloserver.silo.common.settings.CardPresentationSource
import org.siloserver.silo.common.settings.CardPresentationSupport
import org.siloserver.silo.common.settings.CardPresentationUiState
import org.siloserver.silo.model.settings.CardCaption
import org.siloserver.silo.model.settings.CardPosterSize
import org.siloserver.silo.model.settings.CardPresentationPreset

private const val CustomPresetLabel = "Custom"

/**
 * "Media Cards" section — the cross-client `ui.card_presentation` preference
 * (poster size + caption style, plus the client-side presets over the pair).
 *
 * Writes go to `profile_client` so the choice roams among this profile's
 * phones/tablets, unless "Only this device" pins a `profile_device` override.
 * Servers that predate the setting get a single read-only upgrade notice.
 */
@Composable
fun MediaCardsSettings(
    state: CardPresentationUiState,
    onPresetSelected: (CardPresentationPreset) -> Unit,
    onPosterSizeSelected: (CardPosterSize) -> Unit,
    onCaptionSelected: (CardCaption) -> Unit,
    onDeviceOnlyChanged: (Boolean) -> Unit,
    onUseProfileDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSection(title = "Media Cards", modifier = modifier) {
        if (state.support == CardPresentationSupport.Unsupported) {
            SettingsProse(body = "Update your Silo server to customize media cards.")
            return@SettingsSection
        }

        val presentation = state.presentation
        val activePreset = presentation.preset
        // "Custom" is a synthetic display state (the pair matches no preset),
        // not a choice — it appears in the menu only while it is active.
        val presetOptions = CardPresentationPreset.entries.map { it.displayName } +
            listOfNotNull(CustomPresetLabel.takeIf { activePreset == null })
        SettingsDropdownRow(
            label = "Preset",
            description = "A starting point for poster size and captions.",
            value = activePreset?.displayName ?: CustomPresetLabel,
            options = presetOptions,
            onOptionSelected = { label ->
                CardPresentationPreset.entries
                    .firstOrNull { it.displayName == label }
                    ?.let(onPresetSelected)
            },
        )

        SettingsDropdownRow(
            label = "Poster size",
            description = "How large posters render in rows and grids.",
            value = presentation.posterSize.displayName,
            options = CardPosterSize.entries.map { it.displayName },
            onOptionSelected = { label ->
                CardPosterSize.entries
                    .firstOrNull { it.displayName == label }
                    ?.let(onPosterSizeSelected)
            },
        )

        SettingsDropdownRow(
            label = "Captions",
            description = "What shows beneath each poster.",
            value = presentation.caption.displayName,
            options = CardCaption.entries.map { it.displayName },
            onOptionSelected = { label ->
                CardCaption.entries
                    .firstOrNull { it.displayName == label }
                    ?.let(onCaptionSelected)
            },
        )

        val deviceOnly = state.source == CardPresentationSource.DeviceOverride
        SettingsSwitchRow(
            label = "Only this device",
            description = "Keep these choices on this device instead of syncing them.",
            checked = deviceOnly,
            onCheckedChange = onDeviceOnlyChanged,
        )

        if (!deviceOnly && state.source == CardPresentationSource.ClientFamily) {
            SettingsNavigationRow(
                label = "Use profile default",
                description = "Clear this device family's choice and follow the profile.",
                onClick = onUseProfileDefault,
                showChevron = false,
            )
        }

        SettingsProse(
            body = "Choices sync with other phones (or tablets) signed into this " +
                "profile unless 'Only this device' is on.",
        )
    }
}
