package org.siloserver.silo.android.ui.screens.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.AppLanguageStore
import org.siloserver.silo.common.i18n.appLanguageOptions

/**
 * The App language group of the Interface page (`ui.app_language`). Saves this
 * device's choice for the profile, the same value as Settings → Apps → Silo →
 * Language. Renders nothing until the server confirms the key (revision 28).
 */
@Composable
internal fun AppLanguageSettingsSection(store: AppLanguageStore) {
    val state by store.state.collectAsState()
    if (!state.supported) return
    val activity = LocalActivity.current ?: return
    val title = stringResource(CommonR.string.settings_app_language_title)
    val options = appLanguageOptions(stringResource(CommonR.string.settings_app_language_match_device), state.choice)
    val selected = options.firstOrNull { it.id == state.choice.orEmpty() } ?: options.first()

    SettingsSection(title = title, footer = stringResource(CommonR.string.settings_app_language_footer)) {
        SettingsDropdownRow(
            label = title,
            value = selected.label,
            options = options.map { it.label },
            onOptionSelected = { label ->
                options.firstOrNull { it.label == label }
                    ?.takeIf { it != selected }
                    ?.let { store.choose(activity, it.id.ifEmpty { null }) }
            },
        )
    }
}
