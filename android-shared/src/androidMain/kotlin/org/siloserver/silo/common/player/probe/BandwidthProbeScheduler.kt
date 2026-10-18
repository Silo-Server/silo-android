package org.siloserver.silo.common.player.probe

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.siloserver.silo.common.diagnostics.SiloLog
import org.siloserver.silo.common.player.PlaybackNetworkEvidenceProvider
import org.siloserver.silo.common.player.PlaybackNetworkSnapshot
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.playback.CONNECTION_SPEED_V1_FEATURE
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.BandwidthProbeResponseV2
import org.siloserver.silo.network.apiv2.PlaybackBandwidthProbeResultV2
import org.siloserver.silo.network.apiv2.PlaybackV2Api
import org.siloserver.silo.util.parseRfc3339ToEpochMillis

/**
 * How long after a playback start a probe holds off. Media is still loading
 * for a while after a start, so the playing-media check alone would let a
 * timer that fires then probe during startup.
 */
const val PLAYBACK_START_QUIET_MS = 60_000L

/**
 * Where a playback start reads the probe estimate it sends as
 * `bandwidth_estimate_kbps` with source `probe`.
 */
interface PlaybackBandwidthEstimates {
    /**
     * The probe's kbps for the active server on the network in use now, or
     * null when the reading is past its lifetime ([BANDWIDTH_READING_LIFETIME_MS]),
     * there is none, or the network is metered or cellular.
     */
    fun freshEstimateKbps(): Int?

    /** Playback is starting: stop a running probe and hold off new ones while the media loads. */
    fun playbackStarting() = Unit

    companion object {
        /** No probe: playback requests carry no estimate. */
        val None: PlaybackBandwidthEstimates = object : PlaybackBandwidthEstimates {
            override fun freshEstimateKbps(): Int? = null
        }
    }
}

/** One probe measurement, as Settings shows it and the server stores it. */
data class BandwidthEstimate(
    val kbps: Int,
    val bytes: Long,
    val durationMs: Int,
    /** `wifi`, `ethernet`, `cellular` or `other`, as reported to the server. */
    val transport: String,
    /** When it was measured, in epoch milliseconds. */
    val measuredAtEpochMs: Long,
)

/** What a probe request came to. */
sealed interface ProbeOutcome {
    data class Measured(val estimate: BandwidthEstimate) : ProbeOutcome

    /** The server already held this device's reading for the connection, young enough to use: no test ran. */
    data class Stored(val estimate: BandwidthEstimate) : ProbeOutcome

    /** HTTP 429: try again after [retryAfterSeconds]. */
    data class RateLimited(val retryAfterSeconds: Int) : ProbeOutcome

    /** The server refused the download (no capable media path). */
    data object Unavailable : ProbeOutcome

    /** Too little data arrived to give a rate. */
    data object TooLittleData : ProbeOutcome

    /** The request or the capability read failed, or the first byte never came. */
    data class NetworkError(val cause: Throwable?) : ProbeOutcome

    /** A metered or cellular network: speed tests are skipped. */
    data object Metered : ProbeOutcome

    /** No network. */
    data object Offline : ProbeOutcome

    /** Media is playing or starting, and a probe would slow it and under-measure. */
    data object PlaybackActive : ProbeOutcome

    /** Signed out, or the server does not advertise `connection_speed_v1` with a probe URL. */
    data object NotSupported : ProbeOutcome

    /** The network or server changed, or Silo left the foreground, before the probe finished. */
    data object Interrupted : ProbeOutcome
}

/** Speed tests are skipped on this network: it is metered or cellular. */
val PlaybackNetworkSnapshot.skipsBandwidthProbe: Boolean
    get() = metered || cellular

/** The probe endpoint of the active server, for one probe and its report. */
class BandwidthProbeTarget(
    /** `ServerEntry.id` of the server the probe measures. */
    val serverId: String,
    /** The download URL, `size` included. */
    val url: String,
    val scope: AuthScopeSnapshot,
    /** How long a reading on this server stands; see [BANDWIDTH_READING_LIFETIME_MS]. */
    val readingLifetimeMs: Long = BANDWIDTH_READING_LIFETIME_MS,
)

