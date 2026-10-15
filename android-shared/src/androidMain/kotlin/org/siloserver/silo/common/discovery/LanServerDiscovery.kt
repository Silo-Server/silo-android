package org.siloserver.silo.common.discovery

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.common.ui.marquee.ServerBranding
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.DefaultServerIdentityApi
import org.siloserver.silo.network.api.ServerIdentityProbe
import org.siloserver.silo.network.createPlatformHttpClient

/**
 * One Silo server found on the local network and confirmed by its identity.
 * [url] is `http://<address>:<port>` built from the resolved address, never
 * the advertised `.local` name.
 */
data class DiscoveredServer(
    val serverId: String,
    val name: String,
    val url: String,
) {
    /** The port stays, so two deployments on one host are told apart. */
    val detail: String get() = "On this network · ${ServerBranding.hostLabel(url)}"
}

/** What the platform browser resolved for one `_silo._tcp` instance. */
data class LanAdvertisement(
    val instanceName: String,
    /** Numeric addresses; an IPv6 literal may carry a `%scope`. */
    val addresses: List<String>,
    val port: Int,
    val attributes: Map<String, ByteArray?>,
)

/** A DNS-SD browser for `_silo._tcp`; [NsdLanServiceBrowser] on Android. */
interface LanServiceBrowser {
    /** Starts browsing; [events] may be called on any thread until [stop]. */
    fun start(events: Events)

    fun stop()

    /** Asks again for what one-shot resolves can't follow, such as a new DHCP lease. */
    fun refresh()

    interface Events {
        /** [key] resolved, or its addresses, port, or TXT changed. */
        fun resolved(key: String, advertisement: LanAdvertisement)

        /** [key] is no longer reachable; it may resolve again later. */
        fun gone(key: String)

        /** Browsing could not start (multicast unavailable, for example), or stopped on its own. */
        fun failed()
    }
}

/**
 * Lists the Silo servers advertised on the local network while the setup
 * screen is visible (silo-server `docs/architecture/server-discovery.md`,
 * silo-apple `ServerDiscovery.swift`).
 *
 * An advertisement is a hint: its TXT `id` names a deployment, but anyone on
 * the network can advertise anything. A result is listed only when
 * `GET /api/v2/system/identity` at its resolved address reports that same
 * id, and choosing it is the same as typing its address. Results with one id
 * (replicas, or one host seen on several interfaces) share one row.
 */
