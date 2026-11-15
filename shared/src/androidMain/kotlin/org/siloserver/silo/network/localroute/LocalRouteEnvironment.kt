package org.siloserver.silo.network.localroute

import android.content.Context
import android.os.SystemClock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import org.siloserver.silo.network.HttpOrigin
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.isSameHttpOrigin

/** The process's local-route TLS, shared by every client that may reach the route. */
object LocalRouteTransport {
    val tls: LocalRouteTls by lazy { LocalRouteTls(LocalRoutes.state) }
}

/** Installs the process's local route on [this]; see [localRoute]. */
fun OkHttpClient.Builder.localRoute(media: Boolean = false): OkHttpClient.Builder =
    localRoute(LocalRoutes.state, LocalRouteTransport.tls, media)

/**
 * [LocalRouteRecord]s in app-private preferences. A pin and a LAN address are
 * not secrets: the server publishes both to every signed-in client.
 */
class SharedPreferencesLocalRouteStore(context: Context) : LocalRouteStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val serializer = MapSerializer(String.serializer(), LocalRouteRecord.serializer())

    @Synchronized
    override fun load(entryId: String): LocalRouteRecord? = all()[entryId]

    @Synchronized
    override fun save(entryId: String, record: LocalRouteRecord?) {
        val records = all().toMutableMap()
        if (record == null) records.remove(entryId) else records[entryId] = record
        write(records)
    }

    @Synchronized
    override fun retainOnly(entryIds: Set<String>) {
        val records = all()
        if (records.keys.all { it in entryIds }) return
        write(records.filterKeys { it in entryIds })
    }

    private fun write(records: Map<String, LocalRouteRecord>) {
        preferences.edit().putString(KEY, SiloJson.encodeToString(serializer, records)).apply()
    }

    private fun all(): Map<String, LocalRouteRecord> =
        preferences.getString(KEY, null)
            ?.let { runCatching { SiloJson.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    private companion object {
        const val PREFERENCES = "silo_local_route"
        const val KEY = "records.v1"
    }
}

/** The controller's effects on Android. */
class AndroidLocalRouteEnvironment(
    private val tokenManager: TokenManager,
    override val store: LocalRouteStore,
) : LocalRouteController.Environment {
    // Built on first use, off the main thread, not at app start.
    private val probe by lazy { LocalRouteProbe() }

    /** Read over the saved address with its own bearer, never over the route: this is where the pin is learned. */
    override suspend fun readOffer(server: LocalRouteController.Server): LocalRouteOffer {
        val scope = tokenManager.snapshotCurrentScope() ?: return LocalRouteOffer.Unreadable
        if (scope.serverId != server.entryId || !isSameHttpOrigin(scope.serverUrl, server.savedUrl)) {
            return LocalRouteOffer.Unreadable
        }
        val bearer = tokenManager.getAccessTokenForScope(scope)?.takeIf { it.isNotBlank() }
            ?: return LocalRouteOffer.Unreadable
        return probe.readOffer(server.savedUrl, bearer)
    }

    override suspend fun confirms(origin: HttpOrigin, pin: String, identity: String): LocalRouteCheck =
        probe.confirms(origin, pin, identity)

    override suspend fun privateIPv4Addresses(host: String): List<String> = probe.privateIPv4Addresses(host)

    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
