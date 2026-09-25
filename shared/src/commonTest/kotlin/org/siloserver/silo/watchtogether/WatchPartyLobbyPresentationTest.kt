package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchPartyLobbyPresentationTest {
    private val features = WatchPartyFeatures(
        stagedSelection = true, stopPlayback = true, selectionModeSwitch = true, lobbyReady = true, picker = true,
        memberState = true, maxMemberStateIds = 200, voteHostOverride = false, sourceFallback = true,
    )

    private val host = RoomMember(userId = "1", profileId = "a", displayName = "Ana", isHost = true, connected = true)
    private val guest = RoomMember(userId = "2", profileId = "b", displayName = "Ben", connected = true)

    private fun room(
        role: MemberRole,
        phase: RoomPhase = RoomPhase.Lobby,
        mode: RoomSelectionMode = RoomSelectionMode.HostPick,
        staged: Boolean = true,
        guestReady: Boolean = false,
        hostConnected: Boolean = true,
    ) = RoomSnapshot(
        roomId = "r",
        phase = phase,
        selfRole = role,
        selfCanManageRoom = role == MemberRole.Host,
        selectionMode = mode,
        selectedContentId = "movie:a".takeIf { staged },
        hostConnected = hostConnected,
        members = listOf(
            host.copy(isSelf = role == MemberRole.Host, connected = hostConnected),
            guest.copy(isSelf = role == MemberRole.Guest, lobbyReady = guestReady),
        ),
    )

    private fun suggestion(id: String, votes: Int) = Suggestion(
        id = id, roomId = "r", contentId = "movie:$id", contentType = "movie", title = id.uppercase(),
        voteCount = votes, createdAt = "2026-09-25T10:00:0${id.length}Z",
    )

    @Test
    fun `host pick lobby foregrounds choose, then start, and guests ready up`() {
        val none = emptyList<Suggestion>()
        assertEquals(WatchPartyPrimaryAction.ChooseTitle, watchPartyPrimaryAction(room(MemberRole.Host, staged = false), features, none))
        assertEquals(WatchPartyPrimaryAction.Start(null), watchPartyPrimaryAction(room(MemberRole.Host), features, none))
        assertEquals(
            WatchPartySecondaryAction.ChangeTitle,
            watchPartySecondaryAction(room(MemberRole.Host), WatchPartyPrimaryAction.Start(null)),
        )
        assertEquals(WatchPartyPrimaryAction.Ready(false), watchPartyPrimaryAction(room(MemberRole.Guest), features, none))
        assertEquals(WatchPartyPrimaryAction.Ready(true), watchPartyPrimaryAction(room(MemberRole.Guest, guestReady = true), features, none))
        // Ready is advisory, so guests can mark it before the host picks, as on Apple.
        assertEquals(WatchPartyPrimaryAction.Ready(false), watchPartyPrimaryAction(room(MemberRole.Guest, staged = false), features, none))
    }

    @Test
    fun `a voting room starts its leader and waits while nobody has voted`() {
        val voting = room(MemberRole.Host, mode = RoomSelectionMode.Vote, staged = false)
        val promotable = features.copy(voteHostOverride = true)
        assertEquals(WatchPartyPrimaryAction.WaitingForVotes, watchPartyPrimaryAction(voting, promotable, listOf(suggestion("a", 0))))
        assertEquals(
            WatchPartyPrimaryAction.Start("B"),
            watchPartyPrimaryAction(voting, promotable, listOf(suggestion("a", 1), suggestion("b", 2))),
        )
        // Starting promotes the leader, which servers without the promote route can't do.
        assertEquals(
            WatchPartyPrimaryAction.WaitingForVotes,
            watchPartyPrimaryAction(voting, features, listOf(suggestion("a", 1))),
        )
        assertEquals("Nobody has voted yet.", watchPartyLobbyHint(voting, listOf(suggestion("a", 0))))
        assertEquals("Suggest a title to get the vote going.", watchPartyLobbyHint(voting, emptyList()))
    }

    @Test
    fun `playing rooms offer return to playback unless the guest cannot see the title`() {
        assertEquals(
            WatchPartyPrimaryAction.ReturnToPlayback,
            watchPartyPrimaryAction(room(MemberRole.Guest, phase = RoomPhase.Playing), features, emptyList()),
        )
        assertEquals(
            WatchPartyPrimaryAction.None,
            watchPartyPrimaryAction(room(MemberRole.Guest, phase = RoomPhase.Playing), features, emptyList(), selectionUnavailable = true),
        )
    }

    @Test
    fun `seats and presence read like Apple`() {
        assertEquals(WatchPartySeatState.Ready, watchPartySeatState(host, RoomPhase.Lobby))
        assertEquals(WatchPartySeatState.NotReady, watchPartySeatState(guest, RoomPhase.Lobby))
        assertEquals(WatchPartySeatState.Away, watchPartySeatState(guest.copy(connected = false), RoomPhase.Lobby))
        assertEquals(WatchPartySeatState.Watching, watchPartySeatState(guest.copy(isReady = true), RoomPhase.Playing))
        assertEquals("1 of 2 ready", watchPartyPresenceSummary(listOf(host, guest), RoomPhase.Lobby))
        assertEquals("only you so far", watchPartyPresenceSummary(listOf(host), RoomPhase.Lobby))
        assertEquals(
            listOf("Ana", "Ben", "Cal"),
            watchPartySeatOrder(listOf(guest.copy(displayName = "Cal"), guest, host)).map { it.displayName },
        )
    }

    @Test
    fun `hints and banners name the people involved`() {
        assertEquals("Ben hasn't marked ready. You can still start.", watchPartyLobbyHint(room(MemberRole.Host), emptyList()))
        assertEquals("Everyone's ready.", watchPartyLobbyHint(room(MemberRole.Host, guestReady = true), emptyList()))
        assertEquals("Ana starts the movie when everyone's set.", watchPartyLobbyHint(room(MemberRole.Guest), emptyList()))
        assertEquals(
            "Ana lost connection. The party holds for two minutes while they come back.",
            watchPartyLobbyBanner(room(MemberRole.Guest, hostConnected = false), selectionUnavailable = false, reconnecting = false),
        )
        assertNull(watchPartyLobbyBanner(room(MemberRole.Host), selectionUnavailable = false, reconnecting = false))
        assertEquals("Waiting for\nAna", watchPartyLobbyHero(room(MemberRole.Guest, staged = false), emptyList()).title)
        assertEquals("Ana chose this", watchPartyLobbyHero(room(MemberRole.Guest), emptyList()).supporting)
    }
}
