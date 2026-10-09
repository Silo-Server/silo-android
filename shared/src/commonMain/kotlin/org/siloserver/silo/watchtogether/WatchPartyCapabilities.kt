package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.WatchTogetherCapabilitiesV2
import org.siloserver.silo.network.apiv2.PlaybackCapabilitiesV2

/** Playback capability features Watch Party depends on. */
object WatchPartyPlaybackFeatures {
    const val Coordinator = "watch_party_coordinator_v1"
    const val FixedMediaFile = "fixed_media_file_v1"
    const val SourceFallback = "watch_party_source_fallback_v1"
}

/** The v2 room socket protocol a server must serve for native Watch Party. */
const val WATCH_PARTY_SOCKET_PROTOCOL = "silo.room.v2"

/**
 * Optional behaviour, each gated by its own server flag. A missing flag hides
 * the matching control; it never substitutes a different operation.
 */
data class WatchPartyFeatures(
    val stagedSelection: Boolean,
    val stopPlayback: Boolean,
    val selectionModeSwitch: Boolean,
    val lobbyReady: Boolean,
    val picker: Boolean,
    val memberState: Boolean,
    val maxMemberStateIds: Int,
    val voteHostOverride: Boolean,
    val sourceFallback: Boolean,
)

sealed interface WatchPartyAvailability {
    data class Available(val features: WatchPartyFeatures) : WatchPartyAvailability

    /** The server does not serve rooms, or lacks a capability native Watch Party requires. */
    data class Unsupported(val missing: List<String>) : WatchPartyAvailability

    /** The server serves rooms, but this account may not use them. */
    data object NotAllowed : WatchPartyAvailability

    /** Discovery itself failed. Offer Retry; this is not evidence the server lacks support. */
    data class ProbeFailed(val message: String) : WatchPartyAvailability
}

private const val STATE_AVAILABLE = "available"

/**
 * Combines the room and playback capability documents. Entry needs a served
 * v2 room socket with the replacement signal, plus the shared coordinator and
 * fixed-file playback. Everything else is an optional [WatchPartyFeatures] flag.
 */
fun watchPartyAvailability(
    room: WatchTogetherCapabilitiesV2,
    playback: PlaybackCapabilitiesV2,
): WatchPartyAvailability {
    val missing = buildList {
        if (room.state != STATE_AVAILABLE) add("rooms")
        if (room.socketProtocol != WATCH_PARTY_SOCKET_PROTOCOL) add("room_socket")
        if (!room.connectionReplaced) add("connection_replaced")
        if (playback.state != STATE_AVAILABLE) add("playback")
        if (WatchPartyPlaybackFeatures.Coordinator !in playback.features) add(WatchPartyPlaybackFeatures.Coordinator)
        if (WatchPartyPlaybackFeatures.FixedMediaFile !in playback.features) add(WatchPartyPlaybackFeatures.FixedMediaFile)
    }
    if (missing.isNotEmpty()) return WatchPartyAvailability.Unsupported(missing)
    if (room.allowed != true || !playback.allowed) return WatchPartyAvailability.NotAllowed
    return WatchPartyAvailability.Available(
        WatchPartyFeatures(
            stagedSelection = room.stagedSelection,
            stopPlayback = room.stopPlayback,
            selectionModeSwitch = room.selectionModeSwitch,
            lobbyReady = room.lobbyReady,
            picker = room.picker,
            memberState = room.memberState && room.maxMemberStateIds > 0,
            maxMemberStateIds = if (room.memberState) room.maxMemberStateIds.coerceAtLeast(0) else 0,
            voteHostOverride = room.voteHostOverride,
            sourceFallback = WatchPartyPlaybackFeatures.SourceFallback in playback.features,
        ),
    )
}
