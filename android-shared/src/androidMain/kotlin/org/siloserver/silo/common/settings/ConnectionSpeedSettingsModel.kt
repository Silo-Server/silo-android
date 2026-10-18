package org.siloserver.silo.common.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.common.player.PlaybackNetworkSnapshot
import org.siloserver.silo.common.player.probe.BandwidthEstimate
import org.siloserver.silo.common.player.probe.BandwidthProbeScheduler
import org.siloserver.silo.common.player.probe.ProbeOutcome
import org.siloserver.silo.common.player.probe.skipsBandwidthProbe
import org.siloserver.silo.network.apiv2.PlaybackConnectionProbeV2
import org.siloserver.silo.repository.PlaybackConnectionRepository
import org.siloserver.silo.repository.PlaybackConnectionSettings
import org.siloserver.silo.util.parseRfc3339ToEpochMillis

/** One connection speed measurement as Settings shows it. */
data class ConnectionSpeedReading(
    val kbps: Int,
    /** Epoch milliseconds; null when the server sent no usable time. */
    val measuredAtEpochMs: Long?,
    /** `wifi`, `ethernet`, `cellular` or `other`. */
    val transport: String?,
)

/** Why the speed test is not offered on the network in use. */
enum class ConnectionSpeedSkip {
    /** Mobile data: the phone's Cellular quality settings apply. */
    CELLULAR,

    /** A metered network the phone plays at its Cellular quality on, such as a hotspot. */
    METERED_CELLULAR_QUALITY,

    /** Any other metered network, including a VPN Android marks metered. */
    METERED,
}

/** The answer to the last **Test speed**, until the next one. */
sealed interface ConnectionSpeedMessage {
    /** HTTP 429: "Try again in N seconds". */
    data class TryAgainIn(val seconds: Int) : ConnectionSpeedMessage

    /** Media was playing or starting. */
    data object StopPlayback : ConnectionSpeedMessage

    /** The probe ran and measured nothing. */
    data object Failed : ConnectionSpeedMessage
}

/** What the phone and TV Connection groups render. */
data class ConnectionSpeedUiState(
    /** The server advertises `connection_speed_v1` with a probe URL. */
    val visible: Boolean = false,
    /** The latest measurement on this connection: the server's, or this device's own when newer. */
    val reading: ConnectionSpeedReading? = null,
    /** `auto_budget_kbps`, computed by the server; null hides the line. */
    val autoBudgetKbps: Int? = null,
    val testing: Boolean = false,
    val message: ConnectionSpeedMessage? = null,
    /** Non-null while the network is metered or cellular: no speed test, and no budget line. */
    val skipped: ConnectionSpeedSkip? = null,
)

/**
 * Settings → Playback → Connection, for the phone and TV view models: the
 * server's `probe` and `auto_budget_kbps` from `GET /api/v2/playback/connection`,
 * this device's own last measurement, the network's skip reason, and
 * **Test speed** ([testSpeed]).
 *
 * The view model reads the connection and hands it over through [update].
 *
 * @param cellularQualityApplies whether this device plays at its Cellular
 *   quality on a network (the phone). The TV has none.
 */
class ConnectionSpeedSettingsModel(
    private val probe: BandwidthProbeScheduler,
    private val connection: PlaybackConnectionRepository,
    private val scope: CoroutineScope,
    private val cellularQualityApplies: (PlaybackNetworkSnapshot) -> Boolean = { false },
) {
    private data class Local(val testing: Boolean = false, val message: ConnectionSpeedMessage? = null)

    private val server = MutableStateFlow<PlaybackConnectionSettings?>(null)
    private val local = MutableStateFlow(Local())

    val state: StateFlow<ConnectionSpeedUiState> =
        combine(server, probe.lastMeasurement, probe.networkUpdates, local) { settings, own, network, local ->
            val skipped = skipFor(network)
            ConnectionSpeedUiState(
                visible = settings?.connectionSpeed == true,
                reading = newer(settings?.connection?.probe?.reading(), own?.reading()),
                autoBudgetKbps = settings?.connection?.autoBudgetKbps.takeIf { skipped == null },
                testing = local.testing,
                message = local.message,
                skipped = skipped,
            )
        }.stateIn(scope, SharingStarted.Eagerly, ConnectionSpeedUiState())

    /** The server's state, as the view model last read it. */
    fun update(settings: PlaybackConnectionSettings?) {
        server.value = settings
    }

    /**
     * Runs the probe now, reports the result, then reads the connection again
     * so the row shows what the server stored. A second press while one runs
     * is ignored.
     */
    fun testSpeed() {
        if (local.value.testing) return
        local.value = Local(testing = true)
        scope.launch {
            var message: ConnectionSpeedMessage? = null
            try {
                val outcome = probe.runNow()
                message = when (outcome) {
                    is ProbeOutcome.Measured -> null
                    is ProbeOutcome.RateLimited -> ConnectionSpeedMessage.TryAgainIn(outcome.retryAfterSeconds)
                    ProbeOutcome.PlaybackActive -> ConnectionSpeedMessage.StopPlayback
                    // The row already says speed tests are skipped here.
                    ProbeOutcome.Metered -> null
                    else -> ConnectionSpeedMessage.Failed
                }
                if (outcome is ProbeOutcome.Measured) connection.settingsState()?.let { server.value = it }
            } finally {
                local.update { Local(testing = false, message = message) }
            }
        }
    }

    private fun skipFor(network: PlaybackNetworkSnapshot): ConnectionSpeedSkip? = when {
        !network.skipsBandwidthProbe -> null
        cellularQualityApplies(network) ->
            if (network.cellular) ConnectionSpeedSkip.CELLULAR else ConnectionSpeedSkip.METERED_CELLULAR_QUALITY
        else -> ConnectionSpeedSkip.METERED
    }

    private companion object {
        fun PlaybackConnectionProbeV2.reading() =
            ConnectionSpeedReading(kbps, measuredAt?.let(::parseRfc3339ToEpochMillis), transport)

        fun BandwidthEstimate.reading() = ConnectionSpeedReading(kbps, measuredAtEpochMs, transport)

        /** The more recent of the two; a reading with a time beats one without. */
        fun newer(server: ConnectionSpeedReading?, own: ConnectionSpeedReading?): ConnectionSpeedReading? {
            if (server == null) return own
            if (own == null) return server
            val serverAt = server.measuredAtEpochMs ?: return own
            return if ((own.measuredAtEpochMs ?: Long.MIN_VALUE) > serverAt) own else server
        }
    }
}
