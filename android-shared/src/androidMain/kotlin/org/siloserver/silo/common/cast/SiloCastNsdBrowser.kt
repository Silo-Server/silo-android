package org.siloserver.silo.common.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.cast.SiloCastProtocol
import java.nio.charset.Charset
import java.util.ArrayDeque

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
    /** The receiver's advertised `playing` TXT flag. Drives the picker's
     *  "Playing now" badge and gates silent auto-resume (idle TVs must never
     *  be reattached — a bare connection flips them into standby takeover). */
    val isPlaying: Boolean = false,
)

class SiloCastNsdBrowser(context: Context) {
    private val nsdManager: NsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _targets = MutableStateFlow<List<SiloCastTarget>>(emptyList())
    val targets: StateFlow<List<SiloCastTarget>> = _targets.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val pendingResolutions = ArrayDeque<PendingResolution>()
    private var activeResolution: PendingResolution? = null

    /** Targets resolved from mDNS; [targets] merges them with [debugTargets]. */
    private var discoveredTargets: List<SiloCastTarget> = emptyList()

    /** Targets added by the debug adb hook; see [setDebugTargets]. */
    private var debugTargets: List<SiloCastTarget> = emptyList()

    /** Publishes discovery plus debug targets; a debug target replaces a discovered one with its id. Hold the lock. */
    private fun publishTargets() {
        val injectedIds = debugTargets.map { it.deviceId }.toSet()
        _targets.value = (discoveredTargets.filterNot { it.deviceId in injectedIds } + debugTargets)
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Debug builds only (PlaybackDebugReceiver): targets supplied over adb.
     * An emulator sits behind NAT that mDNS can't cross, so a test harness
     * resolves TVs on the host and hands them in; they merge with (and
     * survive a restart of) real discovery. An empty list removes them, and
     * any discovered TV they replaced shows again.
     */
    @Synchronized
    fun setDebugTargets(targets: List<SiloCastTarget>) {
        debugTargets = targets
        publishTargets()
    }

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
                enqueueResolution(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostName = serviceInfo.serviceName
                synchronized(this@SiloCastNsdBrowser) {
                    activeResolution
                        ?.takeIf { it.serviceInfo.serviceName == lostName }
                        ?.cancelled = true
                    pendingResolutions.removeAll { it.serviceInfo.serviceName == lostName }
                    discoveredTargets = discoveredTargets.filterNot { it.serviceName == lostName }
                    publishTargets()
                }
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
        pendingResolutions.clear()
        activeResolution = null
        discoveredTargets = emptyList()
        publishTargets()
    }

    /**
     * Legacy NsdManager permits only one resolve at a time. Bonjour commonly
     * reports Apple TV and Android TV receivers together; resolving both in
     * parallel returns FAILURE_ALREADY_ACTIVE and silently drops one target.
     */
    private fun enqueueResolution(serviceInfo: NsdServiceInfo) {
        val next = synchronized(this) {
            if (discoveryListener == null) return
            val serviceName = serviceInfo.serviceName
            if (activeResolution?.takeUnless { it.cancelled }?.serviceInfo?.serviceName == serviceName ||
                pendingResolutions.any { it.serviceInfo.serviceName == serviceName }
            ) {
                return
            }
            pendingResolutions.addLast(PendingResolution(serviceInfo))
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
                    val mayPublish = synchronized(this@SiloCastNsdBrowser) {
                        discoveryListener != null && activeResolution === pending && !pending.cancelled
                    }
                    if (mayPublish) {
                        info.toSiloCastTarget()?.let { target ->
                            synchronized(this@SiloCastNsdBrowser) {
                                discoveredTargets = discoveredTargets.filterNot { it.deviceId == target.deviceId } + target
                                publishTargets()
                            }
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

    private fun NsdServiceInfo.toSiloCastTarget(): SiloCastTarget? {
        val host = this.host?.hostAddress ?: return null
        val record = attributes.mapValues { (_, value) -> value?.toString(Charset.forName("UTF-8")).orEmpty() }
        return targetFromRecord(host = host, port = port, serviceName = serviceName, txt = record)
    }

    companion object {
        /** A resolved `_silocast._tcp` record, read the way iOS reads it. */
        fun targetFromRecord(host: String, port: Int, serviceName: String, txt: Map<String, String>): SiloCastTarget? {
            if (port <= 0) return null
            fun string(key: String): String? = txt[key]?.takeIf { it.isNotBlank() }
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
                isPlaying = string("playing") == "1",
            )
        }

        private const val TAG = "SiloCastNsdBrowser"
    }

    private class PendingResolution(
        val serviceInfo: NsdServiceInfo,
        var cancelled: Boolean = false,
    )
}
