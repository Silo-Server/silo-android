package org.siloserver.silo.common.player.probe

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.siloserver.silo.common.diagnostics.SiloLog
import org.siloserver.silo.common.player.PlaybackNetworkEvidenceProvider
import org.siloserver.silo.common.player.PlaybackNetworkSnapshot
import org.siloserver.silo.common.player.usesCellularQuality
import org.siloserver.silo.domain.player.SpeedTestMeter
import org.siloserver.silo.domain.player.SpeedTestMode
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.playback.CONNECTION_SPEED_V1_FEATURE
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.BandwidthProbeResponseV2
import org.siloserver.silo.network.apiv2.PlaybackBandwidthProbeResultV2
import org.siloserver.silo.network.apiv2.PlaybackV2Api
import org.siloserver.silo.network.localroute.LocalRoute
import org.siloserver.silo.network.localroute.LocalRouteState
import org.siloserver.silo.network.localroute.LocalRoutes
import org.siloserver.silo.network.localroute.onSavedAddress
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
     * The highest current reading's kbps for a start on [serverId]
     * (`AuthScopeSnapshot.serverId`) whose media takes the local route only
     * when [allowsLocalRoute], on the network in use now. Null when the
     * readings belong to another server or route (a borrowed TV identity, a
     * Watch Party start beside a local route), none is current, or the
     * network is metered or cellular.
     */
    fun freshEstimateKbps(serverId: String?, allowsLocalRoute: Boolean): Int?

    /** Playback is starting: stop a running probe and hold off new ones while the media loads. */
    fun playbackStarting() = Unit

    companion object {
        /** No probe: playback requests carry no estimate. */
        val None: PlaybackBandwidthEstimates = object : PlaybackBandwidthEstimates {
            override fun freshEstimateKbps(serverId: String?, allowsLocalRoute: Boolean): Int? = null
        }
    }
}

/** One speed test result, as Settings shows it and the server stores it. */
data class BandwidthEstimate(
    val kbps: Int,
    val bytes: Long,
    val durationMs: Int,
    /** `wifi`, `ethernet`, `cellular` or `other`, as reported to the server. */
    val transport: String,
    /** When it was measured, in epoch milliseconds. */
    val measuredAtEpochMs: Long,
    /** The link is at least [kbps]: a quick test that reached its target, or a capped rate. */
    val lowerBound: Boolean = false,
)

/** The rate this device's playback received data at, which the server kept. Always a lower bound. */
data class PlaybackSample(val kbps: Int, val measuredAtEpochMs: Long)

/** What a probe request came to. */
sealed interface ProbeOutcome {
    /**
     * [estimate] was measured. With [saved] false the server did not store
     * it: starts send it only when the report failed in transport, and
     * Settings goes on showing the server's reading.
     */
    data class Measured(val estimate: BandwidthEstimate, val saved: Boolean = true) : ProbeOutcome

    /** A current reading the server held for this device proves the link: no test ran. */
    data object Stored : ProbeOutcome

    /** The admin turned automatic tests off and nothing current proves the link: no test ran. */
    data object AutomaticOff : ProbeOutcome

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

/**
 * Emits each time the signed-in profile changes to another one on the same
 * server, given (server id, profile id) pairs. The speed test's target follows
 * the profile's libraries. A change of server, or one through no profile, is a
 * change of the active server instead.
 */
fun Flow<Pair<String?, String?>>.profileSwitches(): Flow<Unit> = flow {
    var previous: Pair<String?, String?>? = null
    collect { next ->
        val last = previous
        previous = next
        val sameServer = last?.first != null && last.first == next.first
        val otherProfile = last?.second != null && next.second != null && last.second != next.second
        if (sameServer && otherProfile) emit(Unit)
    }
}

/**
 * Speed tests are skipped on this network, and starts send no estimate: it is
 * metered or cellular by the rule that picks the phone's cellular quality, so
 * a VPN's own metered flag does not count.
 */
val PlaybackNetworkSnapshot.skipsBandwidthProbe: Boolean
    get() = usesCellularQuality

/** The probe endpoint of the active server and its schedule, for one check or test and its report. */
class BandwidthProbeTarget(
    /** `ServerEntry.id` of the server the probe measures. */
    val serverId: String,
    /** `bandwidth_probe_url`, resolved; [bandwidthProbeDownloadUrl] adds the size. */
    val url: String,
    val scope: AuthScopeSnapshot,
    /** `bandwidth_probe_target_kbps`, the rate a quick test has to prove; null when the server has none. */
    val targetKbps: Int? = null,
    /** `bandwidth_probe_automatic`: false when the admin turned automatic tests off. */
    val automatic: Boolean = true,
    /** `bandwidth_probe_max_age_seconds`, or [BANDWIDTH_READING_LIFETIME_MS] when the server sends none. */
    val readingLifetimeMs: Long = BANDWIDTH_READING_LIFETIME_MS,
)

/** The readings the server holds for this device on its current connection ([BandwidthProbeServer.storedReading]). */
sealed interface StoredReadingLookup {
    /** At least one of [probe] and [sample], current or not. */
    data class Found(val probe: BandwidthEstimate?, val sample: PlaybackSample?) : StoredReadingLookup

