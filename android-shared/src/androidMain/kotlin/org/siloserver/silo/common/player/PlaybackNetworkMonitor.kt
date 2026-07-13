package org.siloserver.silo.common.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackNetworkSnapshot(
    val connected: Boolean = false,
    val validated: Boolean = false,
    val metered: Boolean = false,
    val transport: String = "none",
    /** Media3 throughput estimate from completed media transfers, not link speed. */
    val bandwidthEstimateKbps: Int? = null,
    /** OS-reported link capacity, useful only as diagnostics. */
    val linkDownstreamKbps: Int? = null,
)

fun interface PlaybackNetworkEvidenceProvider {
    fun snapshot(): PlaybackNetworkSnapshot

    companion object {
        val None = PlaybackNetworkEvidenceProvider { PlaybackNetworkSnapshot() }
    }
}

/**
 * Process-wide network evidence shared by route planning and Media3.
 *
 * A measured estimate is cleared when Android changes the default network so
 * Wi-Fi throughput cannot leak into a cellular/VPN route decision. We do not
 * substitute `linkDownstreamBandwidthKbps` for measured throughput: Android's
 * link figure is a nominal interface capacity and is often wildly optimistic.
 */
@UnstableApi
class PlaybackNetworkMonitor(
    context: Context,
    bandwidthMeter: DefaultBandwidthMeter,
) : PlaybackNetworkEvidenceProvider, BandwidthMeter.EventListener {
    private val connectivity = context.applicationContext
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

    init {
        bandwidthMeter.addEventListener(Handler(Looper.getMainLooper()), this)
        refresh(defaultNetwork)
        connectivity.registerDefaultNetworkCallback(callback)
    }

    override fun snapshot(): PlaybackNetworkSnapshot = _state.value

    @Synchronized
    override fun onBandwidthSample(elapsedMs: Int, bytesTransferred: Long, bitrateEstimate: Long) {
        if (elapsedMs <= 0 || bytesTransferred <= 0L || bitrateEstimate <= 0L) return
        measuredBitrateBps = bitrateEstimate
        publish(defaultNetwork, capabilities(defaultNetwork))
    }

    @Synchronized
    private fun refresh(
        network: Network?,
        knownCapabilities: NetworkCapabilities? = capabilities(network),
    ) {
        if (network != defaultNetwork) {
            defaultNetwork = network
            measuredBitrateBps = null
        }
        publish(network, knownCapabilities)
    }

    private fun publish(network: Network?, caps: NetworkCapabilities?) {
        _state.value = PlaybackNetworkSnapshot(
            connected = network != null && caps != null,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = caps != null &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            transport = caps.transportName(),
            bandwidthEstimateKbps = measuredBitrateBps?.toKbpsInt(),
            linkDownstreamKbps = caps?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
        )
    }

    private fun capabilities(network: Network?): NetworkCapabilities? =
        network?.let(connectivity::getNetworkCapabilities)
}

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
