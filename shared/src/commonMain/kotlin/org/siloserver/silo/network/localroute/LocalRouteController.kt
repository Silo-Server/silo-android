package org.siloserver.silo.network.localroute

import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.HttpOrigin
import org.siloserver.silo.network.httpOrigin

/**
 * What the app remembers about one saved server's local route, keyed by
 * `ServerEntry.id`: the pin learned over the saved address, the deployment it
 * was read from, the admin-set local address, and the local origin that last
 * worked.
 */
@Serializable
data class LocalRouteRecord(
    val pin: String,
    val serverId: String,
    val localUrl: String? = null,
    val lastLocalOrigin: String? = null,
)

/** Persistence for [LocalRouteRecord]s. */
interface LocalRouteStore {
    fun load(entryId: String): LocalRouteRecord?
    fun save(entryId: String, record: LocalRouteRecord?)

    /** Forget the records of servers that are no longer saved. */
    fun retainOnly(entryIds: Set<String>) {}
}

/** What `GET /api/v2/system/connections`, read over the saved address, says about the local route. */
sealed interface LocalRouteOffer {
    /** The server answers TLS on its API port with [pin]; [url] is the admin-set local address. */
    data class Offered(val serverId: String, val pin: String, val url: String?) : LocalRouteOffer

    /** The server answered without a local route: an older server, or TLS is off. */
    data class NotOffered(val serverId: String) : LocalRouteOffer

    /** The document could not be read; nothing is learned. */
    data object Unreadable : LocalRouteOffer
}

/** What checking a candidate local origin found. */
enum class LocalRouteCheck {
    /** It presented the pinned key and answered the saved server's identity. */
    CONFIRMED,

    /** It presented another key: the key may have rotated with `SECRET_KEY`, or it is not the server. */
    OTHER_KEY,

    /** No answer, or an answer that is not the saved server's. */
    UNCONFIRMED,
}

/**
 * Decides when the active server's requests use its local route
 * ([LocalRoute]). It needs no location: a route is used exactly when the server
 * answers directly at a private address with the key pinned from its
 * connections document, so a network that copies the server's identity or
 * address gets nothing. Mirrors silo-apple `LocalRouteController.swift`.
 *
 * - The pin and the admin-set local address come from the connections
 *   document, read over the saved address and only when that is HTTPS.
 * - Candidates: the local origin in use, the one that worked last, and the
 *   admin-set address (or the private IPv4 addresses its host name resolves
 *   to, looked up only once the others fail), each tried as `https` on the
 *   same port. Android does not browse `_silo._tcp` adverts yet.
 * - A candidate that presents another key makes it read the connections
 *   document again at once, since the key may have rotated with `SECRET_KEY`.
 * - It checks while the app is in the foreground on a non-cellular network,
 *   again after every network change and foreground return, and once more
 *   shortly after the route fails, but only once until the network or the app
 *   changes: a LAN address that passes the check but fails real traffic does
 *   not cycle. It never probes on a timer.
 *
 * Every input runs on [scope], which must not run two coroutines at once.
 */