/** This device's stored reading on its current connection ([BandwidthProbeServer.storedReading]). */
sealed interface StoredReadingLookup {
    data class Found(val estimate: BandwidthEstimate) : StoredReadingLookup

    /** The server holds no reading for this device on this connection, or one without a time. */
    data object None : StoredReadingLookup

    data class Failed(val cause: Throwable?) : StoredReadingLookup
}

/** The finding of a [BandwidthProbeServer.target] lookup. */
sealed interface BandwidthProbeTargetLookup {
    data class Found(val target: BandwidthProbeTarget) : BandwidthProbeTargetLookup
    data object NotSupported : BandwidthProbeTargetLookup
    data class Failed(val cause: Throwable?) : BandwidthProbeTargetLookup
}

/** The server side of a probe: discovery, the download and the report. */
interface BandwidthProbeServer {
    /** The active profile's probe endpoint, read from the capability response. */
    suspend fun target(): BandwidthProbeTargetLookup

    suspend fun download(
        target: BandwidthProbeTarget,
        read: suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult,
    ): BandwidthProbeResult

    /** POSTs [result]; true once the server stored it. */
    suspend fun report(target: BandwidthProbeTarget, result: PlaybackBandwidthProbeResultV2): Boolean

    /**
     * The reading the server already holds for this device on the connection
     * it is using now: the `probe` of `GET /api/v2/playback/connection`.
     */
    suspend fun storedReading(target: BandwidthProbeTarget): StoredReadingLookup
}

/**
 * [BandwidthProbeServer] over `/api/v2`. The probe runs only for a server
 * whose capability `features` carry [CONNECTION_SPEED_V1_FEATURE] and that
 * names a `bandwidth_probe_url`.
 */
class PlaybackV2BandwidthProbeServer(
    private val api: PlaybackV2Api,
    private val tokens: TokenManager,
) : BandwidthProbeServer {
    override suspend fun target(): BandwidthProbeTargetLookup {
        // The report is profile-scoped, so a probe needs a chosen profile.
        val scope = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
            ?: return BandwidthProbeTargetLookup.NotSupported
        return when (val result = api.capabilities(scope)) {
            is ApiResult.Success -> {
                val advertised = result.data.bandwidthProbeUrl?.takeIf {
                    it.isNotBlank() && CONNECTION_SPEED_V1_FEATURE in result.data.features
                } ?: return BandwidthProbeTargetLookup.NotSupported
                val url = runCatching { bandwidthProbeDownloadUrl(scope.serverUrl, advertised) }.getOrNull()
                    ?: return BandwidthProbeTargetLookup.NotSupported
                BandwidthProbeTargetLookup.Found(BandwidthProbeTarget(scope.serverId, url, scope))
            }
            // An older server has no v2 capabilities at all.
            is ApiResult.Error -> if (result.code == 404) BandwidthProbeTargetLookup.NotSupported
            else BandwidthProbeTargetLookup.Failed(null)
            is ApiResult.NetworkError -> BandwidthProbeTargetLookup.Failed(result.exception)
        }
    }

    override suspend fun download(
        target: BandwidthProbeTarget,
        read: suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult,
    ): BandwidthProbeResult = api.bandwidthProbe(target.scope, target.url, read)

    override suspend fun report(target: BandwidthProbeTarget, result: PlaybackBandwidthProbeResultV2): Boolean =
        api.reportBandwidthProbe(target.scope, result) is ApiResult.Success

    override suspend fun storedReading(target: BandwidthProbeTarget): StoredReadingLookup =
        when (val result = api.connection(target.scope)) {
            is ApiResult.Success -> {
                val probe = result.data.probe
                val measuredAt = probe?.measuredAt?.let(::parseRfc3339ToEpochMillis)
                if (probe == null || measuredAt == null) {
                    StoredReadingLookup.None
                } else {
                    StoredReadingLookup.Found(
                        BandwidthEstimate(
                            kbps = probe.kbps,
                            bytes = 0,
                            durationMs = 0,
                            transport = probe.transport ?: "other",
                            measuredAtEpochMs = measuredAt,
                        ),
                    )
                }
            }
            is ApiResult.Error -> if (result.code == 404) StoredReadingLookup.None else StoredReadingLookup.Failed(null)
            is ApiResult.NetworkError -> StoredReadingLookup.Failed(result.exception)
        }
}

