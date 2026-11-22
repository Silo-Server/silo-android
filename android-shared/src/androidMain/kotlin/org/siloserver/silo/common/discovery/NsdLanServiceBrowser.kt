package org.siloserver.silo.common.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Browses `_silo._tcp` with [NsdManager]. On API 34+ each found service is
 * followed with a service-info callback, which reports every address and
 * follows changes. Older releases resolve once per found service, one at a
 * time (they reject a second concurrent resolve), and again on [refresh].
 */
class NsdLanServiceBrowser(context: Context) : LanServiceBrowser {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()

    /** Advances on start and stop; callbacks from an earlier browse are dropped. */
    private var generation = 0
    private var events: LanServiceBrowser.Events? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** What the current browse sees, by [keyOf]. */
    private val found = mutableMapOf<String, NsdServiceInfo>()

    /**
     * API 34+: one callback per followed service, oldest first. A callback
     * stays until the browse loses its service, or until [refresh] hands its
     * slot to a service that's waiting.
     */
    private val callbacks = linkedMapOf<String, NsdManager.ServiceInfoCallback>()

    /** Before API 34: services waiting to resolve, and the one resolving now. */
    private val resolveQueue = ArrayDeque<String>()

    /**
     * Held until the platform's terminal callback, even across a stop, so a
     * new browse doesn't start a second resolve while the old one runs.
     */
    private var resolving = false

    /** Before API 34: retries of a resolve another resolve in this app got in the way of. */
    private val busyRetries = mutableMapOf<String, Int>()
    private val handler = Handler(Looper.getMainLooper())

