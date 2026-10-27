package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.siloserver.silo.common.settings.CardPresentationSource
import org.siloserver.silo.common.settings.CardPresentationSupport
import org.siloserver.silo.common.settings.CardPresentationUiState
import org.siloserver.silo.model.settings.CardPosterSize

/**
 * Interface → "Cards & Posters" (Apple `InterfaceCustomizationView`) — the
 * cross-client `ui.card_presentation` preference (poster size).
 *
 * Writes go to `profile_client` so the choice roams among this profile's
 * phones/tablets, unless "Only this device" pins a `profile_device` override.
 * Servers that predate the setting get a single read-only upgrade notice.
 */
@Composable
fun MediaCardsSettings(
    state: CardPresentationUiState,
    onPosterSizeSelected: (CardPosterSize) -> Unit,
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
            "Poster size syncs with other phones and tablets on this profile unless Only This Device is on."
        },
        modifier = modifier,
    ) {
        if (unsupported) {
            SettingsProse(body = "Update your Silo server to customize media cards.")
            return@SettingsSection
        }

        val presentation = state.presentation
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
