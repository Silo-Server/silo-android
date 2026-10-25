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
import java.util.ArrayDeque

/**
 * How the phone dials a receiver. The advertised service type decides it, and
 * nothing falls back from one to another: a `_silocast._tcp` TV is always
 * TLS-PSK, and a `_silocast-roku._tcp` Roku is plain or `sealed` as its TXT
 * `sec` says (silo-roku `docs/silocontrol.md` sections 5.1 and 9).
 */
enum class SiloCastTransport {
    /** Apple TV and Android TV: TLS-PSK with the fixed `silo-cast-v1` key. */
    TlsPsk,

    /** A Roku at level `plain`: the same frames over plain TCP. */
    Plain,

    /** A Roku that requires pairing: frames sealed with its pairing key. */
    Sealed,
}

data class SiloCastTarget(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val version: Int,
    /** The mDNS instance name (may carry conflict decorations like " (2)").
     *  onServiceLost only reports this, so removal must match on it — the
     *  display name comes from the TXT record and can collide/diverge. */
    val serviceName: String = name,
    /** The receiver's active server (TXT `server`) — same-server targets are
     *  controllable without a handoff; others need the v2 profile handoff. */
    val serverId: String? = null,
    val serverName: String? = null,
    /** The deployment identity behind [serverId] (TXT `serverIdentity`), so a
     *  phone signed in at another address of the same server still matches. */
    val serverIdentity: String? = null,
    /** The receiver's advertised `playing` TXT flag. Drives the picker's
     *  "Playing now" badge and gates silent auto-resume (idle TVs must never
     *  be reattached — a bare connection flips them into standby takeover). */
    val isPlaying: Boolean = false,
    val transport: SiloCastTransport = SiloCastTransport.TlsPsk,
    /** A sealed Roku's key ID (TXT `kid`): the first 8 hex digits of SHA-256 of its pairing key. */
    val keyId: String? = null,
) {
    /** A Roku receiver: launched only from the Roku itself, and never dialed over TLS. */
    val isRoku: Boolean get() = transport != SiloCastTransport.TlsPsk
}

class SiloCastNsdBrowser(context: Context) {
    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _targets = MutableStateFlow<List<SiloCastTarget>>(emptyList())
    val targets: StateFlow<List<SiloCastTarget>> = _targets.asStateFlow()

    /** One listener per service type: `_silocast._tcp` and the Roku's `_silocast-roku._tcp`. */
    private val discoveryListeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val pendingResolutions = ArrayDeque<PendingResolution>()
    private var activeResolution: PendingResolution? = null

    /** Targets added by the debug adb hook; see [setDebugTargets]. */
    private var debugTargets: List<SiloCastTarget> = emptyList()

    /** Discovered targets an injected one with the same id hides, restored when it goes. */
    private var shadowedTargets: Map<String, SiloCastTarget> = emptyMap()

    /**
     * Debug builds only (PlaybackDebugReceiver): targets supplied over adb.
     * An emulator sits behind NAT that mDNS can't cross, so a test harness
     * resolves TVs on the host and hands them in; they merge with (and
     * survive a restart of) real discovery. An empty list removes them.
     * Every write to [_targets] holds this monitor, so discovery can't
     * overwrite an injected target.
     */
    @Synchronized
    fun setDebugTargets(targets: List<SiloCastTarget>) {
        val previousIds = debugTargets.map { it.deviceId }.toSet()
        val injectedIds = targets.map { it.deviceId }.toSet()
        val discovered = _targets.value.filterNot { it.deviceId in previousIds } + shadowedTargets.values
        shadowedTargets = discovered.filter { it.deviceId in injectedIds }.associateBy { it.deviceId }
        debugTargets = targets
        _targets.value = (discovered.filterNot { it.deviceId in injectedIds } + targets).sortedBy { it.name.lowercase() }
    }

    /** Publishes a resolved mDNS record; a debug-injected target wins over one with its id. */
    private fun publishDiscoveredLocked(target: SiloCastTarget) {
        if (debugTargets.any { it.deviceId == target.deviceId }) {
            shadowedTargets = shadowedTargets + (target.deviceId to target)
            return
        }
        _targets.update { current ->
            (current.filterNot { it.deviceId == target.deviceId } + target)
                .sortedBy { it.name.lowercase() }
        }
    }

    @Synchronized
    fun start() {
        SERVICE_TYPES.forEach(::startDiscovery)
    }

