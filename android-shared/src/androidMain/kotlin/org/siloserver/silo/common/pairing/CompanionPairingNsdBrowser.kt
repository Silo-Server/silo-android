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
import java.util.ArrayDeque

class CompanionPairingNsdBrowser(context: Context) {
    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _targets = MutableStateFlow<List<CompanionPairingTarget>>(emptyList())
    val targets: StateFlow<List<CompanionPairingTarget>> = _targets.asStateFlow()

    /** One listener per service type: `_silopair._tcp` and the Roku's `_silopair-roku._tcp`. */
    private val discoveryListeners = mutableMapOf<String, NsdManager.DiscoveryListener>()

    /** Guarded by this browser's monitor. */
    private val resolutions = PairingResolveQueue()

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
                val next = synchronized(this@CompanionPairingNsdBrowser) {
                    if (discoveryListeners[type] !== this) return
                    resolutions.enqueue(PairingResolve(serviceInfo, roku))
                }
                next?.let(::resolve)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostName = serviceInfo.serviceName
                synchronized(this@CompanionPairingNsdBrowser) {
                    resolutions.lose(lostName, roku)
                    _targets.update { targets -> targets.filterNot { it.serviceName == lostName && it.isRoku == roku } }
                }
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
        // A resolve still in flight publishes nothing once browsing stops.
        resolutions.clear()
        _targets.value = emptyList()
    }

    private fun resolve(pending: PairingResolve) {
        runCatching {
            nsdManager.resolveService(
                pending.serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Companion TV resolve failed for ${info.serviceName}: $errorCode")
                        finish(pending)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        synchronized(this@CompanionPairingNsdBrowser) {
                            if (resolutions.shouldPublish(pending)) info.toPairingTarget(pending.roku)?.let(::publish)
                        }
                        finish(pending)
                    }
                },
            )
        }.onFailure { error ->
            Log.w(TAG, "Companion TV resolve could not start for ${pending.serviceName}", error)
            finish(pending)
        }
    }

    private fun finish(pending: PairingResolve) {
        synchronized(this) { resolutions.finish(pending) }?.let(::resolve)
    }

    /** Caller holds the monitor. */
    private fun publish(target: CompanionPairingTarget) {
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

/** A found pairing service waiting for, or holding, the resolve slot. */
internal class PairingResolve(
    val serviceInfo: NsdServiceInfo,
    val roku: Boolean,
    val serviceName: String = serviceInfo.serviceName,
) {
    /** Lost, or browsing stopped, while resolving: it keeps the slot until its callback but publishes nothing. */
    var cancelled = false

    fun isFor(serviceName: String, roku: Boolean): Boolean = this.serviceName == serviceName && this.roku == roku
}

/**
 * Legacy NsdManager (before Android 14) runs one resolve at a time; a second
 * resolveService while one is in flight fails with FAILURE_ALREADY_ACTIVE and
 * that TV never appears. A Roku and a TV often answer in the same burst, so
 * both service types share this queue. The slot stays taken until the resolve
 * calls back. Not thread-safe: the browser holds its monitor around every call.
 */
internal class PairingResolveQueue {
    private val pending = ArrayDeque<PairingResolve>()
    private var active: PairingResolve? = null

    /** Queues [resolve] unless its service is already queued or resolving; returns the resolve to start now, if any. */
    fun enqueue(resolve: PairingResolve): PairingResolve? {
        val queued = pending.any { it.isFor(resolve.serviceName, resolve.roku) }
        val resolving = active?.takeUnless { it.cancelled }?.isFor(resolve.serviceName, resolve.roku) == true
        if (queued || resolving) return null
        pending.addLast(resolve)
        return takeNext()
    }

    fun shouldPublish(resolve: PairingResolve): Boolean = active === resolve && !resolve.cancelled

    /** Frees [resolve]'s slot; returns the next resolve to start, if any. */
    fun finish(resolve: PairingResolve): PairingResolve? {
        if (active !== resolve) return null
        active = null
        return takeNext()
    }

    fun lose(serviceName: String, roku: Boolean) {
        active?.takeIf { it.isFor(serviceName, roku) }?.cancelled = true
        pending.removeAll { it.isFor(serviceName, roku) }
    }

    /** Forgets everything; a late callback from the old active resolve then publishes and starts nothing. */
    fun clear() {
        pending.clear()
        active = null
    }

    private fun takeNext(): PairingResolve? {
        if (active != null || pending.isEmpty()) return null
        return pending.removeFirst().also { active = it }
    }
}

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
