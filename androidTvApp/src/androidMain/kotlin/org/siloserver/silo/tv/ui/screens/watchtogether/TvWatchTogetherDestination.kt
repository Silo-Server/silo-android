package org.siloserver.silo.tv.ui.screens.watchtogether

import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.tv.ui.navigation.TvRoute
import org.siloserver.silo.watchtogether.WatchTogetherEntryTarget
import org.siloserver.silo.watchtogether.watchTogetherEntryTarget

fun tvWatchTogetherDestination(room: RoomSnapshot): String =
    when (watchTogetherEntryTarget(room)) {
        WatchTogetherEntryTarget.Lobby ->
            TvRoute.WatchTogetherLobby(room.roomId).route
        WatchTogetherEntryTarget.Player ->
            TvRoute.Player(
                contentId = requireNotNull(room.selectedContentId),
                fileId = room.selectedFileId,
                roomId = room.roomId,
                resumePositionSeconds = room.anchorPositionSeconds
                    .takeIf { it.isFinite() && it > 0.0 },
            ).route
    }
