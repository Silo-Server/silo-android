package org.siloserver.silo.common.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

data class PlaybackNetworkSnapshot(
    val connected: Boolean = false,
    val validated: Boolean = false,
    /** The network lacks NET_CAPABILITY_NOT_METERED. */
    val metered: Boolean = false,
    /** The most specific transport, for diagnostics; a VPN reads as what it runs over. */
    val transport: String = "none",
    /** The network has TRANSPORT_CELLULAR, including a VPN running over mobile data. */
    val cellular: Boolean = false,
    /** The network has TRANSPORT_VPN. */
    val vpn: Boolean = false,
    /** Android Data Saver is active and this app is not exempt (RESTRICT_BACKGROUND_STATUS_ENABLED). */
    val dataSaver: Boolean = false,
    /**
     * Media3's throughput estimate from completed transfers, for diagnostics
     * only (route events and the diagnostics bundle): it starts as a guess
     * from the network type, and loads that stall on a full buffer understate
     * the link. Playback requests never carry it; the only estimate they send
     * is the bandwidth probe's, from
     * [org.siloserver.silo.common.player.probe.BandwidthProbeScheduler].
     */
    val bandwidthEstimateKbps: Int? = null,
    /** OS-reported link capacity, useful only as diagnostics. */
    val linkDownstreamKbps: Int? = null,
    /**
     * The default network's `Network.getNetworkHandle()`, or null with no
     * connection. Android gives every new connection a new handle, so a
     * change means another network even when the transport is the same.
     */
    val networkId: Long? = null,
)

fun interface PlaybackNetworkEvidenceProvider {
    fun snapshot(): PlaybackNetworkSnapshot

    /** The current snapshot, then each change. */
    val updates: Flow<PlaybackNetworkSnapshot>
        get() = flowOf(snapshot())

    companion object {
        val None = PlaybackNetworkEvidenceProvider { PlaybackNetworkSnapshot() }
    }
}

/**
 * Process-wide network evidence for the phone's cellular quality choice, the
 * bandwidth probe's gates and cache, and diagnostics (route events and the
 * diagnostics bundle). Media3's throughput estimate stays diagnostics-only:
 * playback requests never carry it.
 *
 * A measured estimate is cleared when Android changes the default network so
 * Wi-Fi throughput is not reported against a cellular/VPN route. We do not
 * substitute `linkDownstreamBandwidthKbps` for measured throughput: Android's
 * link figure is a nominal interface capacity and is often wildly optimistic.
 */
@UnstableApi
class PlaybackNetworkMonitor(
    context: Context,
    bandwidthMeter: DefaultBandwidthMeter,
) : PlaybackNetworkEvidenceProvider, BandwidthMeter.EventListener {
    private val appContext = context.applicationContext
    private val connectivity = appContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _state = MutableStateFlow(PlaybackNetworkSnapshot())
    val state: StateFlow<PlaybackNetworkSnapshot> = _state.asStateFlow()

    @Volatile private var defaultNetwork: Network? = connectivity.activeNetwork
    @Volatile private var measuredBitrateBps: Long? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(network)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            refresh(network, capabilities)

        override fun onLost(network: Network) {
            if (network == defaultNetwork) refresh(connectivity.activeNetwork)
        }
    }

    private val dataSaverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED) {
                refreshCurrentNetwork()
            }
        }
    }

    init {
        bandwidthMeter.addEventListener(Handler(Looper.getMainLooper()), this)
        refreshCurrentNetwork()
        connectivity.registerDefaultNetworkCallback(callback)
        ContextCompat.registerReceiver(
            appContext,
            dataSaverReceiver,
            IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun snapshot(): PlaybackNetworkSnapshot = _state.value

    override val updates: Flow<PlaybackNetworkSnapshot> get() = state

    @Synchronized
    override fun onBandwidthSample(elapsedMs: Int, bytesTransferred: Long, bitrateEstimate: Long) {
        if (elapsedMs <= 0 || bytesTransferred <= 0L || bitrateEstimate <= 0L) return
        measuredBitrateBps = bitrateEstimate
        publish(defaultNetwork, capabilities(defaultNetwork))
    }

    @Synchronized
    private fun refreshCurrentNetwork() {
        val current = defaultNetwork
        publish(current, capabilities(current))
    }

    @Synchronized
    private fun refresh(
        network: Network?,
        knownCapabilities: NetworkCapabilities? = null,
    ) {
        if (network != defaultNetwork) {
            defaultNetwork = network
            measuredBitrateBps = null
        }
        publish(network, knownCapabilities ?: capabilities(network))
    }

    private fun publish(network: Network?, caps: NetworkCapabilities?) {
        _state.value = playbackNetworkSnapshot(
            caps = caps.takeIf { network != null },
            dataSaver = isDataSaverEnabled(),
            bandwidthEstimateKbps = measuredBitrateBps?.toKbpsInt(),
            networkId = network?.networkHandle,
        )
    }

    private fun isDataSaverEnabled(): Boolean =
        connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    private fun capabilities(network: Network?): NetworkCapabilities? =
        network?.let(connectivity::getNetworkCapabilities)
}

/** The snapshot for the default network's [caps]; null caps is no connection. */
internal fun playbackNetworkSnapshot(
    caps: NetworkCapabilities?,
    dataSaver: Boolean = false,
    bandwidthEstimateKbps: Int? = null,
    networkId: Long? = null,
): PlaybackNetworkSnapshot = PlaybackNetworkSnapshot(
    connected = caps != null,
    validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
    metered = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    transport = caps.transportName(),
    cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
    vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
    dataSaver = dataSaver,
    bandwidthEstimateKbps = bandwidthEstimateKbps,
    linkDownstreamKbps = caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
    networkId = networkId.takeIf { caps != null },
)

private fun NetworkCapabilities?.transportName(): String = when {
    this == null -> "none"
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
    hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
    hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
    else -> "other"
}

private fun Long.toKbpsInt(): Int =
    (this / 1_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