    override fun start(events: LanServiceBrowser.Events) {
        synchronized(lock) {
            if (discoveryListener != null) return
            val gen = ++generation
            this.events = events
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.i(TAG, "Server discovery started")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!isSiloServiceType(serviceInfo.serviceType)) return
                    synchronized(lock) {
                        if (gen != generation) return
                        val key = keyOf(serviceInfo)
                        if (found.put(key, serviceInfo) == null) follow(key, serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val key = keyOf(serviceInfo)
                    val events = synchronized(lock) {
                        if (gen != generation || key !in found) return
                        found.remove(key)
                        resolveQueue.remove(key)
                        callbacks.remove(key)?.let(::unregister)
                        this@NsdLanServiceBrowser.events
                    }
                    events?.gone(key)
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.i(TAG, "Server discovery stopped")
                    // stop() advances the generation first, so this is a stop
                    // nobody asked for (a network or daemon reset): report it so
                    // the browse is started again.
                    val events = synchronized(lock) {
                        if (gen != generation) return
                        discoveryListener = null
                        this@NsdLanServiceBrowser.events
                    }
                    events?.failed()
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "Server discovery failed to start: $errorCode")
                    val events = synchronized(lock) {
                        if (gen != generation) return
                        discoveryListener = null
                        this@NsdLanServiceBrowser.events
                    }
                    events?.failed()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "Server discovery failed to stop: $errorCode")
                }
            }
            discoveryListener = listener
            val started = runCatching {
                nsdManager.discoverServices(LanServerDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }
            if (started.isFailure) {
                Log.w(TAG, "Server discovery could not start", started.exceptionOrNull())
                discoveryListener = null
                events.failed()
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            generation++
            events = null
            discoveryListener?.let { listener -> runCatching { nsdManager.stopServiceDiscovery(listener) } }
            discoveryListener = null
            callbacks.values.forEach(::unregister)
            callbacks.clear()
            found.clear()
            resolveQueue.clear()
            busyRetries.clear()
            handler.removeCallbacksAndMessages(null)
        }
    }

    override fun refresh() {
        synchronized(lock) {
            if (discoveryListener == null) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                rotateFollowed()
            } else {
                for (key in found.keys) if (key !in resolveQueue) resolveQueue.addLast(key)
                resolveNext()
            }
        }
    }

    /**
     * API 34+ callbacks follow changes themselves, so only services without
     * one need work: a failed registration, or a service waiting for a slot.
     * When more services are found than [MAX_FOLLOWED], the longest-followed
     * give up their slots, so every service is resolved in turn. A service
     * that gives up its slot keeps its last result. Called under [lock].
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun rotateFollowed() {
        val waiting = found.filterKeys { it !in callbacks }
        if (waiting.isEmpty()) return
        val needed = waiting.size - (MAX_FOLLOWED - callbacks.size)
        callbacks.keys.take(needed.coerceAtLeast(0)).forEach { key -> callbacks.remove(key)?.let(::unregister) }
        waiting.forEach { (key, info) -> register(key, info) }
    }

    /** Starts following [key]. Called under [lock]. */
    private fun follow(key: String, info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            register(key, info)
        } else {
            resolveQueue.addLast(key)
            resolveNext()
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun register(key: String, info: NsdServiceInfo) {
        // NSD limits the requests one app holds, and the app's other browsers
        // and advertisers share that budget; the rest wait for a free slot on
        // a later refresh.
        if (callbacks.size >= MAX_FOLLOWED) return
        val gen = generation
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "Server resolve failed: $errorCode")
                // Retried on the next refresh.
                synchronized(lock) { if (callbacks[key] === this) callbacks.remove(key) }
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                val events = synchronized(lock) { if (owns()) events else null } ?: return
                events.resolved(key, serviceInfo.toAdvertisement(serviceInfo.hostAddresses.mapNotNull { it.hostAddress }))
            }

            // The service may be updated again later, so the callback stays
            // registered until the browse loses the service or its slot moves on.
            override fun onServiceLost() {
                val events = synchronized(lock) { if (owns()) events else null } ?: return
                events.gone(key)
            }

            override fun onServiceInfoCallbackUnregistered() = Unit

            /** False once this callback was replaced, released, or belongs to an earlier browse. */
            private fun owns() = gen == generation && callbacks[key] === this
        }
        callbacks[key] = callback
        runCatching { nsdManager.registerServiceInfoCallback(info, Runnable::run, callback) }
            .onFailure {
                Log.w(TAG, "Server resolve could not start", it)
                callbacks.remove(key)
            }
    }

    private fun unregister(callback: NsdManager.ServiceInfoCallback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
        }
    }

    /** Resolves the next queued service if none is resolving. Called under [lock]. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val key = resolveQueue.removeFirstOrNull() ?: return
        // This occurrence of the service: if the browse loses it and finds it
        // again under the same key while this resolve runs, the answer
        // describes the old one and is dropped.
        val info = found[key] ?: return resolveNext()
        val gen = generation
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Server resolve failed: $errorCode")
                synchronized(lock) {
                    resolving = false
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) retrySoon(key, gen)
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val events = synchronized(lock) {
                    resolving = false
                    busyRetries.remove(key)
                    resolveNext()
                    if (gen == generation && found[key] === info) events else null
                } ?: return
                events.resolved(key, serviceInfo.toAdvertisement(listOfNotNull(serviceInfo.host?.hostAddress)))
            }
        }
        runCatching { nsdManager.resolveService(info, listener) }
            .onFailure {
                Log.w(TAG, "Server resolve could not start", it)
                resolving = false
                resolveNext()
            }
    }

    /**
     * Another resolve in this app (casting, pairing, or a previous visit's)
     * held the platform's single slot; try again shortly, a few times, then
     * leave it to the next refresh. Called under [lock].
     */
    private fun retrySoon(key: String, gen: Int) {
        val tries = busyRetries.getOrElse(key) { 0 }
        if (tries >= MAX_BUSY_RETRIES) {
            busyRetries.remove(key)
            return
        }
        busyRetries[key] = tries + 1
        handler.postDelayed({
            synchronized(lock) {
                if (gen != generation || key !in found || key in resolveQueue) return@postDelayed
                resolveQueue.addLast(key)
                resolveNext()
            }
        }, BUSY_RETRY_DELAY_MS)
    }

    private fun NsdServiceInfo.toAdvertisement(addresses: List<String>) = LanAdvertisement(
        instanceName = serviceName.orEmpty(),
        addresses = addresses,
        port = port,
        attributes = attributes.orEmpty(),
    )

    private companion object {
        const val TAG = "ServerDiscovery"
        const val MAX_FOLLOWED = 6
        const val MAX_BUSY_RETRIES = 3
        const val BUSY_RETRY_DELAY_MS = 1_000L

        /**
         * One instance can be found once per network; losing it on one
         * network must not drop it from another.
         */
        fun keyOf(info: NsdServiceInfo): String {
            val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) info.network else null
            return "${info.serviceName}|${network ?: ""}"
        }

        /** Found service types carry a trailing dot on Android (`_silo._tcp.`). */
        fun isSiloServiceType(serviceType: String?): Boolean =
            serviceType?.trimEnd('.') == LanServerDiscovery.SERVICE_TYPE
    }
}
