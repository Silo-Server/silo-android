package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
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
        title = stringResource(CommonR.string.settings_cards_title),
        footer = if (unsupported) {
            null
        } else {
            stringResource(CommonR.string.settings_cards_footerPhone)
        },
        modifier = modifier,
    ) {
        if (unsupported) {
            SettingsProse(body = stringResource(CommonR.string.settings_cards_unsupported))
            return@SettingsSection
        }

        val presentation = state.presentation
        SettingsIndexedDropdownRow(
            label = stringResource(CommonR.string.settings_cards_posterSize),
            value = presentation.posterSize.displayName,
            options = CardPosterSize.entries.map { it.displayName },
            selectedIndex = CardPosterSize.entries.indexOf(presentation.posterSize),
            onIndexSelected = { onPosterSizeSelected(CardPosterSize.entries[it]) },
        )

        val deviceOnly = state.source == CardPresentationSource.DeviceOverride
        SettingsSwitchRow(
            label = stringResource(CommonR.string.settings_cards_onlyThisDevice),
            checked = deviceOnly,
            onCheckedChange = onDeviceOnlyChanged,
        )

        if (!deviceOnly && state.source == CardPresentationSource.ClientFamily) {
            SettingsNavigationRow(
                label = stringResource(CommonR.string.settings_cards_useProfileDefault),
                onClick = onUseProfileDefault,
                showChevron = false,
            )
        }
    }
}