/**
 * Keeps a reading of the connection while Silo is in the foreground and
 * signed in, for playback starts ([freshEstimateKbps]) and Settings
 * ([lastMeasurement], [runNow]). One per process.
 *
 * - **Reading**: one per server and network, used until it is
 *   [BandwidthProbeTarget.readingLifetimeMs] old. Playback starts send it for
 *   that whole lifetime.
 * - **Checks**: [PROBE_START_DELAY_MS] after Silo returns to the foreground, a
 *   server is signed in or the network changes, and at no other time. A check
 *   with a reading in hand does nothing. Otherwise it asks the server for the
 *   reading it holds for this device on this connection
 *   ([BandwidthProbeServer.storedReading]) and measures only when the server
 *   has none young enough. A check that fails or is refused waits for the next
 *   one, and after a `429` none runs before its `Retry-After`. Leaving the
 *   foreground stops the checks and any running probe.
 * - **Test speed** ([runNow]) always measures. It replaces a running
 *   automatic check rather than joining it.
 * - **Not**: while media plays or starts ([mediaActive], [playbackStarting],
 *   then [PLAYBACK_START_QUIET_MS]), on a metered or cellular network, or for
 *   a server without `connection_speed_v1`. Media starting cancels a running
 *   probe.
 * - **Reporting**: every measurement is POSTed with the transport it was
 *   taken on.
 *
 * Everything runs on [scope], which must be single-threaded.
 *
 * @param activeServer the active server's id while it is signed in with a
 *   profile, else null.
 */
