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
 * Interface → "Cards & Posters" (Apple `InterfaceCustomizationView`) — the
 * cross-client `ui.card_presentation` preference (poster size + caption style,
 * plus the client-side presets over the pair).
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
    val unsupported = state.support == CardPresentationSupport.Unsupported
    SettingsSection(
        title = "Cards & Posters",
        footer = if (unsupported) {
            null
        } else {
            "Start with Balanced, Compact, Cinema, or Artwork Only, then fine-tune size and captions. These " +
                "choices sync with other phones and tablets on this profile unless Only This Device is on."
        },
        modifier = modifier,
    ) {
        if (unsupported) {
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
            value = activePreset?.displayName ?: CustomPresetLabel,
            options = presetOptions,
            onOptionSelected = { label ->
                CardPresentationPreset.entries
                    .firstOrNull { it.displayName == label }
                    ?.let(onPresetSelected)
            },
        )

        SettingsDropdownRow(
            label = "Poster Size",
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
            label = "Only This Device",
            checked = deviceOnly,
            onCheckedChange = onDeviceOnlyChanged,
        )

        if (!deviceOnly && state.source == CardPresentationSource.ClientFamily) {
            SettingsNavigationRow(
                label = "Use Profile Default",
                onClick = onUseProfileDefault,
                showChevron = false,
            )
        }
    }
}
