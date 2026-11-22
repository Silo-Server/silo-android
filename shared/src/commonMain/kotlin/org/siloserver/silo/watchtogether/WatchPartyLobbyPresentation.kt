package org.siloserver.silo.watchtogether

import org.siloserver.silo.common.i18n.SharedPlural
import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion

/*
 * How the lobby reads, shared by phone and TV so both say what the Apple
 * clients say (`WatchPartyLobbyPolicy` in silo-apple). Layout stays in each
 * app; the words and the one foregrounded action live here.
 */

/** How a member's seat reads in the lobby and during playback. */
enum class WatchPartySeatState { Ready, NotReady, Away, Watching, Syncing, Buffering, Joining }

/** The host starts playback, so the host never has to mark ready. */
fun watchPartySeatState(member: RoomMember, phase: RoomPhase): WatchPartySeatState = when {
    !member.connected -> WatchPartySeatState.Away
    phase == RoomPhase.Lobby ->
        if (member.isHost || member.lobbyReady) WatchPartySeatState.Ready else WatchPartySeatState.NotReady
    member.isBuffering -> WatchPartySeatState.Buffering
    member.isSyncing -> WatchPartySeatState.Syncing
    member.isReady -> WatchPartySeatState.Watching
    else -> WatchPartySeatState.Joining
}

/** The short word under a seat's name, or null when the ring already says it. */
fun watchPartySeatStatus(state: WatchPartySeatState): UiText? = when (state) {
    WatchPartySeatState.Ready, WatchPartySeatState.Watching -> null
    WatchPartySeatState.NotReady -> UiText.Key(SharedString.WatchPartySeatNotReady)
    WatchPartySeatState.Away -> UiText.Key(SharedString.WatchPartySeatReconnecting)
    WatchPartySeatState.Syncing -> UiText.Key(SharedString.WatchPartySeatSyncing)
    WatchPartySeatState.Buffering -> UiText.Key(SharedString.WatchPartySeatBuffering)
    WatchPartySeatState.Joining -> UiText.Key(SharedString.WatchPartySeatJoining)
}

/** Host first, then this viewer, then everyone else by name. */
fun watchPartySeatOrder(members: List<RoomMember>): List<RoomMember> =
    members.sortedWith(
        compareByDescending<RoomMember> { it.isHost }
            .thenByDescending { it.isSelf }
            .thenBy { it.displayName.lowercase() },
    )

/** The trailing summary beside "Here now": "only you so far", "2 of 3 ready". Each app words it. */
sealed interface WatchPartyPresence {
    data object OnlyYou : WatchPartyPresence
    data object Nobody : WatchPartyPresence

    /** In the lobby: [ready] of the [here] connected members are ready. */
    data class Ready(val ready: Int, val here: Int) : WatchPartyPresence

    /** During playback: [watching] of the [here] connected members are watching. */
    data class Watching(val watching: Int, val here: Int) : WatchPartyPresence
}

fun watchPartyPresenceSummary(members: List<RoomMember>, phase: RoomPhase): WatchPartyPresence {
    val here = members.filter { it.connected }
    if (here.size <= 1) return if (here.size == 1) WatchPartyPresence.OnlyYou else WatchPartyPresence.Nobody
    return if (phase == RoomPhase.Lobby) {
        WatchPartyPresence.Ready(here.count { watchPartySeatState(it, phase) == WatchPartySeatState.Ready }, here.size)
    } else {
        WatchPartyPresence.Watching(here.count { watchPartySeatState(it, phase) == WatchPartySeatState.Watching }, here.size)
    }
}

/** Connected guests who have not marked ready, for the host's advisory hint: each name, or "Someone". */
fun watchPartyLobbyWaitingNames(members: List<RoomMember>): List<UiText> =
    members.filter { it.connected && !it.isHost && !it.lobbyReady }.map { memberName(it.displayName) }

private fun memberName(displayName: String): UiText =
    displayName.takeIf { it.isNotBlank() }?.let(UiText::Raw) ?: UiText.Key(SharedString.WatchPartySomeone)

/** The one action the lobby foregrounds for this member right now. */
sealed interface WatchPartyPrimaryAction {
    /** Host in host-pick mode with nothing staged. */
    data object ChooseTitle : WatchPartyPrimaryAction

    /** Host may start now. [title] names the vote winner in a voting room. */
    data class Start(val title: String?) : WatchPartyPrimaryAction

    /** Host in a voting room before any suggestion has a vote. */
    data object WaitingForVotes : WatchPartyPrimaryAction

    /** A guest's lobby readiness toggle. */
    data class Ready(val isReady: Boolean) : WatchPartyPrimaryAction

    /** The room is playing; this device can go back to the player. */
    data object ReturnToPlayback : WatchPartyPrimaryAction

    data object None : WatchPartyPrimaryAction
}

private val RoomSnapshot.managedBySelf: Boolean
    get() = selfRole == MemberRole.Host && selfCanManageRoom

/**
 * Whether the host could start now, independent of any request in flight so
 * the lobby's buttons keep their place while one runs. A voting room starts
 * its leader; a host-pick room starts its staged title.
 */