    /** Caller holds the monitor. */
    private fun startDiscovery(type: String) {
        if (type in discoveryListeners) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "SiloCast discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Android frequently reports the discovered type with a
                // trailing dot ("_silocast._tcp."); exact equality would
                // silently reject every receiver.
                if (serviceInfo.serviceType.trimEnd('.') != type) return
                enqueueResolution(serviceInfo, type)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostName = serviceInfo.serviceName
                val lostRoku = isRokuType(type)
                fun matches(target: SiloCastTarget) = target.serviceName == lostName && target.isRoku == lostRoku
                synchronized(this@SiloCastNsdBrowser) {
                    activeResolution
                        ?.takeIf { it.matches(lostName, type) }
                        ?.cancelled = true
                    pendingResolutions.removeAll { it.matches(lostName, type) }
                    shadowedTargets = shadowedTargets.filterValues { !matches(it) }
                    val injectedIds = debugTargets.map { it.deviceId }.toSet()
                    _targets.update { targets ->
                        targets.filterNot { matches(it) && it.deviceId !in injectedIds }
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "SiloCast discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "SiloCast discovery failed to start for $serviceType: $errorCode")
                // Only this type: a Roku browse that fails must not stop the other TVs'.
                synchronized(this@SiloCastNsdBrowser) {
                    if (discoveryListeners[type] === this) discoveryListeners.remove(type)
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "SiloCast discovery failed to stop for $serviceType: $errorCode")
            }
        }
        discoveryListeners[type] = listener
        runCatching { nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { error ->
                Log.w(TAG, "SiloCast discovery could not start for $type", error)
                discoveryListeners.remove(type)
            }
    }

    @Synchronized
    fun stop() {
        discoveryListeners.values.forEach { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListeners.clear()
        pendingResolutions.clear()
        activeResolution = null
        shadowedTargets = emptyMap()
        _targets.value = debugTargets
    }

    /**
     * Legacy NsdManager permits only one resolve at a time. Bonjour commonly
     * reports Apple TV and Android TV receivers together; resolving both in
     * parallel returns FAILURE_ALREADY_ACTIVE and silently drops one target.
     */
    private fun enqueueResolution(serviceInfo: NsdServiceInfo, serviceType: String) {
        val next = synchronized(this) {
            if (serviceType !in discoveryListeners) return
            val serviceName = serviceInfo.serviceName
            if (activeResolution?.takeUnless { it.cancelled }?.matches(serviceName, serviceType) == true ||
                pendingResolutions.any { it.matches(serviceName, serviceType) }
            ) {
                return
            }
            pendingResolutions.addLast(PendingResolution(serviceInfo, serviceType))
            takeNextResolutionLocked()
        }
        next?.let(::startResolution)
    }

    private fun startResolution(pending: PendingResolution) {
        runCatching {
            nsdManager.resolveService(
                pending.serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "SiloCast resolve failed for ${info.serviceName}: $errorCode")
                    finishResolution(pending)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    synchronized(this@SiloCastNsdBrowser) {
                        if (pending.serviceType in discoveryListeners && activeResolution === pending && !pending.cancelled) {
                            // The type browsed, not the resolved info's: some
                            // Android versions report it with a leading dot.
                            info.toSiloCastTarget(pending.serviceType)?.let(::publishDiscoveredLocked)
                        }
                    }
                    finishResolution(pending)
                }
            },
            )
        }.onFailure { error ->
            Log.w(TAG, "SiloCast resolve could not start for ${pending.serviceInfo.serviceName}", error)
            finishResolution(pending)
        }
    }

    private fun finishResolution(completed: PendingResolution) {
        val next = synchronized(this) {
            if (activeResolution !== completed) return
            activeResolution = null
            takeNextResolutionLocked()
        }
        next?.let(::startResolution)
    }

    private fun takeNextResolutionLocked(): PendingResolution? {
        if (activeResolution != null || pendingResolutions.isEmpty()) return null
        return pendingResolutions.removeFirst().also { activeResolution = it }
    }

    private fun NsdServiceInfo.toSiloCastTarget(serviceType: String): SiloCastTarget? {
        val host = this.host?.hostAddress ?: return null
        val record = attributes.mapValues { (_, value) -> value?.toString(Charset.forName("UTF-8")).orEmpty() }
        return targetFromRecord(host = host, port = port, serviceName = serviceName, txt = record, serviceType = serviceType)
    }

    companion object {
        private val SERVICE_TYPES = listOf(SiloCastProtocol.serviceType, SiloCastProtocol.rokuServiceType)

        fun isRokuType(serviceType: String): Boolean =
            serviceType.trim('.') == SiloCastProtocol.rokuServiceType

        /**
         * A resolved `_silocast._tcp` or `_silocast-roku._tcp` record, read
         * the way iOS reads it. [serviceType] alone decides the transport, so
         * a TLS TV's TXT can never talk the phone into plain TCP. A Roku
         * advertising a `sec` level this build doesn't know is left out.
         */
        fun targetFromRecord(
            host: String,
            port: Int,
            serviceName: String,
            txt: Map<String, String>,
            serviceType: String = SiloCastProtocol.serviceType,
        ): SiloCastTarget? {
            if (port <= 0) return null
            fun string(key: String): String? = txt[key]?.takeIf { it.isNotBlank() }
            val transport = if (isRokuType(serviceType)) {
                when (string("sec")?.lowercase()) {
                    null, "plain" -> SiloCastTransport.Plain
                    "sealed" -> SiloCastTransport.Sealed
                    else -> return null
                }
            } else {
                SiloCastTransport.TlsPsk
            }
            val deviceId = string("deviceId") ?: string("id") ?: "$host:$port"
            return SiloCastTarget(
                serviceName = serviceName,
                deviceId = deviceId,
                name = string("name") ?: serviceName,
                host = host,
                port = port,
                // Every v2 receiver advertises `v`; one that doesn't predates it (iOS reads it the same way).
                version = string("v")?.toIntOrNull() ?: 1,
                serverId = string("server"),
                serverName = string("serverName"),
                serverIdentity = string("serverIdentity"),
                isPlaying = string("playing") == "1",
                transport = transport,
                keyId = string("kid")?.lowercase()?.takeIf { transport == SiloCastTransport.Sealed },
            )
        }

        private const val TAG = "SiloCastNsdBrowser"
    }

    private class PendingResolution(
        val serviceInfo: NsdServiceInfo,
        val serviceType: String,
        var cancelled: Boolean = false,
    ) {
        fun matches(serviceName: String, type: String) = serviceInfo.serviceName == serviceName && serviceType == type
    }
}
