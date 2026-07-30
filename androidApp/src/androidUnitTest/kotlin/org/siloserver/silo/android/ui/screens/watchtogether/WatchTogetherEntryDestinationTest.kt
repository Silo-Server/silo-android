package org.siloserver.silo.android.ui.screens.watchtogether

import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.MemberRole
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Asserts on real Route.route strings, which call android.net.Uri.encode —
// Robolectric provides the real Android impl under plain JVM unit tests.
// Pinned to SDK 34 (the project targetSdk 36 is newer than this Robolectric
// release ships an emulated runtime for).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WatchTogetherEntryDestinationTest {

    private fun snapshot(
        roomId: String = "room-1",
        selectedContentId: String? = null,
        selectedFileId: Int? = null,
    ) = RoomSnapshot(
        roomId = roomId,
        phase = RoomPhase.Lobby,
        playbackState = RoomPlaybackState.Idle,
        selectionMode = RoomSelectionMode.HostPick,
        selectionRevision = 0L,
        selectedContentId = selectedContentId,
        selectedFileId = selectedFileId,
    )

    @Test
    fun host_with_selection_goes_to_player_with_roomId() {
        val dest = watchTogetherDestination(snapshot(selectedContentId = "c1", selectedFileId = 7))
        assertEquals(Route.Player(contentId = "c1", fileId = 7, roomId = "room-1").route, dest)
    }

    @Test
    fun no_selection_goes_to_lobby() {
        val dest = watchTogetherDestination(snapshot(selectedContentId = null))
        assertEquals(Route.WatchTogetherLobby(roomId = "room-1").route, dest)
    }

    @Test
    fun selection_set_but_no_fileId_still_routes_to_player() {
        val dest = watchTogetherDestination(snapshot(selectedContentId = "c2", selectedFileId = null))
        assertEquals(Route.Player(contentId = "c2", fileId = null, roomId = "room-1").route, dest)
    }

    @Test
    fun host_alone_with_selection_stays_in_lobby_to_share_invite() {
        val dest = watchTogetherDestination(
            snapshot(selectedContentId = "c1").copy(
                selfRole = MemberRole.Host,
                memberCount = 1,
            ),
        )
        assertEquals(Route.WatchTogetherLobby(roomId = "room-1").route, dest)
    }
}