fun watchPartyCanStart(room: RoomSnapshot?, features: WatchPartyFeatures?, suggestions: List<Suggestion>): Boolean {
    if (room == null || !room.managedBySelf || room.phase != RoomPhase.Lobby) return false
    return when (room.selectionMode) {
        // Starting a voting room promotes its leader, which needs the promote route.
        RoomSelectionMode.Vote -> features?.voteHostOverride == true && roomVoteWinner(suggestions) != null
        RoomSelectionMode.HostPick -> features?.stagedSelection == true && !room.selectedContentId.isNullOrBlank()
        else -> false
    }
}

fun watchPartyPrimaryAction(
    room: RoomSnapshot,
    features: WatchPartyFeatures?,
    suggestions: List<Suggestion>,
    selectionUnavailable: Boolean = false,
): WatchPartyPrimaryAction {
    val manages = room.managedBySelf
    when (room.phase) {
        RoomPhase.Playing ->
            return if (selectionUnavailable && !manages) WatchPartyPrimaryAction.None else WatchPartyPrimaryAction.ReturnToPlayback
        RoomPhase.Lobby -> Unit
        else -> return WatchPartyPrimaryAction.None
    }
    // A guest who cannot see the title has nothing to ready up for.
    if (selectionUnavailable && !manages) return WatchPartyPrimaryAction.None
    val canStart = watchPartyCanStart(room, features, suggestions)
    if (manages) {
        return when (room.selectionMode) {
            RoomSelectionMode.Vote ->
                if (canStart) WatchPartyPrimaryAction.Start(roomVoteWinner(suggestions)?.title) else WatchPartyPrimaryAction.WaitingForVotes
            RoomSelectionMode.HostPick ->
                if (canStart) WatchPartyPrimaryAction.Start(null) else WatchPartyPrimaryAction.ChooseTitle
            else -> WatchPartyPrimaryAction.None
        }
    }
    val self = room.selfMember
    if (features?.lobbyReady == true && self != null) return WatchPartyPrimaryAction.Ready(self.lobbyReady)
    return WatchPartyPrimaryAction.None
}

/** The secondary lobby action beside the primary one, if any. */
enum class WatchPartySecondaryAction { ChangeTitle, Suggest }

fun watchPartySecondaryAction(room: RoomSnapshot, primary: WatchPartyPrimaryAction): WatchPartySecondaryAction? {
    if (room.phase != RoomPhase.Lobby) return null
    val manages = room.managedBySelf
    return when {
        manages && room.selectionMode == RoomSelectionMode.HostPick && !room.selectedContentId.isNullOrBlank() &&
            primary is WatchPartyPrimaryAction.Start -> WatchPartySecondaryAction.ChangeTitle
        room.selectionMode == RoomSelectionMode.Vote && primary is WatchPartyPrimaryAction.Start ->
            WatchPartySecondaryAction.Suggest
        // Guests in either mode can put a title forward.
        !manages && room.selfRole != MemberRole.Unknown &&
            (room.selectionMode == RoomSelectionMode.Vote || room.selectionMode == RoomSelectionMode.HostPick) ->
            WatchPartySecondaryAction.Suggest
        else -> null
    }
}

private fun hostName(room: RoomSnapshot): UiText =
    room.members.firstOrNull { it.isHost }?.displayName?.takeIf { it.isNotBlank() }?.let(UiText::Raw)
        ?: UiText.Key(SharedString.WatchPartyTheHost)

private fun text(key: SharedString, vararg args: Any): UiText = UiText.Key(key, args.toList())

/** The quiet line under the lobby's buttons, or null. */
fun watchPartyLobbyHint(room: RoomSnapshot, suggestions: List<Suggestion>): UiText? {
    if (room.phase != RoomPhase.Lobby) return null
    val host = hostName(room)
    if (room.managedBySelf) {
        if (room.selectionMode == RoomSelectionMode.Vote && roomVoteWinner(suggestions) == null) {
            return text(if (suggestions.isEmpty()) SharedString.WatchPartyHintSuggest else SharedString.WatchPartyHintNoVotes)
        }
        if (room.selectionMode == RoomSelectionMode.HostPick && room.selectedContentId.isNullOrBlank()) return null
        val waiting = watchPartyLobbyWaitingNames(room.members)
        return when (waiting.size) {
            0 -> if (room.members.count { it.connected } > 1) text(SharedString.WatchPartyHintEveryoneReady) else null
            1 -> text(SharedString.WatchPartyHintNotReadyOne, waiting[0])
            2 -> text(SharedString.WatchPartyHintNotReadyTwo, waiting[0], waiting[1])
            else -> text(SharedString.WatchPartyHintNotReadyMore, waiting[0], waiting[1])
        }
    }
    if (room.selectionMode == RoomSelectionMode.Vote) {
        return roomVoteWinner(suggestions)?.title?.let { text(SharedString.WatchPartyHintHostStartsWinner, host, it) }
            ?: text(SharedString.WatchPartyHintHostStartsTheWinner, host)
    }
    return if (room.selectedContentId.isNullOrBlank()) null else text(SharedString.WatchPartyHintHostStarts, host)
}

