package org.siloserver.silo.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R
import org.siloserver.silo.common.settings.ConnectionSpeedUiState
import org.siloserver.silo.common.settings.connectionRateLabel
import org.siloserver.silo.common.settings.connectionSpeedLabel
import org.siloserver.silo.common.settings.connectionSpeedMessageLabel
import org.siloserver.silo.common.settings.rememberMinuteClock

/**
 * Playback → Connection (the buffering-quality spec's *Reporting and
 * display*, phase 2): a focusable **Connection speed** row that runs Test
 * speed, the measured speed under it, then Auto's budget. Shown only while the
 * server measures speed. A TV has no Cellular quality settings; on a metered
 * network the note that speed tests are skipped replaces the speed, and Test
 * speed and the budget line hide. The copy is the spec's.
 */
@Composable
internal fun TvConnectionSettingsGroup(state: ConnectionSpeedUiState, onTestSpeed: () -> Unit) {
    val now by rememberMinuteClock()
    SettingsGroup(title = stringResource(R.string.settings_connection_title)) {
        if (state.skipped != null) {
            SettingsInfoRow(label = stringResource(R.string.settings_connection_speed), value = "")
            SettingsFooterText(text = stringResource(R.string.settings_connection_skipped_metered))
        } else {
            // Stays enabled while a test runs, so focus never drops off it; a
            // second press is ignored.
            SettingsValueRow(
                label = stringResource(R.string.settings_connection_speed),
                value = stringResource(
                    if (state.testing) R.string.settings_connection_testing else R.string.settings_connection_test,
                ),
                onClick = onTestSpeed,
            )
            SettingsFooterText(text = connectionSpeedLabel(state.reading, now))
            state.message?.let { SettingsFooterText(text = connectionSpeedMessageLabel(it)) }
            state.autoBudgetKbps?.let {
                SettingsFooterText(text = stringResource(R.string.settings_connection_budget, connectionRateLabel(it)))
            }
        }
    }
}