class LanServerDiscovery(
    private val browser: LanServiceBrowser,
    private val probeIdentity: suspend (String) -> ServerIdentityProbe,
    /** The server's display name from its branding, or null. */
    private val fetchName: suspend (String) -> String?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    /** One browse result and how far its confirmation got. */
    private class Entry(
        val serverId: String,
        val origins: List<String>,
        val instanceName: String,
    ) {
        /** The last confirmed row, listed while non-null. */
        var row: DiscoveredServer? = null

        /** Failed re-checks of [row] in a row. */
        var misses = 0
        var job: Job? = null
    }

    private var scope: CoroutineScope? = null

    /** Advances on start and stop; events from an earlier visit are dropped. */
    private var session = 0
    private var browseFailed = false

    /** One quick restart per visit after a failed browse; later ones wait for the refresh. */
    private var quickRetryUsed = false

    /** Keyed by the browser's result key; entries are replaced, never edited, when an advertisement changes. */
    private val entries = mutableMapOf<String, Entry>()

    fun start() {
        if (scope != null) return
        val session = ++session
        quickRetryUsed = false
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scope = it }
        startBrowse(session, scope)
        scope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                refresh(session, scope)
            }
        }
    }

    fun stop() {
        val scope = scope ?: return
        this.scope = null
        session++
        browser.stop()
        scope.cancel()
        entries.clear()
        _servers.value = emptyList()
    }

    private fun startBrowse(session: Int, scope: CoroutineScope) {
        browseFailed = false
        browser.start(SessionEvents(session, scope))
    }

    /**
     * Every [REFRESH_INTERVAL_MS]: restart a browse that failed, re-check
     * listed rows (a server keeps its advertisement when its address changes,
     * and a renamed server gets its new name), and give results that failed
     * both confirmations another pair, for a server that was still starting.
     */
    private fun refresh(session: Int, scope: CoroutineScope) {
        if (this.session != session) return
        if (browseFailed) {
            restartBrowse(session, scope)
            return
        }
        browser.refresh()
        for ((key, entry) in entries) {
            if (entry.job?.isActive != true) confirm(key, entry, scope)
        }
    }

    private fun restartBrowse(session: Int, scope: CoroutineScope) {
        browser.stop()
        clearEntries()
        startBrowse(session, scope)
    }

    /** A browse can fail while the previous visit's is still stopping; try again shortly, once. */
    private fun onBrowseFailed(session: Int, scope: CoroutineScope) {
        browseFailed = true
        if (quickRetryUsed) return
        quickRetryUsed = true
        scope.launch {
            delay(BROWSE_RETRY_DELAY_MS)
            if (this@LanServerDiscovery.session == session && browseFailed) restartBrowse(session, scope)
        }
    }

    private fun onResolved(key: String, advertisement: LanAdvertisement, scope: CoroutineScope) {
        val serverId = advertisedServerId(advertisement.attributes)
        val origins = lanOrigins(advertisement.addresses, advertisement.port)
        if (serverId == null) {
            onGone(key)
            return
        }
        // A one-shot resolve (before API 34) may return only an address a URL
        // can't use, such as link-local IPv6; keep what the last one found.
        if (origins.isEmpty()) return
        val existing = entries[key]
        if (existing != null && existing.serverId == serverId && existing.origins == origins &&
            existing.instanceName == advertisement.instanceName
        ) {
            return
        }
        existing?.job?.cancel()
        val entry = Entry(serverId, origins, advertisement.instanceName)
        // A row keeps showing while its address is still advertised; a moved
        // address or a changed id (a reinstall, or another deployment on the
        // same name and port) is confirmed afresh before it's listed.
        if (existing != null && existing.serverId == serverId && existing.row?.url in origins) {
            entry.row = existing.row
            entry.misses = existing.misses
        }
        entries[key] = entry
        publish()
        confirm(key, entry, scope)
    }

    private fun onGone(key: String) {
        entries.remove(key)?.job?.cancel() ?: return
        publish()
    }

    /**
     * A new result gets two attempts a few seconds apart; a listed row gets
     * one per refresh and stays through [MAX_MISSES] - 1 failures in a row, so
     * a probe that times out during a network change doesn't pull it from
     * under the person about to choose it.
     */
    private fun confirm(key: String, entry: Entry, scope: CoroutineScope) {
        entry.job?.cancel()
        entry.job = scope.launch {
            val attempts = if (entry.row != null) 1 else MAX_ATTEMPTS
            repeat(attempts) { attempt ->
                if (attempt > 0) delay(RETRY_DELAY_MS)
                val found = confirmAt(entry)
                // Replaced or removed meanwhile: the newer entry reports.
                if (entries[key] !== entry) return@launch
                if (found != null) {
                    entry.row = found
                    entry.misses = 0
                    publish()
                    return@launch
                }
            }
            if (entry.row != null) {
                entry.misses++
                if (entry.misses >= MAX_MISSES) {
                    entry.row = null
                    entry.misses = 0
                }
                publish()
            }
        }
    }

    /** The first of [Entry.origins] whose identity matches the advertised id. */
    private suspend fun confirmAt(entry: Entry): DiscoveredServer? {
        for (origin in entry.origins) {
            val probe = withTimeoutOrNull(PROBE_TIMEOUT_MS) { probeIdentity(origin) }
            if (probe !is ServerIdentityProbe.Identity || probe.serverId != entry.serverId) continue
            val name = withTimeoutOrNull(PROBE_TIMEOUT_MS) { fetchName(origin) }?.trim()?.takeIf { it.isNotEmpty() }
                ?: entry.instanceName.trim().takeIf { it.isNotEmpty() }
                ?: "Silo"
            return DiscoveredServer(serverId = probe.serverId, name = name, url = origin)
        }
        return null
    }

    private fun clearEntries() {
        entries.values.forEach { it.job?.cancel() }
        entries.clear()
        publish()
    }

    private fun publish() {
        _servers.value = discoveredRows(entries.values.mapNotNull { entry -> entry.row?.let { it to entry.misses } })
    }

    /** Routes browser callbacks onto [scope], dropping any from an earlier session. */
    private inner class SessionEvents(
        private val session: Int,
        private val scope: CoroutineScope,
    ) : LanServiceBrowser.Events {
        override fun resolved(key: String, advertisement: LanAdvertisement) =
            post { onResolved(key, advertisement, scope) }

        override fun gone(key: String) = post { onGone(key) }

        override fun failed() = post { onBrowseFailed(session, scope) }

        private fun post(block: () -> Unit) {
            scope.launch { if (this@LanServerDiscovery.session == session) block() }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_silo._tcp"
        internal const val TXT_SERVER_ID = "id"

        /** Discovery runs while someone looks at the setup screen; a dead address must give up quickly. */
        internal const val PROBE_TIMEOUT_MS = 4_000L
        internal const val RETRY_DELAY_MS = 3_000L
        internal const val BROWSE_RETRY_DELAY_MS = 2_000L
        internal const val REFRESH_INTERVAL_MS = 30_000L
        internal const val MAX_ATTEMPTS = 2
        internal const val MAX_MISSES = 3

        /** Addresses tried per result, so one host with many interfaces stays bounded. */
        internal const val MAX_ORIGINS = 4
    }
}

