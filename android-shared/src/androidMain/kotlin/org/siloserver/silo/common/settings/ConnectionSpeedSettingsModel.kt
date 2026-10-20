package org.siloserver.silo.common.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.common.player.PlaybackNetworkSnapshot
import org.siloserver.silo.common.player.probe.BandwidthEstimate
import org.siloserver.silo.common.player.probe.BandwidthProbeScheduler
import org.siloserver.silo.common.player.probe.ProbeOutcome
import org.siloserver.silo.common.player.probe.skipsBandwidthProbe
import org.siloserver.silo.network.apiv2.PlaybackConnectionProbeV2
import org.siloserver.silo.network.apiv2.PlaybackConnectionSampleV2
import org.siloserver.silo.repository.PlaybackConnectionRepository
import org.siloserver.silo.repository.PlaybackConnectionSettings
import org.siloserver.silo.util.parseRfc3339ToEpochMillis

/** One connection speed reading as Settings shows it. */
data class ConnectionSpeedReading(
    val kbps: Int,
    /** Epoch milliseconds; null when the server sent no usable time. */
    val measuredAtEpochMs: Long?,
    /** `wifi`, `ethernet`, `cellular` or `other`; null for a playback sample. */
    val transport: String?,
    /** The link is at least [kbps]: "Faster than 115 Mbps". */
    val lowerBound: Boolean = false,
    /** A rate this device's playback received data at, not a speed test. Always a lower bound. */
    val duringPlayback: Boolean = false,
)

/**
 * Why the speed test is not offered on the network in use: it is metered or
 * cellular by the rule that also picks the phone's Cellular quality.
 */
enum class ConnectionSpeedSkip {
    /** Mobile data: the phone's Cellular quality settings apply. */
    CELLULAR,

    /** A metered network the phone plays at its Cellular quality on, such as a hotspot. */
    METERED_CELLULAR_QUALITY,

    /** A metered or cellular network on a device without Cellular quality settings (the TV). */
    METERED,
}

/** The answer to the last **Test speed**, until the next one. */
sealed interface ConnectionSpeedMessage {
    /** HTTP 429: "Try again in N seconds", for those [seconds]. */
    data class TryAgainIn(val seconds: Int) : ConnectionSpeedMessage

    /** Media was playing or starting. */
    data object StopPlayback : ConnectionSpeedMessage

    /** The probe ran and measured nothing. */
    data object Failed : ConnectionSpeedMessage

    /** The probe measured, but the server did not store the result: the row keeps the server's reading. */
    data object NotSaved : ConnectionSpeedMessage
}

