package org.siloserver.silo.tv.ui.screens.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.tv.ui.navigation.TvRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class TvWatchTogetherDestinationTest {
    @Test
    fun emptyAndSoloHostRoomsUseLobby() {
        assertEquals(
            TvRoute.WatchTogetherLobby("room-1").route,
            tvWatchTogetherDestination(RoomSnapshot(roomId = "room-1")),
        )
        assertEquals(
            TvRoute.WatchTogetherLobby("room-1").route,
            tvWatchTogetherDestination(
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
    fun selectedJoinedRoomUsesSyncedPlayer() {
        val room = RoomSnapshot(
            roomId = "room-1",
            selectedContentId = "movie-1",
            selectedFileId = 7,
            selfRole = MemberRole.Guest,
            memberCount = 2,
            anchorPositionSeconds = 12.5,
        )
        assertEquals(
            TvRoute.Player(
                contentId = "movie-1",
                fileId = 7,
                roomId = "room-1",
                resumePositionSeconds = 12.5,
            ).route,
            tvWatchTogetherDestination(room),
        )
    }
}