    /** No reading for this device on this connection, or none with a time. */
    data object None : StoredReadingLookup

    data class Failed(val cause: Throwable?) : StoredReadingLookup
}

/** The finding of a [BandwidthProbeServer.target] lookup. */
sealed interface BandwidthProbeTargetLookup {
    data class Found(val target: BandwidthProbeTarget) : BandwidthProbeTargetLookup
    data object NotSupported : BandwidthProbeTargetLookup
    data class Failed(val cause: Throwable?) : BandwidthProbeTargetLookup
}

/** What became of a result's `POST /api/v2/playback/bandwidth-probe/results`. */
enum class ProbeReport {
    STORED,

    /** The server answered and did not store it. */
    REFUSED,

    /** The request got no answer: the connection failed. */
    UNSENT,
}

/** The server side of a probe: discovery, the readings, the download and the report. */
interface BandwidthProbeServer {
    /** The active profile's probe endpoint and schedule, read from the capability response. */
    suspend fun target(): BandwidthProbeTargetLookup

    /** Downloads the probe for [mode] and hands the answer to [read]. */
    suspend fun download(
        target: BandwidthProbeTarget,
        mode: SpeedTestMode,
        read: suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult,
    ): BandwidthProbeResult

    /** POSTs [result]. */
    suspend fun report(target: BandwidthProbeTarget, result: PlaybackBandwidthProbeResultV2): ProbeReport

