package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import org.siloserver.silo.android.R
import org.siloserver.silo.common.settings.ConnectionSpeedMessage
import org.siloserver.silo.common.settings.ConnectionSpeedReading
import org.siloserver.silo.common.settings.ConnectionSpeedSkip
import org.siloserver.silo.common.settings.ConnectionSpeedText
import org.siloserver.silo.common.settings.ConnectionSpeedUiState
import org.siloserver.silo.common.settings.LearnedLimitText
import org.siloserver.silo.common.settings.formatRate
import org.siloserver.silo.common.settings.connectionRate
import org.siloserver.silo.common.settings.connectionSpeedText

/**
 * Playback → Connection (the buffering-quality spec's *Reporting and
 * display*, phase 2): the measured speed with **Test speed**, and Auto's
 * budget in the footer. Shown only while the server measures speed. On
 * cellular and metered networks the row says speed tests are skipped and
 * offers no test. The copy is the spec's.
 */
@Composable
fun PlaybackConnectionSection(
    state: ConnectionSpeedUiState,
    onTestSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // "measured 3 minutes ago" moves on while the page is open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    SettingsSection(
        title = stringResource(R.string.settings_connection_title),
        footer = state.autoBudgetKbps?.let { stringResource(R.string.settings_connection_budget, rateLabel(it)) },
        modifier = modifier,
    ) {
        val skipped = state.skipped
        SettingsRow(
            label = stringResource(R.string.settings_connection_speed),
            description = if (skipped != null) skipLabel(skipped) else speedLabel(state.reading, now),
        ) {
            if (skipped == null) {
                TextButton(onClick = onTestSpeed, enabled = !state.testing) {
                    Text(
                        stringResource(
                            if (state.testing) R.string.settings_connection_testing else R.string.settings_connection_test,
                        ),
                    )
                }
            }
        }
        state.message?.takeIf { skipped == null }?.let { SettingsProse(body = messageLabel(it)) }
    }
}

/** "48 Mbps · measured 3 minutes ago on Wi-Fi", or "Not measured yet". */
@Composable
private fun speedLabel(reading: ConnectionSpeedReading?, nowEpochMs: Long): String {
    reading ?: return stringResource(R.string.settings_connection_not_measured)
    val text = connectionSpeedText(reading.kbps, reading.measuredAtEpochMs, nowEpochMs, reading.transport)
    val rate = rateLabel(text.rate, text.unit)
    val transport = stringResource(
        when (text.transport) {
            ConnectionSpeedText.Transport.WIFI -> R.string.settings_connection_transport_wifi
            ConnectionSpeedText.Transport.ETHERNET -> R.string.settings_connection_transport_ethernet
            ConnectionSpeedText.Transport.CELLULAR -> R.string.settings_connection_transport_cellular
            ConnectionSpeedText.Transport.OTHER -> R.string.settings_connection_transport_other
        },
    )
    val age = text.age ?: return stringResource(R.string.settings_connection_measured_undated, rate, transport)
    val ago = when (age.unit) {
        ConnectionSpeedText.AgeUnit.JUST_NOW -> stringResource(R.string.settings_connection_age_just_now)
        ConnectionSpeedText.AgeUnit.MINUTES -> pluralStringResource(R.plurals.settings_connection_age_minutes, age.count, age.count)
        ConnectionSpeedText.AgeUnit.HOURS -> pluralStringResource(R.plurals.settings_connection_age_hours, age.count, age.count)
        ConnectionSpeedText.AgeUnit.DAYS -> pluralStringResource(R.plurals.settings_connection_age_days, age.count, age.count)
    }
    return stringResource(R.string.settings_connection_measured, rate, ago, transport)
}

@Composable
private fun rateLabel(kbps: Int): String = connectionRate(kbps).let { (rate, unit) -> rateLabel(rate, unit) }

@Composable
private fun rateLabel(rate: Double, unit: LearnedLimitText.RateUnit): String = stringResource(
    if (unit == LearnedLimitText.RateUnit.MBPS) R.string.settings_rate_mbps else R.string.settings_rate_kbps,
    formatRate(rate),
)

@Composable
private fun skipLabel(skip: ConnectionSpeedSkip): String = stringResource(
    when (skip) {
        ConnectionSpeedSkip.CELLULAR -> R.string.settings_connection_skipped_cellular
        ConnectionSpeedSkip.METERED_CELLULAR_QUALITY -> R.string.settings_connection_skipped_metered_cellular_quality
        ConnectionSpeedSkip.METERED -> R.string.settings_connection_skipped_metered
    },
)

@Composable
private fun messageLabel(message: ConnectionSpeedMessage): String = when (message) {
    is ConnectionSpeedMessage.TryAgainIn ->
        pluralStringResource(R.plurals.settings_connection_try_again, message.seconds, message.seconds)
    ConnectionSpeedMessage.StopPlayback -> stringResource(R.string.settings_connection_stop_playback)
    ConnectionSpeedMessage.Failed -> stringResource(R.string.settings_connection_failed)
}
