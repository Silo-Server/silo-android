package org.siloserver.silo.common.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.siloserver.silo.cast.SiloCastProtocol
import java.nio.charset.Charset

data class SiloCastTarget(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val version: Int,
    val serverId: String? = null,
    val serverName: String? = null,
    val playing: Boolean = false,
    /** The mDNS instance name (may carry conflict decorations like " (2)").
     *  onServiceLost only reports this, so removal must match on it — the
     *  display name comes from the TXT record and can collide/diverge. */
    val serviceName: String = name,
)

class SiloCastNsdBrowser(context: Context) {
    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _targets = MutableStateFlow<List<SiloCastTarget>>(emptyList())
    val targets: StateFlow<List<SiloCastTarget>> = _targets.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Synchronized
    fun start() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "SiloCast discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Android frequently reports the discovered type with a
                // trailing dot ("_silocast._tcp."); exact equality would
                // silently reject every receiver.
                if (serviceInfo.serviceType.trimEnd('.') != SiloCastProtocol.serviceType.trimEnd('.')) return
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostName = serviceInfo.serviceName
                _targets.update { targets -> targets.filterNot { it.serviceName == lostName } }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "SiloCast discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "SiloCast discovery failed to start: $errorCode")
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "SiloCast discovery failed to stop: $errorCode")
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(SiloCastProtocol.serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun stop() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        _targets.value = emptyList()
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "SiloCast resolve failed for ${info.serviceName}: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val target = info.toSiloCastTarget() ?: return
                    _targets.update { current ->
                        (current.filterNot { it.deviceId == target.deviceId } + target)
                            .sortedBy { it.name.lowercase() }
                    }
                }
            },
        )
    }

    private fun NsdServiceInfo.toSiloCastTarget(): SiloCastTarget? {
        val host = this.host?.hostAddress ?: return null
        val port = port.takeIf { it > 0 } ?: return null
        val name = attributes.string("name") ?: serviceName
        val mdnsName = serviceName
        val deviceId = attributes.string("deviceId") ?: attributes.string("id") ?: "$host:$port"
        val version = attributes.string("v")?.toIntOrNull() ?: 1
        val serverId = attributes.string("server")
        val serverName = attributes.string("serverName")
        val playing = attributes.string("playing") == "1"
        return SiloCastTarget(
            serviceName = mdnsName,
            deviceId = deviceId,
            name = name,
            host = host,
            port = port,
            version = version,
            serverId = serverId,
            serverName = serverName,
            playing = playing,
        )
    }

    private fun Map<String, ByteArray>.string(key: String): String? =
        this[key]?.toString(Charset.forName("UTF-8"))?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "SiloCastNsdBrowser"
    }
}
