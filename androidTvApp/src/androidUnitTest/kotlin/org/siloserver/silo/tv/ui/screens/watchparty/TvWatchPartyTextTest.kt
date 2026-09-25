package org.siloserver.silo.tv.ui.screens.watchparty

import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvWatchPartyTextTest {
    private val self = RoomMember(userId = "1", profileId = "a", displayName = "Me", isSelf = true, connected = true)
    private val ana = RoomMember(userId = "2", profileId = "b", displayName = "Ana", connected = true)
    private val ben = RoomMember(userId = "3", profileId = "c", displayName = "Ben", connected = true)

    private fun room(
        state: RoomPlaybackState,
        members: List<RoomMember>,
        revision: Long = 4L,
        phase: RoomPhase = RoomPhase.Playing,
    ) = RoomSnapshot(
        roomId = "room",
        phase = phase,
        playbackState = state,
        selectionRevision = revision,
        members = members,
    )

    @Test
    fun `members still not ready when the room plays again were left behind`() {
        val waiting = room(RoomPlaybackState.Waiting, listOf(self, ana.copy(isSyncing = true), ben.copy(isSyncing = true)))
        val playing = room(RoomPlaybackState.Playing, listOf(self, ana, ben.copy(isReady = true)))
        assertEquals(listOf("Ana"), tvWatchPartyLeftBehind(waiting, playing))
    }

    @Test
    fun `this viewer is never listed as left behind`() {
        val waiting = room(RoomPlaybackState.Waiting, listOf(self.copy(isSyncing = true), ana))
        val playing = room(RoomPlaybackState.Playing, listOf(self, ana))
        assertTrue(tvWatchPartyLeftBehind(waiting, playing).isEmpty())
    }

    @Test
    fun `a new selection, a room that is still waiting, or a member who left is not left behind`() {
        val waiting = room(RoomPlaybackState.Waiting, listOf(self, ana.copy(isSyncing = true)))
        assertTrue(tvWatchPartyLeftBehind(waiting, room(RoomPlaybackState.Playing, listOf(self, ana), revision = 5L)).isEmpty())
        assertTrue(tvWatchPartyLeftBehind(waiting, room(RoomPlaybackState.Waiting, listOf(self, ana))).isEmpty())
        assertTrue(tvWatchPartyLeftBehind(waiting, room(RoomPlaybackState.Playing, listOf(self))).isEmpty())
        assertTrue(tvWatchPartyLeftBehind(null, room(RoomPlaybackState.Playing, listOf(self, ana))).isEmpty())
        assertTrue(
            tvWatchPartyLeftBehind(
                waiting,
                room(RoomPlaybackState.Idle, listOf(self, ana), phase = RoomPhase.Lobby),
            ).isEmpty(),
        )
    }

    @Test
    fun `the waiting overlay names other syncing members only while the room waits`() {
        assertEquals(
            listOf("Ana"),
            tvWatchPartyWaitingFor(room(RoomPlaybackState.Waiting, listOf(self.copy(isSyncing = true), ana.copy(isSyncing = true), ben))),
        )
        assertEquals(emptyList(), tvWatchPartyWaitingFor(room(RoomPlaybackState.Waiting, listOf(self.copy(isSyncing = true), ana))))
        assertNull(tvWatchPartyWaitingFor(room(RoomPlaybackState.Playing, listOf(self, ana.copy(isSyncing = true)))))
    }

    @Test
    fun `name lists read naturally`() {
        assertEquals("", tvWatchPartyNameList(emptyList()))
        assertEquals("Ana", tvWatchPartyNameList(listOf("Ana")))
        assertEquals("Ana and Ben", tvWatchPartyNameList(listOf("Ana", "Ben")))
        assertEquals("Ana, Ben, and Cy", tvWatchPartyNameList(listOf("Ana", "Ben", "Cy")))
        assertEquals("Ana, Ben, and 3 others", tvWatchPartyNameList(listOf("Ana", "Ben", "Cy", "Di", "Ed")))
    }

    @Test
    fun `an ended party explains itself`() {
        assertEquals("The Watch Party has ended.", tvWatchPartyEndedMessage("host_left"))
        assertEquals("The Watch Party has ended.", tvWatchPartyEndedMessage("not_found"))
        assertEquals("The Watch Party has ended.", tvWatchPartyEndedMessage("ended"))
        assertEquals(
            "This profile joined the Watch Party on another device.",
            tvWatchPartyEndedMessage("connection_replaced"),
        )
        assertEquals("Lost connection to the party.", tvWatchPartyEndedMessage("connection_lost"))
    }
}
