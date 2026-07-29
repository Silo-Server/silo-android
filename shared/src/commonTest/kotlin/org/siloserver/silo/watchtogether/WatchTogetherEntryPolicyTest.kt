package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchTogetherEntryPolicyTest {
    @Test
    fun selectedGuestRoutesToPlayer() {
        val room = RoomSnapshot(
            roomId = "room-1",
            selectedContentId = "movie-1",
            selfRole = MemberRole.Guest,
            memberCount = 2,
        )
        assertEquals(WatchTogetherEntryTarget.Player, watchTogetherEntryTarget(room))
    }

    @Test
    fun emptyRoomAndSoloHostRouteToLobby() {
        assertEquals(
            WatchTogetherEntryTarget.Lobby,
            watchTogetherEntryTarget(RoomSnapshot(roomId = "room-1")),
        )
        assertEquals(
            WatchTogetherEntryTarget.Lobby,
            watchTogetherEntryTarget(
                RoomSnapshot(
                    roomId = "room-1",
                    selectedContentId = "movie-1",
                    selfRole = MemberRole.Host,
                    memberCount = 1,
                ),
            ),
        )
    }

    @Test
    fun onlyNonTerminalNonBlankRoomIsResumable() {
        assertNull(resumableWatchTogetherRoom(null))
        assertNull(resumableWatchTogetherRoom(RoomSnapshot(roomId = "")))
        assertNull(
            resumableWatchTogetherRoom(
                RoomSnapshot(roomId = "room-1", phase = RoomPhase.Ended),
            ),
        )
        assertEquals(
            "room-1",
            resumableWatchTogetherRoom(
                RoomSnapshot(roomId = "room-1", phase = RoomPhase.Lobby),
            )?.roomId,
        )
    }
}
