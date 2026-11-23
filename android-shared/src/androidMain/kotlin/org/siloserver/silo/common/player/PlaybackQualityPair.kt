package org.siloserver.silo.common.player

import org.siloserver.silo.common.diagnostics.SiloLog
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO
import org.siloserver.silo.model.playback.videoQualityPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * The two quality fields a video request carries: `quality_preference` and
 * `bandwidth_cap_kbps` (null is uncapped).
 */
data class PlaybackQualityPair(val qualityPreference: String, val bandwidthCapKbps: Int?)

/** Bitrate ceiling applied on top of the cellular pair when Android Data Saver is on. */
const val LOW_DATA_CAP_KBPS = 2_000

/**
 * The network tier playback uses for quality selection:
 * - [UNMETERED]: uses the main quality pair;
 * - [METERED]: cellular, hotspot, or metered Wi-Fi without Data Saver — uses the cellular pair;
 * - [LOW_DATA]: metered with Android Data Saver enabled — applies [LOW_DATA_CAP_KBPS] on top of the cellular pair.
 */
enum class PlaybackNetworkTier {
    UNMETERED,
    METERED,
    LOW_DATA,
}

/**
 * Whether the phone plays at its cellular quality on this network: mobile
 * data (TRANSPORT_CELLULAR, also through a VPN that runs over it), or a
 * non-VPN network Android marks metered (no NET_CAPABILITY_NOT_METERED), such
 * as a phone hotspot or Wi-Fi the user marked metered. A VPN's own metered
 * flag is ignored: Android marks most VPNs metered by default, which would
 * put a home Wi-Fi behind Tailscale on the cellular pair.
 *
 * This is the one metered rule: the speed test and the estimate a start
 * sends use it too ([org.siloserver.silo.common.player.probe.skipsBandwidthProbe]).
 * Android Data Saver tightens a metered network further to [PlaybackNetworkTier.LOW_DATA].
 */
val PlaybackNetworkSnapshot.usesCellularQuality: Boolean
    get() = connected && (cellular || (!vpn && metered))

/** The quality-selection tier for this network snapshot. */
val PlaybackNetworkSnapshot.networkTier: PlaybackNetworkTier
    get() = when {
        !usesCellularQuality -> PlaybackNetworkTier.UNMETERED
        dataSaver -> PlaybackNetworkTier.LOW_DATA
        else -> PlaybackNetworkTier.METERED
    }

/** Applies the [LOW_DATA_CAP_KBPS] ceiling without raising a stricter saved cap. */
internal fun PlaybackQualityPair.withLowDataCeiling(): PlaybackQualityPair {
    val capped = bandwidthCapKbps?.takeIf { it > 0 }?.let { minOf(it, LOW_DATA_CAP_KBPS) } ?: LOW_DATA_CAP_KBPS
    return copy(bandwidthCapKbps = capped)
}

/** The stored pair for a network tier. */
suspend fun PlayerSettingsStore.qualityPair(tier: PlaybackNetworkTier): PlaybackQualityPair =
    when (tier) {
        PlaybackNetworkTier.UNMETERED ->
            PlaybackQualityPair(preferredQualityFlow.first(), maxBitrateKbpsFlow.first())
        PlaybackNetworkTier.METERED ->
            PlaybackQualityPair(cellularPreferredQualityFlow.first(), cellularMaxBitrateKbpsFlow.first())
        PlaybackNetworkTier.LOW_DATA ->
            PlaybackQualityPair(cellularPreferredQualityFlow.first(), cellularMaxBitrateKbpsFlow.first())
                .withLowDataCeiling()
    }

/**
 * The stored resolution preference a start on the current network sends, as
 * [qualityPair] picks it, updated when [network] moves between unmetered and
 * cellular or metered. A detail page ranks its versions with it, so the file
 * it shows is the one the player starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun PlayerSettingsStore.networkPreferredQualityFlow(network: Flow<PlaybackNetworkSnapshot>): Flow<String> =
    network
        .map { it.usesCellularQuality }
        .distinctUntilChanged()
        .flatMapLatest { cellular -> if (cellular) cellularPreferredQualityFlow else preferredQualityFlow }

/**
 * The pair an Auto picked in the player sends: "auto" with the effective bitrate
 * cap for [tier] (null is No limit), so going back to Auto never lifts that cap.
 * Null for any other pick, which keeps the session's cap.
 */
suspend fun PlayerSettingsStore.inPlayerQualityPair(preference: String?, tier: PlaybackNetworkTier): PlaybackQualityPair? {
    if (videoQualityPreference(preference) != PLAYBACK_QUALITY_AUTO) return null
    return autoQualityPair(tier)
}