/**
 * Unauthenticated reads of a found address, on a client of their own: an
 * address that only advertised itself must not receive the signed-in
 * server's credentials or this device's metadata, even when it shares that
 * server's origin. These reads run without anyone asking, so each answer is
 * capped at [MAX_BODY_BYTES]; an advertiser can't make the app buffer more.
 */
class LanServerProbe {
    private val client: HttpClient = createPlatformHttpClient(retryOnConnectionFailure = false).config {
        install(HttpTimeout)
    }

    suspend fun identity(serverUrl: String): ServerIdentityProbe =
        readObject(serverUrl, DefaultServerIdentityApi.IDENTITY_PATH)?.string("server_id")
            ?.let { ServerIdentityProbe.Identity(it) }
            ?: ServerIdentityProbe.Unreachable

    /** `server_name` from `GET /api/v2/theme/branding`. */
    suspend fun name(serverUrl: String): String? = readObject(serverUrl, BRANDING_PATH)?.string("server_name")

    /** The JSON object [serverUrl] answers at [path], or null for anything else or anything larger. */
    private suspend fun readObject(serverUrl: String, path: String): JsonObject? = try {
        client.prepareGet(serverUrl.trimEnd('/') + path).execute { response ->
            if (!response.status.isSuccess()) return@execute null
            val body = response.bodyAsChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
            if (body.size > MAX_BODY_BYTES) return@execute null
            SiloJson.parseToJsonElement(body.decodeToString()) as? JsonObject
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val BRANDING_PATH = "/api/v2/theme/branding"

        /** Identity and branding answers are a few hundred bytes. */
        const val MAX_BODY_BYTES = 64 * 1024
    }
}

/** The TXT `id`, or null when absent or blank. Every other key is ignored, including `v`. */
internal fun advertisedServerId(attributes: Map<String, ByteArray?>): String? =
    attributes.entries
        .firstOrNull { it.key.equals(LanServerDiscovery.TXT_SERVER_ID, ignoreCase = true) }
        ?.value
        ?.decodeToString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * `http://` origins for a resolved advertisement in the order to try them:
 * IPv4 first, then routable IPv6 in brackets. Link-local IPv6 is skipped
 * because a URL host can't carry its interface scope; loopback, unspecified
 * and multicast addresses never name a server on this network.
 */
internal fun lanOrigins(addresses: List<String>, port: Int): List<String> {
    if (port !in 1..65535) return emptyList()
    val literals = addresses
        .map { it.trim().removePrefix("/").substringBefore('%') }
        .filter { it.isNotEmpty() }
        .distinct()
    val ipv4 = literals.filter { Ipv4Literal.matches(it) && !isUnusableIpv4(it) }
    val ipv6 = literals.filter { ':' in it && !isLinkLocalIpv6(it) && !isUnusableIpv6(it) }
    return (ipv4.map { "http://$it:$port" } + ipv6.map { "http://[$it]:$port" })
        .take(LanServerDiscovery.MAX_ORIGINS)
}

/**
 * One row per server: of several confirmed results with one id, the one with
 * the fewest failed re-checks, then the lowest URL. Sorted by name.
 */
internal fun discoveredRows(rows: List<Pair<DiscoveredServer, Int>>): List<DiscoveredServer> =
    rows
        .sortedWith(compareBy<Pair<DiscoveredServer, Int>> { it.second }.thenBy { it.first.url })
        .distinctBy { it.first.serverId }
        .map { it.first }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { server: DiscoveredServer -> server.name }.thenBy { it.url })

private val Ipv4Literal = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

private fun isUnusableIpv4(literal: String): Boolean {
    val first = literal.substringBefore('.').toInt()
    return first == 0 || first == 127 || first >= 224
}

private fun isUnusableIpv6(literal: String): Boolean {
    val lower = literal.lowercase()
    return lower == "::" || lower == "::1" || lower.startsWith("ff")
}

private fun isLinkLocalIpv6(literal: String): Boolean {
    val head = literal.lowercase()
    return head.startsWith("fe8") || head.startsWith("fe9") || head.startsWith("fea") || head.startsWith("feb")
}
