package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSnapshot

enum class WatchTogetherEntryTarget {
    Lobby,
    Player,
}

fun watchTogetherEntryTarget(room: RoomSnapshot): WatchTogetherEntryTarget =
    if (
        !room.selectedContentId.isNullOrBlank() &&
        !(room.selfRole == MemberRole.Host && room.memberCount <= 1)
    ) {
        WatchTogetherEntryTarget.Player
    } else {
        WatchTogetherEntryTarget.Lobby
    }

fun resumableWatchTogetherRoom(room: RoomSnapshot?): RoomSnapshot? =
    room?.takeIf { it.roomId.isNotBlank() && it.phase != RoomPhase.Ended }