/** The hero's eyebrow, title, and supporting line for the lobby. */
data class WatchPartyLobbyHero(
    val eyebrow: UiText,
    /** Null means "use the staged title's own name". */
    val title: UiText?,
    val supporting: UiText?,
    /** A voting lobby before playback: the question is the title and no poster shows. */
    val isVoteQuestion: Boolean = false,
)

fun watchPartyLobbyHero(room: RoomSnapshot, suggestions: List<Suggestion>, selectionUnavailable: Boolean = false): WatchPartyLobbyHero {
    val host = hostName(room)
    val manages = room.managedBySelf
    if (room.selectionMode == RoomSelectionMode.Vote && room.phase == RoomPhase.Lobby) {
        return WatchPartyLobbyHero(
            eyebrow = UiText.Plural(SharedPlural.WatchPartyVotingEyebrow, suggestions.size),
            title = text(SharedString.WatchPartyHeroVoteTitle),
            supporting = if (manages) {
                text(SharedString.WatchPartyHeroVoteCaptionHost)
            } else {
                text(SharedString.WatchPartyHeroVoteCaptionGuest, host)
            },
            isVoteQuestion = true,
        )
    }
    val eyebrow = text(if (room.phase == RoomPhase.Playing) SharedString.WatchPartyEyebrowNowWatching else SharedString.WatchPartyEyebrowUpNext)
    if (!room.selectedContentId.isNullOrBlank()) {
        return WatchPartyLobbyHero(
            eyebrow = eyebrow,
            title = if (selectionUnavailable) text(SharedString.WatchPartyHeroHidden) else null,
            supporting = if (room.selfRole == MemberRole.Host) {
                text(SharedString.WatchPartyHeroYouChose)
            } else {
                text(SharedString.WatchPartyHeroHostChose, host)
            },
        )
    }
    return WatchPartyLobbyHero(
        eyebrow = text(SharedString.WatchPartyEyebrowOpen),
        title = if (manages) text(SharedString.WatchPartyHeroPick) else text(SharedString.WatchPartyHeroWaiting, host),
        supporting = if (manages) {
            text(SharedString.WatchPartyHeroPickCaption)
        } else {
            text(SharedString.WatchPartyHeroWaitingCaption, host)
        },
    )
}

/** Connection state for the dot beside the code pill. */
enum class WatchPartyConnectionTone { Good, Warning, Neutral, Bad }

/**
 * The dot's colour and word: "Open" or "2 here" when connected, "Host away"
 * for a guest whose host dropped, else the connection state.
 */
fun watchPartyConnectionLabel(
    room: RoomSnapshot,
    connected: Boolean,
    reconnecting: Boolean,
): Pair<WatchPartyConnectionTone, UiText> = when {
    connected && !room.hostConnected && room.selfRole != MemberRole.Host ->
        WatchPartyConnectionTone.Warning to text(SharedString.WatchPartyConnectionHostAway)
    connected -> {
        val here = room.members.count { it.connected }
        WatchPartyConnectionTone.Good to
            if (here <= 1) text(SharedString.WatchPartyConnectionOpen) else UiText.Plural(SharedPlural.WatchPartyHere, here)
    }
    reconnecting -> WatchPartyConnectionTone.Warning to text(SharedString.WatchPartyConnectionReconnecting)
    else -> WatchPartyConnectionTone.Neutral to text(SharedString.WatchPartyConnectionConnecting)
}

/** The lobby's single status banner, in Apple's order of importance, or null. */
fun watchPartyLobbyBanner(room: RoomSnapshot, selectionUnavailable: Boolean, reconnecting: Boolean): UiText? {
    val host = hostName(room)
    return when {
        selectionUnavailable && room.managedBySelf -> text(SharedString.WatchPartyBannerUnavailableHost)
        selectionUnavailable -> text(SharedString.WatchPartyBannerUnavailableGuest, host)
        !room.hostConnected && room.selfRole != MemberRole.Host -> text(SharedString.WatchPartyBannerHostAway, host)
        reconnecting -> WatchPartyReconnectingBanner
        else -> null
    }
}

/** [watchPartyLobbyBanner]'s reconnect banner, which a screen may replace with its own. */
val WatchPartyReconnectingBanner: UiText = UiText.Key(SharedString.WatchPartyReconnecting)

/**
 * Which avatar colour a member gets, matching Apple's `avatarGradient(for:)`:
 * djb2 over "userId:profileId" with 64-bit wrapping, then `abs % size`, so a
 * person has the same colour on every client.
 */
fun watchPartyAvatarIndex(member: RoomMember, paletteSize: Int): Int {
    if (paletteSize <= 0) return 0
    val hash = "${member.userId}:${member.profileId}".fold(5381L) { acc, c -> acc * 33 + c.code }
    return (kotlin.math.abs(hash) % paletteSize).toInt().coerceAtLeast(0)
}
