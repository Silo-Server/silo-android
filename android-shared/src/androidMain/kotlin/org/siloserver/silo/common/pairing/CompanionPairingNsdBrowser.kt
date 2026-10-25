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

    /** One listener per service type: `_silopair._tcp` and the Roku's `_silopair-roku._tcp`. */
    private val discoveryListeners = mutableMapOf<String, NsdManager.DiscoveryListener>()

    @Synchronized
    fun start() {
        PAIRING_SERVICE_TYPES.forEach(::startDiscovery)
    }

    /** Caller holds the monitor. */
    private fun startDiscovery(type: String) {
        if (type in discoveryListeners) return
        val roku = type == PairingProtocol.ROKU_SERVICE_TYPE
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Companion TV discovery started for $type")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Discovered DNS-SD service types carry a trailing dot on Android
                // ("_silopair._tcp."). The browse request itself uses the undotted
                // form, so normalize both before comparing.
                if (pairingServiceTypeOf(serviceInfo.serviceType) != type) return
                resolve(serviceInfo, roku)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostName = serviceInfo.serviceName
                _targets.update { targets -> targets.filterNot { it.serviceName == lostName && it.isRoku == roku } }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Companion TV discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Companion TV discovery failed to start for $type: $errorCode")
                // Only this type: a failed Roku browse must not stop the other TVs'.
                synchronized(this@CompanionPairingNsdBrowser) {
                    if (discoveryListeners[type] === this) discoveryListeners.remove(type)
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Companion TV discovery failed to stop: $errorCode")
            }
        }
        discoveryListeners[type] = listener
        runCatching { nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { error ->
                Log.w(TAG, "Companion TV discovery could not start for $type", error)
                discoveryListeners.remove(type)
            }
    }

    @Synchronized
    fun stop() {
        discoveryListeners.values.forEach { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListeners.clear()
        _targets.value = emptyList()
    }

    private fun resolve(serviceInfo: NsdServiceInfo, roku: Boolean) {
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Companion TV resolve failed for ${info.serviceName}: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val target = info.toPairingTarget(roku) ?: return
                    Log.i(
                        TAG,
                        "Resolved ${target.name} at ${target.host}:${target.port} " +
                            "(session=${target.sessionId ?: "unknown"})",
                    )
                    _targets.update { current ->
                        (current.filterNot { it.deviceId == target.deviceId && it.isRoku == target.isRoku } + target)
                            .sortedBy { it.name.lowercase() }
                    }
                }
            },
        )
    }

    private fun NsdServiceInfo.toPairingTarget(roku: Boolean): CompanionPairingTarget? {
        val host = this.host?.hostAddress ?: return null
        val txt = attributes.mapValues { (_, value) -> value?.toString(Charset.forName("UTF-8")).orEmpty() }
        return pairingTargetFromRecord(host, port, serviceName, txt, isRoku = roku)
    }

    private companion object {
        const val TAG = "CompanionPairing"
    }
}

private val PAIRING_SERVICE_TYPES = listOf(PairingProtocol.SERVICE_TYPE, PairingProtocol.ROKU_SERVICE_TYPE)

/** The pairing service type [serviceType] names, ignoring Android's trailing dot; null for any other type. */
internal fun pairingServiceTypeOf(serviceType: String): String? =
    serviceType.trimEnd('.').takeIf { it in PAIRING_SERVICE_TYPES }

internal fun isPairingServiceType(serviceType: String): Boolean = pairingServiceTypeOf(serviceType) != null

/**
 * A resolved `_silopair._tcp` or `_silopair-roku._tcp` record. Both carry the
 * same TXT keys (`v`, `name`, `id`, `sid`, `st`, `srv`); [isRoku] comes from
 * the browsed type alone, never from TXT.
 */
internal fun pairingTargetFromRecord(
    host: String,
    port: Int,
    serviceName: String,
    txt: Map<String, String>,
    isRoku: Boolean,
): CompanionPairingTarget? {
    if (port <= 0) return null
    fun string(key: String): String? = txt[key]?.takeIf { it.isNotBlank() }
    val name = string("name") ?: serviceName
    return CompanionPairingTarget(
        serviceName = serviceName,
        deviceId = string("id") ?: "$host:$port",
        name = name,
        host = host,
        port = port,
        state = string("st"),
        version = string("v")?.toIntOrNull() ?: PairingProtocol.VERSION,
        sessionId = string("sid"),
        serverIdentity = string("srv"),
        isRoku = isRoku,
    )
}
