package org.siloserver.silo.common.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.siloserver.silo.pairing.PairingProtocol
import java.nio.charset.Charset

class CompanionPairingNsdBrowser(context: Context) {
    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _targets = MutableStateFlow<List<CompanionPairingTarget>>(emptyList())
    val targets: StateFlow<List<CompanionPairingTarget>> = _targets.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Synchronized
    fun start() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Companion TV discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Discovered DNS-SD service types carry a trailing dot on Android
                // ("_silopair._tcp."). The browse request itself uses the undotted
                // form, so normalize both before comparing.
                if (!isPairingServiceType(serviceInfo.serviceType)) return
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostName = serviceInfo.serviceName
                _targets.update { targets -> targets.filterNot { it.serviceName == lostName } }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Companion TV discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Companion TV discovery failed to start: $errorCode")
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Companion TV discovery failed to stop: $errorCode")
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(PairingProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
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
                    Log.w(TAG, "Companion TV resolve failed for ${info.serviceName}: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val target = info.toPairingTarget() ?: return
                    Log.i(
                        TAG,
                        "Resolved ${target.name} at ${target.host}:${target.port} " +
                            "(session=${target.sessionId ?: "unknown"})",
                    )
                    _targets.update { current ->
                        (current.filterNot { it.deviceId == target.deviceId } + target)
                            .sortedBy { it.name.lowercase() }
                    }
                }
            },
        )
    }

    private fun NsdServiceInfo.toPairingTarget(): CompanionPairingTarget? {
        val host = this.host?.hostAddress ?: return null
        val port = port.takeIf { it > 0 } ?: return null
        val name = attributes.string("name") ?: serviceName
        val deviceId = attributes.string("id") ?: "$host:$port"
        val version = attributes.string("v")?.toIntOrNull() ?: PairingProtocol.VERSION
        return CompanionPairingTarget(
            serviceName = serviceName,
            deviceId = deviceId,
            name = name,
            host = host,
            port = port,
            state = attributes.string("st"),
            version = version,
            sessionId = attributes.string("sid"),
        )
    }

    private fun Map<String, ByteArray>.string(key: String): String? =
        this[key]?.toString(Charset.forName("UTF-8"))?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "CompanionPairing"
    }
}

internal fun isPairingServiceType(serviceType: String): Boolean =
    serviceType.trimEnd('.') == PairingProtocol.SERVICE_TYPE.trimEnd('.')
