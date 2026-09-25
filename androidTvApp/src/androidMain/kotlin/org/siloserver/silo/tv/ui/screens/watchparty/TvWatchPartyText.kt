package org.siloserver.silo.tv.ui.screens.watchparty

import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.repository.WatchPartyEndReason

/** Plain-language reason this device's Watch Party ended. */
internal fun tvWatchPartyEndedMessage(reason: String): String = when (reason) {
    WatchPartyEndReason.Replaced -> "This profile joined the Watch Party on another device."
    WatchPartyEndReason.ConnectionLost -> "Lost connection to the party."
    else -> "The Watch Party has ended."
}

/** A member's name for display; the server may omit it. */
internal fun tvWatchPartyMemberName(member: RoomMember): String =
    member.displayName.trim().ifBlank { "Someone" }

/** "Ana", "Ana and Ben", "Ana, Ben, and Cy", "Ana, Ben, and 3 others". */
internal fun tvWatchPartyNameList(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    3 -> "${names[0]}, ${names[1]}, and ${names[2]}"
    else -> "${names[0]}, ${names[1]}, and ${names.size - 2} others"
}

/**
 * The other members the room is waiting for, or null when the room is not
 * waiting. An empty list means the room waits only for this viewer.
 */
internal fun tvWatchPartyWaitingFor(room: RoomSnapshot?): List<String>? {
    if (room == null || room.phase != RoomPhase.Playing || room.playbackState != RoomPlaybackState.Waiting) {
        return null
    }
    return room.members.filter { it.isSyncing && !it.isSelf }.map(::tvWatchPartyMemberName)
}

/**
 * Members the room left behind between two snapshots of the same selection:
 * the room was waiting while they synced, and it now plays again while they
 * are still not ready. This viewer is never listed; it gets the catching-up
 * notice instead. Members who left the room are not listed either.
 */
internal fun tvWatchPartyLeftBehind(previous: RoomSnapshot?, next: RoomSnapshot?): List<String> {
    if (previous == null || next == null) return emptyList()
    if (previous.roomId != next.roomId || previous.selectionRevision != next.selectionRevision) return emptyList()
    if (previous.phase != RoomPhase.Playing || previous.playbackState != RoomPlaybackState.Waiting) return emptyList()
    if (next.phase != RoomPhase.Playing || next.playbackState != RoomPlaybackState.Playing) return emptyList()
    return previous.members
        .filter { it.isSyncing && !it.isSelf }
        .mapNotNull { waitedFor ->
            next.members.firstOrNull { it.isSameMember(waitedFor) }
                ?.takeIf { !it.isReady }
                ?.let(::tvWatchPartyMemberName)
        }
}

private fun RoomMember.isSameMember(other: RoomMember): Boolean =
    if (userId.isNotBlank() || other.userId.isNotBlank()) {
        userId == other.userId && profileId == other.profileId
    } else {
        displayName == other.displayName
    }

/** A room code split in two for reading aloud and typing: "ABCD 2345". */
internal fun tvWatchPartyDisplayCode(code: String): String =
    if (code.length == 8) "${code.substring(0, 4)} ${code.substring(4)}" else code

internal const val TV_WATCH_PARTY_HOST_LEAVE_NOTE =
    "The party ends two minutes after you leave unless you rejoin."

internal const val TV_WATCH_PARTY_HOST_AWAY_NOTE =
    "The party ends in two minutes unless the host returns."
