package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText
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

/**
 * Why a Watch Party request failed, sorted from its result the same way on
 * phone and TV. [Other] carries the caller's fallback for anything unrecognized.
 */
sealed class WatchPartyError(val text: UiText) {
    data object Unreachable : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorUnreachable))
    data object Ended : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorEnded))
    data object ProfileChanged : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorProfileChanged))
    data object UnexpectedAnswer : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorUnexpectedAnswer))
    data object SignInAgain : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorSignIn))
    data object ProfileLocked : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorProfileLocked))
    data object HostOnly : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorHostOnly))
    data object NotFound : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorNotFound))
    data object PartyChanged : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorPartyChanged))
    data object NotPlayable : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorNotPlayable))
    data object RateLimited : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorRateLimited))
    data object ServerUnavailable : WatchPartyError(UiText.Key(SharedString.WatchPartyErrorServerUnavailable))
    data class Other(val fallback: UiText) : WatchPartyError(fallback)

    companion object {
        fun of(result: ApiResult<*>, fallback: UiText): WatchPartyError = when (result) {
            is ApiResult.Success -> Other(fallback)
            is ApiResult.NetworkError -> Unreachable
            is ApiResult.Error -> when {
                result.error == WatchPartyEndReason.Ended || result.error == "no_active_room" -> Ended
                result.error == "obsolete_room_request" || result.error == "identity_changed" -> ProfileChanged
                result.error == "invalid_response" || result.error == "invalid_room_response" -> UnexpectedAnswer
                result.code == 401 -> SignInAgain
                result.code == 403 && result.error == "profile_verification_required" -> ProfileLocked
                result.code == 403 -> HostOnly
                result.code == 404 -> NotFound
                result.code == 409 -> PartyChanged
                result.code == 422 -> NotPlayable
                result.code == 429 -> RateLimited
                result.code == 503 -> ServerUnavailable
                else -> Other(fallback)
            }
        }
    }
}

/** What a screen shows for a failed Watch Party request; [fallback] covers anything unrecognized. */
fun watchPartyErrorText(result: ApiResult<*>, fallback: UiText): UiText = WatchPartyError.of(result, fallback).text

/** [watchPartyErrorText] with a shared catalog string as the fallback. */
fun watchPartyErrorText(result: ApiResult<*>, fallback: SharedString, vararg args: Any): UiText =
    watchPartyErrorText(result, UiText.Key(fallback, args.toList()))
