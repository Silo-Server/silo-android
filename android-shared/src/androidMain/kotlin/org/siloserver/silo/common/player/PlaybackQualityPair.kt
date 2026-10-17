package org.siloserver.silo.common.player

import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO
import org.siloserver.silo.model.playback.videoQualityPreference
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * The two quality fields a video request carries: `quality_preference` and
 * `bandwidth_cap_kbps` (null is uncapped).
 */
data class PlaybackQualityPair(val qualityPreference: String, val bandwidthCapKbps: Int?)

/**
 * Whether the phone plays at its cellular quality on this network: mobile
 * data (TRANSPORT_CELLULAR, also through a VPN that runs over it), or a
 * non-VPN network Android marks metered (no NET_CAPABILITY_NOT_METERED), such
 * as a phone hotspot or Wi-Fi the user marked metered. A VPN's own metered
 * flag is ignored: Android marks most VPNs metered by default, which would
 * put a home Wi-Fi behind Tailscale on the cellular pair.
 */
val PlaybackNetworkSnapshot.usesCellularQuality: Boolean
    get() = connected && (cellular || (!vpn && metered))

/** The stored pair for a network: [cellular] picks the cellular pair over the main one. */
suspend fun PlayerSettingsStore.qualityPair(cellular: Boolean): PlaybackQualityPair =
    if (cellular) {
        PlaybackQualityPair(cellularPreferredQualityFlow.first(), cellularMaxBitrateKbpsFlow.first())
    } else {
        PlaybackQualityPair(preferredQualityFlow.first(), maxBitrateKbpsFlow.first())
    }

/**
 * The pair an Auto picked in the player sends: "auto" with the saved bitrate
 * cap for the connection ([cellular] picks the cellular cap; null is No
 * limit), so going back to Auto never lifts that cap. Null for any other
 * pick, which keeps the session's cap.
 */
suspend fun PlayerSettingsStore.inPlayerQualityPair(preference: String?, cellular: Boolean): PlaybackQualityPair? {
    if (videoQualityPreference(preference) != PLAYBACK_QUALITY_AUTO) return null
    return autoQualityPair(cellular)
}

private suspend fun PlayerSettingsStore.autoQualityPair(cellular: Boolean): PlaybackQualityPair =
    PlaybackQualityPair(PLAYBACK_QUALITY_AUTO, qualityPair(cellular).bandwidthCapKbps)

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
 * A change applies only once it has held for [settleMs], so a brief Wi-Fi
 * drop does not cost two replans; moments without a connection are skipped
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
     * not.
     */
    @OptIn(FlowPreview::class)
    suspend fun follow(
        playing: () -> FollowedSession?,
        replan: suspend (FollowedSession, PlaybackQualityPair) -> Unit,
    ) {
        val latestCellular = MutableStateFlow<Boolean?>(null)
        network
            .filter { it.connected }
            .map { it.usesCellularQuality }
            .distinctUntilChanged()
            .onEach { latestCellular.value = it }
            .debounce(settleMs)
            .collectLatest { cellular ->
                for (wait in RETRY_DELAYS_MS) {
                    delay(wait)
                    if (latestCellular.value != cellular) return@collectLatest
                    val current = playing() ?: return@collectLatest
                    val wanted = if (current.autoPicked) {
                        settings.autoQualityPair(cellular)
                    } else {
                        settings.qualityPair(cellular)
                    }
                    if (wanted == current.pair) return@collectLatest
                    replan(current, wanted)
                }
            }
    }

    companion object {
        const val DEFAULT_SETTLE_MS = 5_000L

        /** The wait before each try at a move: at once, then three retries over 50 seconds. */
        val RETRY_DELAYS_MS = listOf(0L, 5_000L, 15_000L, 30_000L)
    }
}
