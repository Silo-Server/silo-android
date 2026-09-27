package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.WatchPartyEndReason

/** Where the party screens should be for a room: its phase alone decides. */
sealed interface WatchPartyDestination {
    val roomId: String

    data class Lobby(override val roomId: String) : WatchPartyDestination
    data class Player(override val roomId: String) : WatchPartyDestination
}

/**
 * The lobby while the room is in its lobby phase, the player while it plays.
 * A staged item never opens the player, and a host alone still plays.
 */
fun watchPartyDestination(room: RoomSnapshot): WatchPartyDestination? = when (room.phase) {
    RoomPhase.Lobby -> WatchPartyDestination.Lobby(room.roomId)
    RoomPhase.Playing -> WatchPartyDestination.Player(room.roomId)
    RoomPhase.Ended, RoomPhase.Unknown -> null
}

/**
 * What this member may do right now. Every flag depends on the phase, the
 * member's role, the server's capabilities, and whether an action is already
 * in flight; an unknown phase, role, or mode grants nothing.
 */
data class WatchPartyEligibility(
    val isHost: Boolean = false,
    val canStage: Boolean = false,
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    val canSwitchMode: Boolean = false,
    val canSetPolicy: Boolean = false,
    val canLobbyReady: Boolean = false,
    val canSuggest: Boolean = false,
    val canVote: Boolean = false,
    val canPromote: Boolean = false,
    val canQueue: Boolean = false,
    val canEnd: Boolean = false,
    val busy: Boolean = false,
)

fun watchPartyEligibility(
    room: RoomSnapshot?,
    features: WatchPartyFeatures?,
    busy: Boolean,
    personalVotesKnown: Boolean,
): WatchPartyEligibility {
    if (room == null || features == null) return WatchPartyEligibility(busy = busy)
    val host = room.selfRole == MemberRole.Host && room.selfCanManageRoom
    val known = room.selfRole != MemberRole.Unknown
    val lobby = room.phase == RoomPhase.Lobby
    val playing = room.phase == RoomPhase.Playing
    val hostPick = room.selectionMode == RoomSelectionMode.HostPick
    val vote = room.selectionMode == RoomSelectionMode.Vote
    val staged = !room.selectedContentId.isNullOrBlank()
    val free = !busy
    return WatchPartyEligibility(
        isHost = host,
        canStage = free && host && lobby && hostPick && features.stagedSelection,
        canStart = free && host && lobby && staged && features.stagedSelection,
        canStop = free && host && playing && features.stopPlayback,
        canSwitchMode = free && host && lobby && features.selectionModeSwitch && (hostPick || vote),
        canSetPolicy = free && host && (lobby || playing),
        // Advisory and allowed in any lobby, as on Apple: the server clears it
        // whenever the selection changes.
        canLobbyReady = known && !host && lobby && features.lobbyReady,
        canSuggest = free && known && (lobby || playing) && (hostPick || vote),
        canVote = free && known && vote && (lobby || playing) && personalVotesKnown,
        canPromote = free && host && vote && (lobby || playing) && features.voteHostOverride,
        canQueue = free && host && lobby && hostPick && features.stagedSelection,
        canEnd = host && (lobby || playing),
        busy = busy,
    )
}

/**
 * Whether a "host a party" entry point should show. Hidden once the server is
 * known not to host (no staged selection, unsupported, or not allowed); shown
 * while unknown, since the hub then explains what the probe finds.
 */
fun watchPartyHostingOffered(availability: WatchPartyAvailability?): Boolean = when (availability) {
    is WatchPartyAvailability.Available -> availability.features.stagedSelection
    is WatchPartyAvailability.Unsupported, WatchPartyAvailability.NotAllowed -> false
    is WatchPartyAvailability.ProbeFailed, null -> true
}

/** The host, or the suggester matched by both account and profile, may remove a suggestion. */
fun canRemoveSuggestion(room: RoomSnapshot?, suggestion: Suggestion): Boolean {
    room ?: return false
    if (room.selfRole == MemberRole.Host && room.selfCanManageRoom) return true
    val self = room.selfMember ?: return false
    return self.userId.isNotBlank() && self.profileId.isNotBlank() &&
        suggestion.suggesterUserId == self.userId && suggestion.suggesterProfileId == self.profileId
}

/** Advisory lobby readiness: connected guests who marked themselves ready, out of all connected guests. */
data class LobbyReadiness(val ready: Int, val guests: Int)

fun lobbyReadiness(room: RoomSnapshot?): LobbyReadiness {
    val guests = room?.members.orEmpty().filter { it.connected && !it.isHost }
    return LobbyReadiness(ready = guests.count { it.lobbyReady }, guests = guests.size)
}

/** Plain text for a failed Watch Party request. [fallback] covers anything unrecognized. */
fun watchPartyErrorMessage(result: ApiResult<*>, fallback: String): String = when (result) {
    is ApiResult.Success -> fallback
    is ApiResult.NetworkError -> "Couldn't reach the server. Check your connection and try again."
    is ApiResult.Error -> when {
        result.error == WatchPartyEndReason.Ended || result.error == "no_active_room" -> "This Watch Party has ended."
        result.error == "obsolete_room_request" || result.error == "identity_changed" ->
            "Your profile changed, so that didn't go through."
        result.error == "invalid_response" || result.error == "invalid_room_response" ->
            "The server sent an unexpected answer. Try again."
        result.code == 401 -> "Sign in again to use Watch Party."
        result.code == 403 && result.error == "profile_verification_required" -> "Unlock this profile to use Watch Party."
        result.code == 403 -> "Only the host can do that."
        result.code == 404 -> "That Watch Party wasn't found."
        result.code == 409 -> "That didn't work because the party changed. Check the room and try again."
        result.code == 422 -> "That item can't be played in a Watch Party."
        result.code == 429 -> "Too many requests. Wait a moment and try again."
        result.code == 503 -> "Watch Party is unavailable on this server right now."
        else -> fallback
    }
}
