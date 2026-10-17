package org.siloserver.silo.common.player

import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_AUTO
import org.siloserver.silo.model.playback.videoQualityPreference
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
 * A session the network follower moves: the [pair] it plays at, and whether
 * the viewer picked Auto in the player. Auto is not a fixed pick, it means
 * "follow my settings for this connection", so then the quality stays "auto"
 * and only the cap follows the connection.
 */
data class FollowedSession(val pair: PlaybackQualityPair, val autoPicked: Boolean = false)

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
     * network's, [replan] is asked to move to that pair.
     */
    @OptIn(FlowPreview::class)
    suspend fun follow(
        playing: () -> FollowedSession?,
        replan: suspend (PlaybackQualityPair) -> Unit,
    ) {
        network
            .filter { it.connected }
            .map { it.usesCellularQuality }
            .distinctUntilChanged()
            .debounce(settleMs)
            .collect { cellular ->
                val current = playing() ?: return@collect
                val wanted = if (current.autoPicked) {
                    settings.autoQualityPair(cellular)
                } else {
                    settings.qualityPair(cellular)
                }
                if (wanted != current.pair) replan(wanted)
            }
    }

    companion object {
        const val DEFAULT_SETTLE_MS = 5_000L
    }
}