private suspend fun PlayerSettingsStore.autoQualityPair(tier: PlaybackNetworkTier): PlaybackQualityPair =
    PlaybackQualityPair(PLAYBACK_QUALITY_AUTO, qualityPair(tier).bandwidthCapKbps)

/**
 * A session the network follower moves: which one it is ([sessionId]), the
 * [pair] it plays at, and whether the viewer picked Auto in the player. Auto
 * is not a fixed pick, it means "follow my settings for this connection", so
 * then the quality stays "auto" and only the cap follows the connection.
 */
data class FollowedSession(
    val sessionId: String,
    val pair: PlaybackQualityPair,
    val autoPicked: Boolean = false,
)

/**
 * Moves phone playback to the quality pair of the network it is on when that
 * changes between unmetered and cellular or metered mid-playback.
 *
 * A change applies only once it has held for [settleMs]. Each move is a full
 * reload, and a phone on steady Wi-Fi can sit on mobile data for several
 * seconds while Android checks or roams its Wi-Fi; leaving Wi-Fi for mobile
 * data holds far longer than that. Moments without a connection are skipped
 * rather than read as "not cellular".
 */
class NetworkQualityFollower(
    private val network: Flow<PlaybackNetworkSnapshot>,
    private val settings: PlayerSettingsStore,
    private val settleMs: Long = DEFAULT_SETTLE_MS,
) {
    /**
     * Collects until cancelled. For each settled network, [playing] reports
     * the session, or null when it does not follow the settings: none is
     * active, it plays a download, it is in a Watch Party, or the viewer
     * picked a fixed rung in the player. When its pair differs from the
     * network's, [replan] is asked to move that session to the pair and
     * returns once the move has finished or was refused. The settings read
     * suspends, so [replan] must refuse when playback is no longer the
     * session it is handed.
     *
     * A move that did not take (the request failed, or another change was
     * in flight) is tried again after each of [RETRY_DELAYS_MS], reading the
     * session and settings afresh, until the session plays the network's
     * pair, stops following, or another network supersedes it, settled or
     * not. A settings read that fails waits for the next try the same way,
     * so it never ends the following.
     */
    @OptIn(FlowPreview::class)
    suspend fun follow(
        playing: () -> FollowedSession?,
        replan: suspend (FollowedSession, PlaybackQualityPair) -> Unit,
    ) {
        val latestTier = MutableStateFlow<PlaybackNetworkTier?>(null)
        network
            .filter { it.connected }
            .map { it.networkTier }
            .distinctUntilChanged()
            .onEach { tier ->
                // Without this line a report shows a reload but not what
                // caused it, and a blip that never reloaded leaves no trace.
                if (latestTier.value != null) {
                    val side = when (tier) {
                        PlaybackNetworkTier.UNMETERED -> "unmetered"
                        PlaybackNetworkTier.METERED -> "metered"
                        PlaybackNetworkTier.LOW_DATA -> "data saver"
                    }
                    SiloLog.i(
                        DiagnosticsLogCategory.PLAYBACK,
                        TAG,
                        "connection now $side; quality follows if it holds for ${settleMs / 1_000} s",
                    )
                }
                latestTier.value = tier
            }
            .debounce(settleMs)
            .collectLatest { tier ->
                for (wait in RETRY_DELAYS_MS) {
                    delay(wait)
                    if (latestTier.value != tier) return@collectLatest
                    val current = playing() ?: return@collectLatest
                    val wanted = try {
                        if (current.autoPicked) settings.autoQualityPair(tier) else settings.qualityPair(tier)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        SiloLog.w(
                            DiagnosticsLogCategory.PLAYBACK,
                            TAG,
                            "quality settings read failed",
                            throwable = failure,
                        )
                        continue
                    }
                    if (wanted == current.pair) return@collectLatest
                    // Logged before [replan], which may still decline the move
                    // (another change in flight), so it names a request.
                    SiloLog.i(
                        DiagnosticsLogCategory.PLAYBACK,
                        TAG,
                        when (tier) {
                            PlaybackNetworkTier.UNMETERED ->
                                "connection stayed unmetered; asking playback to move to the main quality"
                            PlaybackNetworkTier.METERED ->
                                "connection stayed metered; asking playback to move to the cellular quality"
                            PlaybackNetworkTier.LOW_DATA ->
                                "connection stayed in data saver; asking playback to move to the low-data ceiling"
                        },
                    )
                    replan(current, wanted)
                }
            }
    }

    companion object {
        private const val TAG = "NetworkQualityFollower"
        const val DEFAULT_SETTLE_MS = 30_000L

        /** The wait before each try at a move: at once, then three retries over 50 seconds. */
        val RETRY_DELAYS_MS = listOf(0L, 5_000L, 15_000L, 30_000L)
    }
}