class BandwidthProbeScheduler(
    private val server: BandwidthProbeServer,
    private val network: PlaybackNetworkEvidenceProvider,
    private val mediaActive: StateFlow<Boolean>,
    private val activeServer: StateFlow<String?>,
    private val scope: CoroutineScope,
    private val probe: BandwidthProbe = BandwidthProbe(),
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) : PlaybackBandwidthEstimates {

    private data class Cached(
        val serverId: String,
        val networkKey: String,
        val estimate: BandwidthEstimate,
        val lifetimeMs: Long,
    )

    /** One check or test, and the outcome it ends with when something cancels it. */
    private class ProbeRun(val manual: Boolean) {
        lateinit var result: Deferred<ProbeOutcome>
        @Volatile var cancelledFor: ProbeOutcome? = null
    }

    private val context = requireNotNull(scope.coroutineContext[ContinuationInterceptor]) {
        "The scheduler scope needs a dispatcher."
    }

    // Read from any thread by freshEstimateKbps; written on the scope.
    @Volatile private var cached: Cached? = null
    @Volatile private var lastPlaybackAtElapsedMs: Long? = null

    /** After a `429`, no automatic check measures before this time. */
    private var notBeforeElapsedMs: Long? = null

    // Confined to the scope.
    private var foreground = false
    private var timer: Job? = null
    private var inFlight: ProbeRun? = null

    private val _lastMeasurement = MutableStateFlow<BandwidthEstimate?>(null)

    /**
     * The reading on the active server and network, measured here or taken
     * from the server, kept past its lifetime so Settings can show it. Null
     * once the network or server changes.
     */
    val lastMeasurement: StateFlow<BandwidthEstimate?> = _lastMeasurement.asStateFlow()

    /** The network as the probe sees it, for Settings. */
    val networkUpdates: Flow<PlaybackNetworkSnapshot> get() = network.updates

    /** Follows [lifecycle] (the process lifecycle) and the network, server and media signals. */
    fun start(lifecycle: Lifecycle) {
        observe()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch { onForeground() }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope.launch { onBackground() }
            }
        })
    }

    internal fun observe() {
        scope.launch {
            network.updates.map { networkKey(it) }.distinctUntilChanged().drop(1).collect { onNetworkChanged() }
        }
        scope.launch {
            activeServer.drop(1).collect(::onServerChanged)
        }
        scope.launch {
            mediaActive.filter { it }.collect { onPlaybackActive() }
        }
    }

    override fun freshEstimateKbps(): Int? {
        val entry = cached ?: return null
        return entry.estimate.kbps.takeIf { isFresh(entry, network.snapshot()) }
    }

    override fun playbackStarting() {
        lastPlaybackAtElapsedMs = elapsedClock()
        scope.launch { cancelInFlight(ProbeOutcome.PlaybackActive) }
    }

    /**
     * **Test speed**: measures now, in the foreground, and reports the result.
     * Refuses as the checks do, except that a reading in hand does not stand in
     * for a new one. Replaces a running automatic check; joins another Test
     * speed.
     */
    suspend fun runNow(): ProbeOutcome = withContext(context) {
        gate()?.let { return@withContext it }
        probeOnce(manual = true)
    }

    internal fun onForeground() {
        foreground = true
        if (activeServer.value != null) schedule(PROBE_START_DELAY_MS)
    }

    internal fun onBackground() {
        foreground = false
        timer?.cancel()
        timer = null
        cancelInFlight(ProbeOutcome.Interrupted)
    }

    private fun onNetworkChanged() {
        forget()
        cancelInFlight(ProbeOutcome.Interrupted)
        if (activeServer.value != null) schedule(PROBE_START_DELAY_MS)
    }

    private fun onServerChanged(serverId: String?) {
        if (cached?.serverId != serverId) forget()
        cancelInFlight(ProbeOutcome.Interrupted)
        if (serverId != null) schedule(PROBE_START_DELAY_MS) else timer?.cancel()
    }

    private fun onPlaybackActive() {
        lastPlaybackAtElapsedMs = elapsedClock()
        cancelInFlight(ProbeOutcome.PlaybackActive)
    }

    private fun forget() {
        cached = null
        _lastMeasurement.value = null
    }

    private fun cancelInFlight(reason: ProbeOutcome) {
        val run = inFlight?.takeIf { it.result.isActive } ?: return
        run.cancelledFor = reason
        run.result.cancel()
    }

    private fun schedule(delayMs: Long) {
        timer?.cancel()
        if (!foreground) return
        timer = scope.launch {
            delay(delayMs.coerceAtLeast(0))
            runScheduled()
        }
    }

    private suspend fun runScheduled() {
        if (activeServer.value == null || hasReading()) return
        if (notBeforeElapsedMs?.let { elapsedClock() < it } == true) return
        if (gate() != null) return
        probeOnce(manual = false)
    }

    /** A reading for this server and network is in hand and still young enough. */
    private fun hasReading(): Boolean = cached?.let { isFresh(it, network.snapshot()) } == true

    /** Why a probe may not run now, or null when it may. */
    private fun gate(): ProbeOutcome? {
        val snapshot = network.snapshot()
        val lastPlayback = lastPlaybackAtElapsedMs
        return when {
            activeServer.value == null -> ProbeOutcome.NotSupported
            !snapshot.connected -> ProbeOutcome.Offline
            snapshot.skipsBandwidthProbe -> ProbeOutcome.Metered
            mediaActive.value -> ProbeOutcome.PlaybackActive
            lastPlayback != null && elapsedClock() - lastPlayback < PLAYBACK_START_QUIET_MS -> ProbeOutcome.PlaybackActive
            else -> null
        }
    }

    /** On the same server and network, and younger than its lifetime; a reading from the future counts. */
    private fun isFresh(entry: Cached, snapshot: PlaybackNetworkSnapshot): Boolean {
        if (!snapshot.connected || snapshot.skipsBandwidthProbe) return false
        if (networkKey(snapshot) != entry.networkKey || activeServer.value != entry.serverId) return false
        return wallClock() - entry.estimate.measuredAtEpochMs <= entry.lifetimeMs
    }

    private suspend fun probeOnce(manual: Boolean): ProbeOutcome {
        val running = inFlight?.takeIf { it.result.isActive }
        // Test speed asks for a measurement of its own, so it does not settle
        // for a check that may only read the server's.
        if (running != null && manual && !running.manual) {
            running.cancelledFor = ProbeOutcome.Interrupted
            running.result.cancel()
        }
        val run = running?.takeIf { it.result.isActive } ?: ProbeRun(manual).also { run ->
            run.result = scope.async {
                measureAndReport(manual).also { outcome ->
                    if (outcome is ProbeOutcome.RateLimited) {
                        notBeforeElapsedMs = elapsedClock() + outcome.retryAfterSeconds * 1_000L
                    }
                }
            }
            inFlight = run
        }
        return try {
            run.result.await()
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            run.cancelledFor ?: ProbeOutcome.Interrupted
        }
    }

    private suspend fun measureAndReport(manual: Boolean): ProbeOutcome {
        val target = when (val lookup = server.target()) {
            is BandwidthProbeTargetLookup.Found -> lookup.target
            BandwidthProbeTargetLookup.NotSupported -> return ProbeOutcome.NotSupported
            is BandwidthProbeTargetLookup.Failed -> return ProbeOutcome.NetworkError(lookup.cause)
        }
        gate()?.let { return it }
        if (target.serverId != activeServer.value) return ProbeOutcome.Interrupted
        val snapshot = network.snapshot()
        val networkKey = networkKey(snapshot)
        if (!manual) adoptStoredReading(target, networkKey)?.let { return it }
        val measured = when (val result = probe.measure { read -> server.download(target, read) }) {
            is BandwidthProbeResult.Measured -> result
            is BandwidthProbeResult.RateLimited -> return ProbeOutcome.RateLimited(result.retryAfterSeconds)
            is BandwidthProbeResult.Unavailable -> return ProbeOutcome.Unavailable
            BandwidthProbeResult.TooLittleData -> return ProbeOutcome.TooLittleData
            is BandwidthProbeResult.NetworkError -> return ProbeOutcome.NetworkError(result.cause)
        }
        // A change mid-probe cancels it; this catches one that raced its end.
        if (networkKey(network.snapshot()) != networkKey || activeServer.value != target.serverId) {
            return ProbeOutcome.Interrupted
        }
        val estimate = BandwidthEstimate(
            kbps = measured.kbps,
            bytes = measured.bytes,
            durationMs = measured.durationMs,
            transport = reportedTransport(snapshot),
            measuredAtEpochMs = wallClock(),
        )
        cached = Cached(target.serverId, networkKey, estimate, target.readingLifetimeMs)
        _lastMeasurement.value = estimate
        val reported = try {
            server.report(
                target,
                PlaybackBandwidthProbeResultV2(estimate.kbps, estimate.bytes, estimate.durationMs, estimate.transport),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
        SiloLog.i(
            DiagnosticsLogCategory.PLAYBACK,
            TAG,
            "bandwidth probe: ${estimate.kbps} kbps over ${estimate.durationMs} ms on ${estimate.transport}" +
                if (reported) "" else " (report failed)",
        )
        return ProbeOutcome.Measured(estimate)
    }

    /**
     * Takes the server's reading for this connection when it is younger than
     * its lifetime ([ProbeOutcome.Stored]); null when a measurement is needed.
     */
    private suspend fun adoptStoredReading(target: BandwidthProbeTarget, networkKey: String): ProbeOutcome? {
        val stored = when (val lookup = server.storedReading(target)) {
            is StoredReadingLookup.Found -> lookup.estimate
            StoredReadingLookup.None -> return null
            is StoredReadingLookup.Failed -> return ProbeOutcome.NetworkError(lookup.cause)
        }
        val entry = Cached(target.serverId, networkKey, stored, target.readingLifetimeMs)
        if (!isFresh(entry, network.snapshot())) return null
        cached = entry
        _lastMeasurement.value = stored
        SiloLog.i(
            DiagnosticsLogCategory.PLAYBACK,
            TAG,
            "bandwidth probe: using the server's ${stored.kbps} kbps reading " +
                "from ${(wallClock() - stored.measuredAtEpochMs) / 60_000} min ago",
        )
        return ProbeOutcome.Stored(stored)
    }

    private companion object {
        const val TAG = "BandwidthProbe"

        /** Which network a measurement describes: another connection, transport or metering is another network. */
        fun networkKey(snapshot: PlaybackNetworkSnapshot): String = with(snapshot) {
            if (!connected) "none" else "$networkId|$transport|$cellular|$metered|$vpn"
        }

        /** The contract's transport names; a VPN reports what it runs over. */
        fun reportedTransport(snapshot: PlaybackNetworkSnapshot): String = when (snapshot.transport) {
            "wifi", "ethernet", "cellular" -> snapshot.transport
            else -> "other"
        }
    }
}