    /**
     * The readings the server already holds for this device on the connection
     * it is using now: `probe` and `playback_sample` of
     * `GET /api/v2/playback/connection`.
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
    private val localRoutes: LocalRouteState = LocalRoutes.state,
) : BandwidthProbeServer {
    override suspend fun target(): BandwidthProbeTargetLookup {
        // The report is profile-scoped, so a probe needs a chosen profile.
        val scope = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
            ?: return BandwidthProbeTargetLookup.NotSupported
        return when (val result = api.capabilities(scope)) {
            is ApiResult.Success -> {
                val capabilities = result.data
                val advertised = capabilities.bandwidthProbeUrl?.takeIf {
                    it.isNotBlank() && CONNECTION_SPEED_V1_FEATURE in capabilities.features
                } ?: return BandwidthProbeTargetLookup.NotSupported
                // A lookup over the local route names the server's LAN
                // address, which would go out without credentials. On the
                // saved address it carries them, and still takes the route.
                val url = runCatching { resolveBandwidthProbeUrl(scope.serverUrl, advertised) }.getOrNull()
                    ?.let { localRoutes.onSavedAddress(it, scope.serverUrl) }
                    ?: return BandwidthProbeTargetLookup.NotSupported
                BandwidthProbeTargetLookup.Found(
                    BandwidthProbeTarget(
                        serverId = scope.serverId,
                        url = url,
                        scope = scope,
                        targetKbps = capabilities.bandwidthProbeTargetKbps?.takeIf { it > 0 },
                        automatic = capabilities.bandwidthProbeAutomatic,
                        readingLifetimeMs = capabilities.bandwidthProbeMaxAgeSeconds?.takeIf { it > 0 }
                            ?.let { it * 1_000 } ?: BANDWIDTH_READING_LIFETIME_MS,
                    ),
                )
            }
            // An older server has no v2 capabilities at all.
            is ApiResult.Error -> if (result.code == 404) BandwidthProbeTargetLookup.NotSupported
            else BandwidthProbeTargetLookup.Failed(null)
            is ApiResult.NetworkError -> BandwidthProbeTargetLookup.Failed(result.exception)
        }
    }

    override suspend fun download(
        target: BandwidthProbeTarget,
        mode: SpeedTestMode,
        read: suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult,
    ): BandwidthProbeResult = api.bandwidthProbe(target.scope, bandwidthProbeDownloadUrl(target.url, mode), read)

    override suspend fun report(target: BandwidthProbeTarget, result: PlaybackBandwidthProbeResultV2): ProbeReport =
        when (api.reportBandwidthProbe(target.scope, result)) {
            is ApiResult.Success -> ProbeReport.STORED
            is ApiResult.Error -> ProbeReport.REFUSED
            is ApiResult.NetworkError -> ProbeReport.UNSENT
        }

    override suspend fun storedReading(target: BandwidthProbeTarget): StoredReadingLookup =
        when (val result = api.connection(target.scope)) {
            is ApiResult.Success -> {
                val probe = result.data.probe?.let { stored ->
                    stored.measuredAt?.let(::parseRfc3339ToEpochMillis)?.let { measuredAt ->
                        BandwidthEstimate(
                            kbps = stored.kbps,
                            bytes = 0,
                            durationMs = 0,
                            transport = stored.transport ?: "other",
                            measuredAtEpochMs = measuredAt,
                            lowerBound = stored.lowerBound,
                        )
                    }
                }
                val sample = result.data.playbackSample?.let { stored ->
                    stored.measuredAt?.let(::parseRfc3339ToEpochMillis)?.let { PlaybackSample(stored.kbps, it) }
                }
                if (probe == null && sample == null) StoredReadingLookup.None else StoredReadingLookup.Found(probe, sample)
            }
            is ApiResult.Error -> if (result.code == 404) StoredReadingLookup.None else StoredReadingLookup.Failed(null)
            is ApiResult.NetworkError -> StoredReadingLookup.Failed(result.exception)
        }
}

/**
 * Keeps this device's connection readings while Silo is in the foreground and
 * signed in, for playback starts ([freshEstimateKbps]) and Settings
 * ([lastMeasurement], [runNow]). One per process. The spec is *Connection
 * speed test → When to test* in the server repo's
 * `docs/design/2026-10-17-buffering-quality.md`.
 *
 * - **Readings**: a probe and a playback sample, per server, network and
 *   local route, current while younger than
 *   [BandwidthProbeTarget.readingLifetimeMs]. The server's and this device's
 *   own are kept only within the estimate's bounds, and a time the server
 *   dated ahead of this device's clock counts as now.
 *   Playback starts on that server whose media takes that route send the
 *   highest current one. A settled probe proves the
 *   link; a lower bound proves it only while it is at least the current
 *   [BandwidthProbeTarget.targetKbps], and without a target any current reading
 *   proves it.
 * - **Checks**: when Silo returns to the foreground, a server is signed in,
 *   the profile changes, the network changes (another network or interface,
 *   metering, VPN state, or this server's local route coming or going) or
 *   the network is validated again, and at no other time. None runs under a
 *   borrowed identity (a remote or Cast hand-off on the TV). A check
 *   reads the server's readings ([BandwidthProbeServer.storedReading]) at once,
 *   so a playback start in the first seconds already sends them. Only the
 *   quick test waits,
 *   [PROBE_START_DELAY_MS], and it runs only when automatic tests are on, no
 *   current reading proves the link, and it still may: nothing plays or
 *   starts, and no `429` from that server is waiting out its `Retry-After`. A
 *   check that fails or is refused waits for the next one. Leaving the
 *   foreground stops the checks and any running test.
 * - **Test speed** ([runNow]) runs the accurate test, whatever the readings or
 *   the automatic setting. It replaces a running automatic check rather than
 *   joining it.
 * - **Not**: no test while media plays or starts ([mediaActive],
 *   [playbackStarting], then [PLAYBACK_START_QUIET_MS] for checks), on a
 *   metered or cellular network, or for a server without
 *   `connection_speed_v1`. A check still reads the server's readings while
 *   media plays, since the next start needs them; media starting cancels a
 *   running test, not that read.
 * - **Reporting**: every result is POSTed with the transport it was taken on,
 *   and `lower_bound` when it is one. It is kept for starts once the server
 *   stored it, or when the POST got no answer; a result the server refused
 *   is never kept. Media starting or Silo leaving the foreground does not
 *   cancel the POST.
 *
 * Everything runs on [scope], which must be single-threaded.
 *
 * @param activeServer the active server's id while it is signed in with a
 *   profile, else null.
 * @param profileSwitches emits when the profile changes on the same server
 *   ([profileSwitches]).
 * @param localRoute the local route requests take now ([LocalRouteState.active]).
 */
class BandwidthProbeScheduler(
    private val server: BandwidthProbeServer,
    private val network: PlaybackNetworkEvidenceProvider,
    private val mediaActive: StateFlow<Boolean>,
    private val activeServer: StateFlow<String?>,
    private val scope: CoroutineScope,
    private val profileSwitches: Flow<Unit> = emptyFlow(),
    private val localRoute: StateFlow<LocalRoute?> = MutableStateFlow(null),
    private val probe: BandwidthProbe = BandwidthProbe(),
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) : PlaybackBandwidthEstimates {

    /** The readings for one server, network and route, with the schedule they are judged by. */
    private data class Cached(
        val serverId: String,
        val networkKey: String,
        val probe: BandwidthEstimate?,
        val sample: PlaybackSample?,
        val lifetimeMs: Long,
        val targetKbps: Int?,
    )

    /** One check or test, and the outcome it ends with when something cancels it. */
    private class ProbeRun(val manual: Boolean) {
        lateinit var result: Deferred<ProbeOutcome>
        @Volatile var cancelledFor: ProbeOutcome? = null

        /** Past reading the server's readings and the wait, and into the download. */
        @Volatile var testing = false

        /** The test is over and its result is being POSTed. */
        @Volatile var reporting = false
    }

    private val context = requireNotNull(scope.coroutineContext[ContinuationInterceptor]) {
        "The scheduler scope needs a dispatcher."
    }

    // Read from any thread by freshEstimateKbps; written on the scope.
    @Volatile private var cached: Cached? = null
    @Volatile private var lastPlaybackAtElapsedMs: Long? = null

    // Confined to the scope.
    private var foreground = false
    private var timer: Job? = null
    private var inFlight: ProbeRun? = null

    /**
     * After a `429`, by the server that sent it: the time before which no
     * automatic check tests it. Kept across profile, account and server
     * changes for as long as the process runs.
     */
    private val rateLimits = mutableMapOf<String, Long>()

    private val _lastMeasurement = MutableStateFlow<BandwidthEstimate?>(null)

    /**
     * The probe reading the server holds on the active server and network,
     * read from it or measured here and stored, kept past its lifetime so
     * Settings can show it. Null once the network or server changes.
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
            var last: Pair<String, Boolean>? = null
            combine(network.updates, localRoute) { snapshot, route -> networkKey(snapshot, route) to snapshot.validated }
                .distinctUntilChanged()
                .collect { next ->
                    val previous = last
                    last = next
                    when {
                        previous == null -> Unit
                        previous.first != next.first -> onNetworkChanged()
                        // Validated again: a read that failed meanwhile can reach the server now.
                        next.second && !previous.second -> onConnectivityReturned()
                    }
                }
        }
        scope.launch {
            activeServer.drop(1).collect(::onServerChanged)
        }
        scope.launch {
            profileSwitches.collect { onProfileSwitched() }
        }
        scope.launch {
            mediaActive.filter { it }.collect { onPlaybackActive() }
        }
    }

    override fun freshEstimateKbps(serverId: String?, allowsLocalRoute: Boolean): Int? {
        val entry = cached?.takeIf { it.serverId == serverId && onThisNetwork(it, network.snapshot()) } ?: return null
        // Read over the local route, which media on the saved address does not take.
        if (!allowsLocalRoute && localRoute.value?.serverId == entry.serverId) return null
        return listOfNotNull(
            entry.probe?.takeIf { isCurrent(it.measuredAtEpochMs, entry) }?.kbps,
            entry.sample?.takeIf { isCurrent(it.measuredAtEpochMs, entry) }?.kbps,
        ).maxOrNull()
    }

    override fun playbackStarting() {
        lastPlaybackAtElapsedMs = elapsedClock()
        scope.launch { cancelTest(ProbeOutcome.PlaybackActive) }
    }

    /**
     * **Test speed**: runs the accurate test now, in the foreground, and
     * reports the result. Refuses as the checks do, except that a current
     * reading, the automatic setting and the quiet period after a start do
     * not matter: it runs whenever no media plays. Replaces a running
     * automatic check; joins another Test speed.
     */
    suspend fun runNow(): ProbeOutcome = withContext(context) {
        gate(manual = true)?.let { return@withContext it }
        probeOnce(manual = true)
    }

    internal fun onForeground() {
        foreground = true
        if (activeServer.value != null) check()
    }

    internal fun onBackground() {
        foreground = false
        timer?.cancel()
        timer = null
        // A result already measured still goes to the server.
        if (inFlight?.reporting != true) cancelInFlight(ProbeOutcome.Interrupted)
    }

    private fun onNetworkChanged() {
        forget()
        cancelInFlight(ProbeOutcome.Interrupted)
        if (activeServer.value != null) check()
    }

    /** The same network again, now validated: what it holds still applies. */
    private fun onConnectivityReturned() {
        if (activeServer.value != null) check()
    }

    private fun onServerChanged(serverId: String?) {
        if (cached?.serverId != serverId) forget()
        cancelInFlight(ProbeOutcome.Interrupted)
        if (serverId != null) check() else timer?.cancel()
    }

    /** Another profile on the same server: its target and credentials apply now. */
    private fun onProfileSwitched() {
        cancelInFlight(ProbeOutcome.Interrupted)
        if (activeServer.value != null) check()
    }

    private fun onPlaybackActive() {
        lastPlaybackAtElapsedMs = elapsedClock()
        cancelTest(ProbeOutcome.PlaybackActive)
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

    /** Media is starting: cancels a run that is testing. One still reading the server's readings finishes them and then does not test. */
    private fun cancelTest(reason: ProbeOutcome) {
        if (inFlight?.testing == true) cancelInFlight(reason)
    }

    /** Starts a check now, in the foreground; its test waits inside it. */
    private fun check() {
        timer?.cancel()
        if (!foreground) return
        timer = scope.launch {
            if (activeServer.value == null) return@launch
            // Media playing still lets the check read the server's readings.
            gate(manual = false)?.takeIf { it != ProbeOutcome.PlaybackActive }?.let { return@launch }
            probeOnce(manual = false)
        }
    }

    /** Why a test may not run now, or null when it may. The quiet period after a start holds only checks, not [manual] Test speed. */
    private fun gate(manual: Boolean): ProbeOutcome? {
        val snapshot = network.snapshot()
        val lastPlayback = lastPlaybackAtElapsedMs
        return when {
            activeServer.value == null -> ProbeOutcome.NotSupported
            !snapshot.connected -> ProbeOutcome.Offline
            snapshot.skipsBandwidthProbe -> ProbeOutcome.Metered
            mediaActive.value -> ProbeOutcome.PlaybackActive
            !manual && lastPlayback != null && elapsedClock() - lastPlayback < PLAYBACK_START_QUIET_MS ->
                ProbeOutcome.PlaybackActive
            else -> null
        }
    }

    /** [entry] is for the active server and the network and route in use, and that network may be tested. */
    private fun onThisNetwork(entry: Cached, snapshot: PlaybackNetworkSnapshot): Boolean =
        snapshot.connected && !snapshot.skipsBandwidthProbe &&
            networkKey(snapshot, localRoute.value) == entry.networkKey && activeServer.value == entry.serverId

    /** Younger than [Cached.lifetimeMs]; a reading from the future counts. */
    private fun isCurrent(measuredAtEpochMs: Long, entry: Cached): Boolean =
        wallClock() - measuredAtEpochMs < entry.lifetimeMs

    /**
     * A current reading proves the link: a settled probe, or a lower bound (a
     * quick test that reached its target, or a playback sample) at least the
     * target. Without a target any current reading does: there is nothing to
     * measure against, and a failed target lookup must not send every device
     * holding a lower bound into a test.
     */
    private fun provesTheLink(entry: Cached): Boolean {
        val target = entry.targetKbps
        val probe = entry.probe?.takeIf { isCurrent(it.measuredAtEpochMs, entry) }
        val sample = entry.sample?.takeIf { isCurrent(it.measuredAtEpochMs, entry) }
        if (probe != null && (!probe.lowerBound || target == null || probe.kbps >= target)) return true
        return sample != null && (target == null || sample.kbps >= target)
    }

    private suspend fun probeOnce(manual: Boolean): ProbeOutcome {
        val running = inFlight?.takeIf { it.result.isActive }
        // Test speed asks for an accurate test of its own, so it does not
        // settle for a check's quick one or the server's reading.
        if (running != null && manual && !running.manual) {
            running.cancelledFor = ProbeOutcome.Interrupted
            running.result.cancel()
        }
        val run = running?.takeIf { it.result.isActive } ?: ProbeRun(manual).also { run ->
            run.result = scope.async { measureAndReport(run) }
            inFlight = run
        }
        return try {
            run.result.await()
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            run.cancelledFor ?: ProbeOutcome.Interrupted
        }
    }

    private suspend fun measureAndReport(run: ProbeRun): ProbeOutcome {
        val manual = run.manual
        val target = when (val lookup = server.target()) {
            is BandwidthProbeTargetLookup.Found -> lookup.target
            BandwidthProbeTargetLookup.NotSupported -> return ProbeOutcome.NotSupported
            is BandwidthProbeTargetLookup.Failed -> return ProbeOutcome.NetworkError(lookup.cause)
        }
        // A borrowed identity (a remote or Cast hand-off) holds its own
        // account's readings, which are not this device's to keep.
        if (target.serverId != activeServer.value || target.scope.credentialGenerationId != null) {
            return ProbeOutcome.Interrupted
        }
        val snapshot = network.snapshot()
        val networkKey = networkKey(snapshot, localRoute.value)
        if (!manual) {
            gate(manual = false)?.takeIf { it != ProbeOutcome.PlaybackActive }?.let { return it }
            val stored = when (val lookup = server.storedReading(target)) {
                is StoredReadingLookup.Found -> lookup
                StoredReadingLookup.None -> null
                is StoredReadingLookup.Failed -> return ProbeOutcome.NetworkError(lookup.cause)
            }
            val now = wallClock()
            val entry = remember(target, networkKey, stored?.probe?.fromServer(now), stored?.sample?.fromServer(now))
            if (provesTheLink(entry)) {
                SiloLog.i(
                    DiagnosticsLogCategory.PLAYBACK,
                    TAG,
                    "bandwidth probe: current reading proves the link (probe ${entry.probe?.describe()}, " +
                        "sample ${entry.sample?.kbps} kbps, target ${target.targetKbps} kbps)",
                )
                return ProbeOutcome.Stored
            }
            if (!target.automatic) return ProbeOutcome.AutomaticOff
            // Only the test waits, so the first screen can settle. Then it
            // checks again that it may run.
            delay(PROBE_START_DELAY_MS)
            if (target.serverId != activeServer.value || networkKey(network.snapshot(), localRoute.value) != networkKey) {
                return ProbeOutcome.Interrupted
            }
            rateLimits[target.serverId]?.let { notBefore ->
                val waitMs = notBefore - elapsedClock()
                if (waitMs > 0) return ProbeOutcome.RateLimited(((waitMs + 999) / 1_000).toInt())
            }
        }
        gate(manual)?.let { return it }
        run.testing = true
        val mode = if (manual) SpeedTestMode.ACCURATE else SpeedTestMode.QUICK
        val measured = when (
            val result = probe.measure(mode, target.targetKbps) { read -> server.download(target, mode, read) }
        ) {
            // Never kept or sent outside the bounds a start may carry.
            is BandwidthProbeResult.Measured -> result.takeIf { it.kbps in READING_KBPS }
                ?: return ProbeOutcome.TooLittleData
            is BandwidthProbeResult.RateLimited -> {
                val now = elapsedClock()
                rateLimits.values.removeAll { it <= now }
                rateLimits[target.serverId] = now + result.retryAfterSeconds * 1_000L
                return ProbeOutcome.RateLimited(result.retryAfterSeconds)
            }
            is BandwidthProbeResult.Unavailable -> return ProbeOutcome.Unavailable
            BandwidthProbeResult.TooLittleData -> return ProbeOutcome.TooLittleData
            is BandwidthProbeResult.NetworkError -> return ProbeOutcome.NetworkError(result.cause)
        }
        // A change mid-probe cancels it; this catches one that raced its end.
        if (networkKey(network.snapshot(), localRoute.value) != networkKey || activeServer.value != target.serverId) {
            return ProbeOutcome.Interrupted
        }
        val estimate = BandwidthEstimate(
            kbps = measured.kbps,
            bytes = measured.bytes,
            durationMs = measured.durationMs,
            transport = reportedTransport(snapshot),
            measuredAtEpochMs = wallClock(),
            lowerBound = measured.lowerBound,
        )
        run.testing = false
        run.reporting = true
        val report = try {
            server.report(
                target,
                PlaybackBandwidthProbeResultV2(
                    kbps = estimate.kbps,
                    bytes = estimate.bytes,
                    durationMs = estimate.durationMs,
                    transport = estimate.transport,
                    lowerBound = true.takeIf { estimate.lowerBound },
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ProbeReport.UNSENT
        }
        when (report) {
            ProbeReport.STORED -> remember(target, networkKey, estimate, sample = null)
            // Starts may still send it; Settings shows only what the server holds.
            ProbeReport.UNSENT -> remember(target, networkKey, estimate, sample = null, stored = false)
            ProbeReport.REFUSED -> Unit
        }
        SiloLog.i(
            DiagnosticsLogCategory.PLAYBACK,
            TAG,
            "bandwidth probe: ${mode.name.lowercase()} test ${estimate.describe()} over ${estimate.durationMs} ms " +
                "on ${estimate.transport}" + when (report) {
                    ProbeReport.STORED -> ""
                    ProbeReport.REFUSED -> " (refused, not kept)"
                    ProbeReport.UNSENT -> " (report failed)"
                },
        )
        return ProbeOutcome.Measured(estimate, saved = report == ProbeReport.STORED)
    }

    /**
     * Keeps the readings for [target]'s server on [networkKey]: the newer of
     * [probe] and the probe already kept for it, and [sample] or the kept one.
     * A [probe] the server does not hold ([stored] false) is kept for starts
     * only, not for [lastMeasurement].
     */
    private fun remember(
        target: BandwidthProbeTarget,
        networkKey: String,
        probe: BandwidthEstimate?,
        sample: PlaybackSample?,
        stored: Boolean = true,
    ): Cached {
        val kept = cached?.takeIf { it.serverId == target.serverId && it.networkKey == networkKey }
        val entry = Cached(
            serverId = target.serverId,
            networkKey = networkKey,
            probe = newer(kept?.probe, probe),
            sample = sample ?: kept?.sample,
            lifetimeMs = target.readingLifetimeMs,
            targetKbps = target.targetKbps,
        )
        cached = entry
        if (stored) _lastMeasurement.value = newer(_lastMeasurement.value.takeIf { kept != null }, probe)
        return entry
    }

    private companion object {
        const val TAG = "BandwidthProbe"

        /**
         * Which network and route a measurement describes: another connection,
         * transport, metering, VPN state or local route is another network.
         */
        fun networkKey(snapshot: PlaybackNetworkSnapshot, route: LocalRoute?): String {
            if (!snapshot.connected) return "none"
            val via = route?.let { "${it.serverId}@${LocalRoute.format(it.localOrigin)}" } ?: "saved"
            return with(snapshot) { "$networkId|$transport|$cellular|$metered|$vpn|$via" }
        }

        /** The contract's transport names; a VPN reports what it runs over. */
        fun reportedTransport(snapshot: PlaybackNetworkSnapshot): String = when (snapshot.transport) {
            "wifi", "ethernet", "cellular" -> snapshot.transport
            else -> "other"
        }

        fun BandwidthEstimate.describe(): String = "$kbps kbps" + if (lowerBound) " (lower bound)" else ""

        fun newer(a: BandwidthEstimate?, b: BandwidthEstimate?): BandwidthEstimate? =
            listOfNotNull(a, b).maxByOrNull { it.measuredAtEpochMs }

        /**
         * The bounds of a reading playback can send as `bandwidth_estimate_kbps`.
         * A stored reading outside them would be refused on every start.
         */
        val READING_KBPS = SpeedTestMeter.MIN_KBPS..SpeedTestMeter.MAX_KBPS

        /** A server's reading as this device keeps it: none when out of bounds, and dated no later than [nowMs]. */
        fun BandwidthEstimate.fromServer(nowMs: Long): BandwidthEstimate? =
            takeIf { kbps in READING_KBPS }?.copy(measuredAtEpochMs = minOf(measuredAtEpochMs, nowMs))

        fun PlaybackSample.fromServer(nowMs: Long): PlaybackSample? =
            takeIf { kbps in READING_KBPS }?.copy(measuredAtEpochMs = minOf(measuredAtEpochMs, nowMs))
    }
}