class LocalRouteController(
    private val state: LocalRouteState,
    private val environment: Environment,
    private val scope: CoroutineScope,
) {
    /** The active server and what the route needs from it. */
    data class Server(
        val entryId: String,
        val savedUrl: String,
        val savedOrigin: HttpOrigin,
        /** `ServerEntry.verifiedServerId`, when known. */
        val knownIdentity: String?,
    )

    /** Effects the controller depends on, replaceable in tests. */
    interface Environment {
        val store: LocalRouteStore
        suspend fun readOffer(server: Server): LocalRouteOffer
        /** Whether [origin] presents [pin] over TLS and answers [identity] at `/api/v2/system/identity`. */
        suspend fun confirms(origin: HttpOrigin, pin: String, identity: String): LocalRouteCheck
        suspend fun privateIPv4Addresses(host: String): List<String>
        fun nowMs(): Long
        val retryDelayMs: Long get() = 5_000L
        val offerRefreshIntervalMs: Long get() = 15 * 60_000L
        val networkSettleMs: Long get() = 500L
    }

    private var server: Server? = null
    private var record: LocalRouteRecord? = null
    private var activeLocalOrigin: HttpOrigin? = null
    private var generation = 0L
    // The process lifecycle says when the app is in the foreground.
    private var isForeground = false
    private var allowsLocal = false
    private var lastOfferReadMs: Long? = null
    private var evaluation: Job? = null
    private var retry: Job? = null
    /** Whether the one re-check after a failure has run since the last network change or foreground return. */
    private var retriedSinceTrigger = false
    private var settle: Job? = null

    init {
        scope.launch {
            state.events.collect { event ->
                when (event) {
                    is LocalRouteEvent.Failed -> routeFailed(event.route)
                    is LocalRouteEvent.KeyMismatch -> keyMismatch(event.host, event.port)
                }
            }
        }
    }

    // Inputs

    /** The active server, and whether the app is signed in to it. A different server starts over. */
    fun update(entry: ServerEntry?, signedIn: Boolean) {
        scope.launch {
            val next = if (signedIn) entry?.let(::serverFor) else null
            if (next == server) {
                refresh()
                return@launch
            }
            reset()
            server = next
            // A record from another deployment than the one the saved address
            // now answers as vouches for nothing.
            record = next?.let { environment.store.load(it.entryId) }
                ?.takeIf { next.knownIdentity == null || it.serverId == next.knownIdentity }
            lastOfferReadMs = null
            retriedSinceTrigger = false
            refresh()
        }
    }

    fun foreground(active: Boolean) {
        scope.launch {
            isForeground = active
            if (active) {
                retriedSinceTrigger = false
                refresh()
            } else {
                cancelEvaluation()
            }
        }
    }

    /**
     * The device's network changed. The route goes at once, since its address
     * may now lead somewhere else, and so does any check made on the old
     * network; on a Wi-Fi or wired network it is checked again once the change
     * settles.
     */
    fun networkChanged(allowsLocal: Boolean) {
        scope.launch {
            this@LocalRouteController.allowsLocal = allowsLocal
            retriedSinceTrigger = false
            demote()
            cancelEvaluation()
            settle?.cancel()
            settle = scope.launch {
                delay(environment.networkSettleMs)
                refresh()
            }
        }
    }

    private fun routeFailed(route: LocalRoute) {
        if (route.generation != generation || activeLocalOrigin == null || route.serverId != server?.entryId) return
        demote()
        scheduleRetry()
    }

    private fun keyMismatch(host: String, port: Int) {
        val active = activeLocalOrigin ?: return
        if (!active.host.equals(host, ignoreCase = true) || active.port != port) return
        demote()
        // The pin may have rotated with SECRET_KEY; the next read of the
        // connections document replaces it.
        lastOfferReadMs = null
        scheduleRetry()
    }

    // Evaluation

    /**
     * Re-checks the candidates, and re-reads the connections document when it
     * is due. A known key is checked first, so a slow saved address does not
     * hold up the local one. A newer evaluation replaces one in flight.
     */
    private fun refresh() {
        val server = server ?: return
        if (!isForeground) return
        evaluation?.cancel()
        evaluation = scope.launch {
            val checkedKnownKey = record != null
            // Another key at a candidate may be a rotated one: re-read the pin now.
            if (checkedKnownKey && evaluate(server)) lastOfferReadMs = null
            ensureActive()
            val changed = refreshOfferIfDue(server)
            ensureActive()
            if (!checkedKnownKey || changed) evaluate(server)
        }
    }

    /** Whether the record changed. */
    private suspend fun refreshOfferIfDue(server: Server): Boolean {
        val last = lastOfferReadMs
        if (last != null && environment.nowMs() - last < environment.offerRefreshIntervalMs) return false
        val offer = environment.readOffer(server)
        currentCoroutineContext().ensureActive()
        if (this.server != server) return false
        val offeredBy = when (offer) {
            is LocalRouteOffer.Offered -> offer.serverId
            is LocalRouteOffer.NotOffered -> offer.serverId
            LocalRouteOffer.Unreadable -> return false
        }
        // The saved address answering as another deployment vouches for nothing.
        if (server.knownIdentity != null && offeredBy != server.knownIdentity) return false
        lastOfferReadMs = environment.nowMs()
        val offered = offer as? LocalRouteOffer.Offered ?: run {
            if (record == null) return false
            forgetRecord()
            return true
        }
        val current = record
        var next = current?.takeIf { it.pin == offered.pin && it.serverId == offered.serverId }
        if (next == null) {
            // A new key (SECRET_KEY rotation) or deployment: nothing the old
            // pin trusted stands any more.
            if (current != null) {
                state.removeAllPins()
                demote()
            }
            next = LocalRouteRecord(pin = offered.pin, serverId = offered.serverId)
        }
        next = next.copy(localUrl = offered.url)
        if (next == current) return false
        record = next
        environment.store.save(server.entryId, next)
        return true
    }

    /**
     * Uses the first candidate that confirms, best first. Returns whether the
     * pin may be stale: none confirmed, and one presented another key.
     */
    private suspend fun evaluate(server: Server): Boolean {
        val record = record
        if (!allowsLocal || record == null || this.server != server) {
            demote()
            return false
        }
        val tried = mutableSetOf<HttpOrigin>()
        var otherKey = false
        // Whether checking [origin] ends the evaluation: it confirmed, or the server changed.
        suspend fun settles(origin: HttpOrigin): Boolean {
            if (!tried.add(origin)) return false
            currentCoroutineContext().ensureActive()
            if (this.server != server) return true
            when (environment.confirms(origin, record.pin, record.serverId)) {
                LocalRouteCheck.CONFIRMED -> {
                    currentCoroutineContext().ensureActive()
                    if (this.server == server && allowsLocal) activate(origin, server, record.pin)
                    return true
                }
                LocalRouteCheck.OTHER_KEY -> otherKey = true
                LocalRouteCheck.UNCONFIRMED -> Unit
            }
            return false
        }
        // A host name is looked up only once the known addresses fail, so a
        // slow lookup never holds up one that works.
        if (knownCandidates(record).any { settles(it) }) return false
        if (resolvedCandidates(record).any { settles(it) }) return false
        currentCoroutineContext().ensureActive()
        if (this.server == server) demote()
        return otherKey
    }

    /** The local origin in use, the one that worked last, and an admin-set IP address. */
    private fun knownCandidates(record: LocalRouteRecord): List<HttpOrigin> = listOfNotNull(
        activeLocalOrigin,
        record.lastLocalOrigin?.let(LocalRoute::localOriginForAddress),
        record.localUrl?.let(LocalRoute::localOriginForAddress),
    )

    /** The private IPv4 addresses an admin-set host name resolves to on this network, on the same port. */
    private suspend fun resolvedCandidates(record: LocalRouteRecord): List<HttpOrigin> {
        val configured = record.localUrl ?: return emptyList()
        if (LocalRoute.localOriginForAddress(configured) != null) return emptyList()
        val origin = httpOrigin(configured.trim()) ?: return emptyList()
        return environment.privateIPv4Addresses(origin.host)
            .mapNotNull { LocalRoute.localOriginForAddress("http://$it:${origin.port}") }
    }

    private fun activate(origin: HttpOrigin, server: Server, pin: String) {
        if (activeLocalOrigin == origin && state.active.value?.generation == generation) return
        // Only an address that just presented the key is pinned; the transport
        // then accepts exactly that key there.
        state.trust(origin.host, origin.port, pin)
        generation += 1
        state.publish(LocalRoute(server.entryId, server.savedOrigin, origin, generation))
        activeLocalOrigin = origin
        val current = record ?: return
        val formatted = LocalRoute.format(origin)
        if (current.lastLocalOrigin != formatted) {
            val next = current.copy(lastLocalOrigin = formatted)
            record = next
            environment.store.save(server.entryId, next)
        }
    }

    private fun demote() {
        if (activeLocalOrigin == null) return
        activeLocalOrigin = null
        if (state.active.value?.generation == generation) state.publish(null)
    }

    private fun reset() {
        cancelEvaluation()
        retry?.cancel()
        retry = null
        demote()
        state.publish(null)
        state.removeAllPins()
        server = null
        record = null
    }

    private fun forgetRecord() {
        val server = server ?: return
        record = null
        environment.store.save(server.entryId, null)
        state.removeAllPins()
        demote()
    }

    private fun cancelEvaluation() {
        evaluation?.cancel()
        evaluation = null
    }

    private fun scheduleRetry() {
        if (retriedSinceTrigger) return
        retriedSinceTrigger = true
        retry?.cancel()
        retry = scope.launch {
            delay(environment.retryDelayMs)
            refresh()
        }
    }

    companion object {
        /**
         * The route needs an HTTPS saved address without a path: the API
         * listener serves the API at its root, and only an address the system
         * already trusts may vouch for a key.
         */
        fun serverFor(entry: ServerEntry): Server? {
            val url = entry.url.trim().trimEnd('/')
            val origin = httpOrigin(url) ?: return null
            if (origin.scheme != "https") return null
            val path = runCatching { Url(url).encodedPath }.getOrNull() ?: return null
            if (path.isNotEmpty() && path != "/") return null
            return Server(
                entryId = entry.id,
                savedUrl = url,
                savedOrigin = origin,
                knownIdentity = entry.verifiedServerId?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
