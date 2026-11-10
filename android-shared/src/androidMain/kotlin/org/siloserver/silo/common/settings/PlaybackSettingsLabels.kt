package org.siloserver.silo.common.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import org.siloserver.silo.common.R
import org.siloserver.silo.domain.player.BufferingQualityMode
import org.siloserver.silo.network.apiv2.PlaybackLearnedLimitV2

/*
 * The words of Settings → Playback's buffering-quality and Connection rows,
 * the same on phone and TV (the buffering-quality spec's *Reporting and
 * display*; the copy is the spec's). Each app lays the rows out.
 */

/** Now, in epoch milliseconds, moving on once a minute so "measured 3 minutes ago" keeps up while a page is open. */
@Composable
fun rememberMinuteClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(60_000)
        value = System.currentTimeMillis()
    }
}

/**
 * "48 Mbps · measured 3 minutes ago on Wi-Fi", "Faster than 115 Mbps · …" for
 * a lower bound, "Faster than 51 Mbps · seen during playback 2 hours ago" for
 * a playback sample, or "Not measured yet".
 */
@Composable
fun connectionSpeedLabel(reading: ConnectionSpeedReading?, nowEpochMs: Long): String {
    reading ?: return stringResource(R.string.settings_connection_notMeasured)
    val text = connectionSpeedText(reading.measuredAtEpochMs, nowEpochMs, reading.transport)
    val rate = connectionRateLabel(reading.kbps)
    val ago = text.age?.let { age ->
        when (age.unit) {
            ConnectionSpeedText.AgeUnit.JUST_NOW -> stringResource(R.string.settings_connection_age_justNow)
            ConnectionSpeedText.AgeUnit.MINUTES -> pluralStringResource(R.plurals.settings_connection_age_minutes, age.count, age.count)
            ConnectionSpeedText.AgeUnit.HOURS -> pluralStringResource(R.plurals.settings_connection_age_hours, age.count, age.count)
            ConnectionSpeedText.AgeUnit.DAYS -> pluralStringResource(R.plurals.settings_connection_age_days, age.count, age.count)
        }
    }
    if (reading.duringPlayback) {
        return if (ago == null) stringResource(R.string.settings_connection_duringPlaybackUndated, rate)
        else stringResource(R.string.settings_connection_duringPlayback, rate, ago)
    }
    val transport = stringResource(
        when (text.transport) {
            ConnectionSpeedText.Transport.WIFI -> R.string.settings_connection_transport_wifi
            ConnectionSpeedText.Transport.ETHERNET -> R.string.settings_connection_transport_ethernet
            ConnectionSpeedText.Transport.CELLULAR -> R.string.settings_connection_transport_cellular
            ConnectionSpeedText.Transport.OTHER -> R.string.settings_connection_transport_otherNetwork
        },
    )
    return when {
        ago == null && reading.lowerBound -> stringResource(R.string.settings_connection_lowerBoundUndated, rate, transport)
        ago == null -> stringResource(R.string.settings_connection_measuredUndated, rate, transport)
        reading.lowerBound -> stringResource(R.string.settings_connection_lowerBound, rate, ago, transport)
        else -> stringResource(R.string.settings_connection_measured, rate, ago, transport)
    }
}

/** [kbps] as Settings writes a rate: "850 kbps", "1.4 Mbps", "44 Mbps". */
@Composable
fun connectionRateLabel(kbps: Int): String {
    val (rate, unit) = connectionRate(kbps)
    return stringResource(
        if (unit == RateUnit.MBPS) R.string.settings_rate_mbps else R.string.settings_rate_kbps,
        formatRate(rate),
    )
}

/** Why speed tests are skipped on this network. */
@Composable
fun connectionSpeedSkipLabel(skip: ConnectionSpeedSkip): String = stringResource(
    when (skip) {
        ConnectionSpeedSkip.CELLULAR -> R.string.settings_connection_skippedCellular
        ConnectionSpeedSkip.METERED -> R.string.settings_connection_skippedMetered
    },
)

/** What became of the last **Test speed**. */
@Composable
fun connectionSpeedMessageLabel(message: ConnectionSpeedMessage): String = when (message) {
    is ConnectionSpeedMessage.TryAgainIn ->
        pluralStringResource(R.plurals.settings_connection_retry, message.seconds, message.seconds)
    ConnectionSpeedMessage.StopPlayback -> stringResource(R.string.settings_connection_stopPlayback)
    ConnectionSpeedMessage.Failed -> stringResource(R.string.settings_connection_measureFailed)
    ConnectionSpeedMessage.NotSaved -> stringResource(R.string.settings_connection_notSavedShort)
}

/** The label each buffering-quality mode is offered under. */
@StringRes
fun bufferingQualityModeLabel(mode: BufferingQualityMode): Int = when (mode) {
    BufferingQualityMode.NEVER -> R.string.settings_bufferingQuality_never
    BufferingQualityMode.ASK -> R.string.settings_bufferingQuality_ask
    BufferingQualityMode.ALWAYS -> R.string.settings_bufferingQuality_always
}

/** "Limited to 44 Mbps after buffering on Oct 17", in this device's locale and time zone. */
@Composable
fun learnedLimitLabel(limit: PlaybackLearnedLimitV2): String {
    val rate = connectionRateLabel(limit.kbps)
    val date = remember(limit) { learnedLimitDate(limit.observedAt) }
    return date?.let { stringResource(R.string.settings_learnedLimit, rate, it) }
        ?: stringResource(R.string.settings_learnedLimit_undated, rate)
}
