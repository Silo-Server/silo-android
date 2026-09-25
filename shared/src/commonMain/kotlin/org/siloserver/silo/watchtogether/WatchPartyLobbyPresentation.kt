package org.siloserver.silo.watchtogether

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
fun watchPartySeatStatus(state: WatchPartySeatState): String? = when (state) {
    WatchPartySeatState.Ready, WatchPartySeatState.Watching -> null
    WatchPartySeatState.NotReady -> "not ready"
    WatchPartySeatState.Away -> "reconnecting"
    WatchPartySeatState.Syncing -> "syncing"
    WatchPartySeatState.Buffering -> "buffering"
    WatchPartySeatState.Joining -> "joining"
}

/** Host first, then this viewer, then everyone else by name. */
fun watchPartySeatOrder(members: List<RoomMember>): List<RoomMember> =
    members.sortedWith(
        compareByDescending<RoomMember> { it.isHost }
            .thenByDescending { it.isSelf }
            .thenBy { it.displayName.lowercase() },
    )

/** The trailing summary beside "Here now": "only you so far", "2 of 3 ready". */
fun watchPartyPresenceSummary(members: List<RoomMember>, phase: RoomPhase): String {
    val here = members.filter { it.connected }
    if (here.size <= 1) return if (here.size == 1) "only you so far" else "nobody connected"
    return if (phase == RoomPhase.Lobby) {
        val ready = here.count { watchPartySeatState(it, phase) == WatchPartySeatState.Ready }
        "$ready of ${here.size} ready"
    } else {
        val watching = here.count { watchPartySeatState(it, phase) == WatchPartySeatState.Watching }
        "$watching of ${here.size} watching"
    }
}

/** Connected guests who have not marked ready, for the host's advisory hint. */
fun watchPartyLobbyWaitingNames(members: List<RoomMember>): List<String> =
    members.filter { it.connected && !it.isHost && !it.lobbyReady }.map { it.displayName.ifBlank { "Someone" } }

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

private fun hostName(room: RoomSnapshot): String =
    room.members.firstOrNull { it.isHost }?.displayName?.ifBlank { null } ?: "The host"

/** The quiet line under the lobby's buttons, or null. */
fun watchPartyLobbyHint(room: RoomSnapshot, suggestions: List<Suggestion>): String? {
    if (room.phase != RoomPhase.Lobby) return null
    val host = hostName(room)
    if (room.managedBySelf) {
        if (room.selectionMode == RoomSelectionMode.Vote && roomVoteWinner(suggestions) == null) {
            return if (suggestions.isEmpty()) "Suggest a title to get the vote going." else "Nobody has voted yet."
        }
        if (room.selectionMode == RoomSelectionMode.HostPick && room.selectedContentId.isNullOrBlank()) return null
        val waiting = watchPartyLobbyWaitingNames(room.members)
        if (waiting.isEmpty()) return if (room.members.count { it.connected } > 1) "Everyone's ready." else null
        val names = waiting.take(2).joinToString(" and ") + if (waiting.size > 2) " and others" else ""
        return "$names ${if (waiting.size == 1) "hasn't" else "haven't"} marked ready. You can still start."
    }
    if (room.selectionMode == RoomSelectionMode.Vote) {
        return "$host starts ${roomVoteWinner(suggestions)?.title ?: "the winner"} when voting settles."
    }
    return if (room.selectedContentId.isNullOrBlank()) null else "$host starts the movie when everyone's set."
}

/** The hero's eyebrow, title, and supporting line for the lobby. */
data class WatchPartyLobbyHero(
    val eyebrow: String,
    /** Null means "use the staged title's own name". */
    val title: String?,
    val supporting: String?,
    /** A voting lobby before playback: the question is the title and no poster shows. */
    val isVoteQuestion: Boolean = false,
)

fun watchPartyLobbyHero(room: RoomSnapshot, suggestions: List<Suggestion>, selectionUnavailable: Boolean = false): WatchPartyLobbyHero {
    val host = hostName(room)
    val manages = room.managedBySelf
    if (room.selectionMode == RoomSelectionMode.Vote && room.phase == RoomPhase.Lobby) {
        val count = suggestions.size
        return WatchPartyLobbyHero(
            eyebrow = "Voting · $count ${if (count == 1) "title" else "titles"}",
            title = "What are we watching?",
            supporting = if (manages) "Everyone votes. You start the leader." else "Tap a title to vote. $host starts the winner.",
            isVoteQuestion = true,
        )
    }
    val eyebrow = if (room.phase == RoomPhase.Playing) "Now watching · together" else "Up next · together"
    if (!room.selectedContentId.isNullOrBlank()) {
        return WatchPartyLobbyHero(
            eyebrow = eyebrow,
            title = if (selectionUnavailable) "A title you can't see" else null,
            supporting = if (room.selfRole == MemberRole.Host) "You chose this" else "$host chose this",
        )
    }
    return WatchPartyLobbyHero(
        eyebrow = "Your party is open",
        title = if (manages) "Pick something\nto watch" else "Waiting for\n$host",
        supporting = if (manages) {
            "Friends can join now with the code. They'll see your pick the moment you choose."
        } else {
            "$host is choosing what to watch. You'll see it here the moment they do."
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
): Pair<WatchPartyConnectionTone, String> = when {
    connected && !room.hostConnected && room.selfRole != MemberRole.Host -> WatchPartyConnectionTone.Warning to "Host away"
    connected -> {
        val here = room.members.count { it.connected }
        WatchPartyConnectionTone.Good to if (here <= 1) "Open" else "$here here"
    }
    reconnecting -> WatchPartyConnectionTone.Warning to "Reconnecting"
    else -> WatchPartyConnectionTone.Neutral to "Connecting"
}

/** The lobby's single status banner, in Apple's order of importance, or null. */
fun watchPartyLobbyBanner(room: RoomSnapshot, selectionUnavailable: Boolean, reconnecting: Boolean): String? {
    val host = hostName(room)
    return when {
        selectionUnavailable && room.managedBySelf ->
            "This title isn't available to your profile. Choose something else so everyone can watch."
        selectionUnavailable -> "This title isn't available to your profile. $host will need to pick something else."
        !room.hostConnected && room.selfRole != MemberRole.Host ->
            "$host lost connection. The party holds for two minutes while they come back."
        reconnecting -> "Reconnecting to the party…"
        else -> null
    }
}

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
