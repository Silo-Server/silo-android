package org.siloserver.silo.common.network

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.localroute.AndroidLocalRouteEnvironment
import org.siloserver.silo.network.localroute.LocalRouteController
import org.siloserver.silo.network.localroute.LocalRouteStore
import org.siloserver.silo.network.localroute.LocalRoutes
import org.siloserver.silo.network.localroute.SharedPreferencesLocalRouteStore

/**
 * Feeds [LocalRouteController] on Android, for the phone and TV apps alike:
 * the active server and whether it is signed in, the app's foreground state,
 * and the default network. Only Wi-Fi and Ethernet count as a local network;
 * cellular never does, and a VPN carries the transports of the network beneath
 * it, so a VPN over cellular does not count either.
 */
class LocalRouteStarter private constructor(
    private val context: Context,
    private val registry: ServerRegistry,
    private val tokenManager: TokenManager,
    private val controller: LocalRouteController,
    private val store: LocalRouteStore,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    /** [lifecycle] is the process lifecycle (`ProcessLifecycleOwner`). Both apps declare the permission. */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun start(lifecycle: Lifecycle) {
        scope.launch {
            // Only what the route depends on: other entry updates (a fetched
            // name, a contract probe) must not start a new check. The profile
            // is set on sign-in and dropped on sign-out.
            registry.activeEntry
                .distinctUntilChangedBy { entry -> entry?.let { listOf(it.id, it.url, it.verifiedServerId, it.profileId) } }
                .collect { publishActiveServer() }
        }
        scope.launch {
            // A removed server's LAN address and key go with it. An unreadable
            // registry looks empty, so it prunes nothing.
            registry.entries.collect { entries ->
                if (!registry.stateLoadFailed) store.retainOnly(entries.mapTo(mutableSetOf()) { it.id })
            }
        }
        scope.launch {
            // The generation only: the LAN address stays out of the logs.
            LocalRoutes.state.active.collect { route ->
                Log.i(TAG, if (route != null) "local route active (generation ${route.generation})" else "local route not in use")
            }
        }
        lifecycle.addObserver(this)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            Log.w(TAG, "No ConnectivityManager; the local route stays off")
            return
        }
        runCatching { connectivity.registerDefaultNetworkCallback(DefaultNetworkCallback()) }
            .onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed; the local route stays off", it) }
    }

    // Foreground changes reach the controller in the order they happen; the
    // signed-in check that follows only refreshes the active server.
    override fun onStart(owner: LifecycleOwner) {
        controller.foreground(true)
        scope.launch { publishActiveServer() }
    }

    override fun onStop(owner: LifecycleOwner) {
        controller.foreground(false)
    }

    /**
     * Tells the controller about the active server as it is now. One at a time,
     * reading the entry inside the lock, so an older answer never lands after a
     * newer one.
     */
    private suspend fun publishActiveServer() {
        updates.withLock {
            val entry = registry.activeEntry.value
            controller.update(entry, isSignedIn(entry))
        }
    }

    private val updates = Mutex()

    private suspend fun isSignedIn(entry: ServerEntry?): Boolean =
        entry != null && !tokenManager.getAccessTokenForScope(entry.id).isNullOrBlank()

    /**
     * Reports a network change only when the default network, its local
     * eligibility, its interface or its gateways change, not on every signal
     * strength update.
     */
    private inner class DefaultNetworkCallback : ConnectivityManager.NetworkCallback() {
        private var network: Network? = null
        private var capabilities: NetworkCapabilities? = null
        private var link: LinkProperties? = null
        private var signature: String? = null

        override fun onAvailable(network: Network) {
            if (network != this.network) {
                capabilities = null
                link = null
            }
            this.network = network
            publish()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            this.network = network
            capabilities = networkCapabilities
            publish()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            this.network = network
            link = linkProperties
            publish()
        }

        override fun onLost(network: Network) {
            if (network != this.network) return
            this.network = null
            capabilities = null
            link = null
            publish()
        }

        private fun publish() {
            val allowsLocal = capabilities?.allowsLocalRoute() == true
            val gateways = link?.routes.orEmpty()
                .mapNotNull { route -> route.gateway?.takeUnless { it.isAnyLocalAddress }?.hostAddress }
                .sorted()
            val next = listOf(network?.toString(), allowsLocal, link?.interfaceName, gateways).joinToString("|")
            if (next == signature) return
            signature = next
            controller.networkChanged(allowsLocal)
        }
    }

    companion object {
        private const val TAG = "LocalRouteStarter"

        /** A failure here costs the route, never the app: requests stay on the saved address. */
        private val logFailures = CoroutineExceptionHandler { _, failure ->
            // Class name only: the message can carry the server's address.
            Log.w(TAG, "local route check failed: ${failure::class.simpleName}")
        }

        /** The app's starter, driving the process-wide route ([LocalRoutes]). */
        fun create(context: Context, registry: ServerRegistry, tokenManager: TokenManager): LocalRouteStarter {
            val store = SharedPreferencesLocalRouteStore(context.applicationContext)
            val controller = LocalRouteController(
                state = LocalRoutes.state,
                environment = AndroidLocalRouteEnvironment(tokenManager = tokenManager, store = store),
                // One coroutine at a time: the controller's state is confined to it.
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + logFailures),
            )
            return LocalRouteStarter(
                context = context.applicationContext,
                registry = registry,
                tokenManager = tokenManager,
                controller = controller,
                store = store,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logFailures),
            )
        }

        private fun NetworkCapabilities.allowsLocalRoute(): Boolean =
            !hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                (hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
}