/** What the phone and TV Connection groups render. */
data class ConnectionSpeedUiState(
    /** The server advertises `connection_speed_v1` with a probe URL. */
    val visible: Boolean = false,
    /**
     * The latest speed test on this connection, the server's or this
     * device's own when newer; without one, the server's playback sample.
     */
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
 * server's `probe`, `playback_sample` and `auto_budget_kbps` from
 * `GET /api/v2/playback/connection`,
 * this device's own last measurement, the network's skip reason, and
 * **Test speed** ([testSpeed]).
 *
 * It reads the connection, for the learned-limit row ([settings]) as well:
 * when Playback settings opens and after a network change while it is open
 * ([follow]), after **Reset** ([read]) and after **Test speed**.
 *
 * @param wallClock epoch milliseconds: a server time ahead of it counts as now.
 * @param hasCellularQuality whether this device has Cellular quality settings
 *   (the phone). The TV has none.
 */
class ConnectionSpeedSettingsModel(
    private val probe: BandwidthProbeScheduler,
    private val connection: PlaybackConnectionRepository,
    private val scope: CoroutineScope,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val hasCellularQuality: Boolean = false,
) {
    private data class Local(val testing: Boolean = false, val message: ConnectionSpeedMessage? = null)

    private val server = MutableStateFlow<PlaybackConnectionSettings?>(null)
    private val local = MutableStateFlow(Local())

    /** The server's state as last read: null before the first read, or when one failed. */
    val settings: StateFlow<PlaybackConnectionSettings?> = server.asStateFlow()

    val state: StateFlow<ConnectionSpeedUiState> =
        combine(server, probe.lastMeasurement, probe.networkUpdates, local) { settings, own, network, local ->
            val skipped = skipFor(network)
            val now = wallClock()
            ConnectionSpeedUiState(
                visible = settings?.connectionSpeed == true,
                reading = newer(settings?.connection?.probe?.reading(now), own?.reading())
                    ?: settings?.connection?.playbackSample?.reading(now),
                autoBudgetKbps = settings?.connection?.autoBudgetKbps.takeIf { skipped == null },
                testing = local.testing,
                message = local.message,
                skipped = skipped,
            )
        }.stateIn(scope, SharingStarted.Eagerly, ConnectionSpeedUiState())

    /**
     * While Playback settings is open: reads the connection now, and again
     * after every network change, until cancelled. Each read replaces the
     * last **Test speed** answer, which spoke of the connection before it.
     */
    suspend fun follow() {
        merge(flowOf(Unit), probe.networkChanges).collectLatest {
            local.update { it.copy(message = null) }
            read()
        }
    }

    /** Reads the connection; a failed read hides the rows it fed. */
    suspend fun read() {
        server.value = connection.settingsState()
    }

    /**
     * Runs the accurate test now (up to 10 seconds), reports the result, then
     * reads the connection again so the row shows what the server stored. A
     * second press while one runs is ignored.
     */
    fun testSpeed() {
        if (local.value.testing) return
        local.value = Local(testing = true)
        scope.launch {
            var message: ConnectionSpeedMessage? = null
            try {
                val outcome = probe.runNow()
                message = when (outcome) {
                    is ProbeOutcome.Measured -> if (outcome.saved) null else ConnectionSpeedMessage.NotSaved
                    is ProbeOutcome.RateLimited -> ConnectionSpeedMessage.TryAgainIn(outcome.retryAfterSeconds)
                    ProbeOutcome.PlaybackActive -> ConnectionSpeedMessage.StopPlayback
                    // The row already says speed tests are skipped here.
                    ProbeOutcome.Metered -> null
                    else -> ConnectionSpeedMessage.Failed
                }
                // The network has not changed, so a read that fails keeps the rows.
                connection.settingsState()?.let { server.value = it }
            } finally {
                local.update { Local(testing = false, message = message) }
                (message as? ConnectionSpeedMessage.TryAgainIn)?.let(::expire)
            }
        }
    }

    /** Hides [message] once its wait has passed, unless another answer replaced it. */
    private fun expire(message: ConnectionSpeedMessage.TryAgainIn) {
        scope.launch {
            delay(message.seconds * 1_000L)
            local.update { if (it.message === message) it.copy(message = null) else it }
        }
    }

    private fun skipFor(network: PlaybackNetworkSnapshot): ConnectionSpeedSkip? = when {
        !network.skipsBandwidthProbe -> null
        !hasCellularQuality -> ConnectionSpeedSkip.METERED
        network.cellular -> ConnectionSpeedSkip.CELLULAR
        else -> ConnectionSpeedSkip.METERED_CELLULAR_QUALITY
    }

    private companion object {
        /** A server time, no later than [nowMs]: the server's clock can run ahead of this device's. */
        fun serverTime(measuredAt: String?, nowMs: Long): Long? =
            measuredAt?.let(::parseRfc3339ToEpochMillis)?.coerceAtMost(nowMs)

        fun PlaybackConnectionProbeV2.reading(nowMs: Long) =
            ConnectionSpeedReading(kbps, serverTime(measuredAt, nowMs), transport, lowerBound)

        fun PlaybackConnectionSampleV2.reading(nowMs: Long) = ConnectionSpeedReading(
            kbps,
            serverTime(measuredAt, nowMs),
            transport = null,
            lowerBound = true,
            duringPlayback = true,
        )

        fun BandwidthEstimate.reading() = ConnectionSpeedReading(kbps, measuredAtEpochMs, transport, lowerBound)

        /** The more recent of the two; a reading with a time beats one without. */
        fun newer(server: ConnectionSpeedReading?, own: ConnectionSpeedReading?): ConnectionSpeedReading? {
            if (server == null) return own
            if (own == null) return server
            val serverAt = server.measuredAtEpochMs ?: return own
            return if ((own.measuredAtEpochMs ?: Long.MIN_VALUE) > serverAt) own else server
        }
    }
}
