package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartyEntryRulesTest {
    private val features = WatchPartyFeatures(
        stagedSelection = true, stopPlayback = true, selectionModeSwitch = true, lobbyReady = true, picker = true,
        memberState = true, maxMemberStateIds = 200, voteHostOverride = true, sourceFallback = true,
    )

    private fun room(
        phase: RoomPhase = RoomPhase.Lobby,
        role: MemberRole = MemberRole.Guest,
        mode: RoomSelectionMode = RoomSelectionMode.HostPick,
        staged: Boolean = false,
    ) = RoomSnapshot(
        roomId = "r",
        phase = phase,
        selfRole = role,
        selfCanManageRoom = role == MemberRole.Host,
        selectionMode = mode,
        selectedContentId = "movie:a".takeIf { staged },
        members = listOf(RoomMember(userId = "7", profileId = "p", isSelf = true, isHost = role == MemberRole.Host, connected = true)),
    )

    @Test
    fun `the phase alone decides lobby or player`() {
        assertEquals(WatchPartyDestination.Lobby("r"), watchPartyDestination(room(staged = true)))
        // A host alone in a playing room plays.
        assertEquals(WatchPartyDestination.Player("r"), watchPartyDestination(room(phase = RoomPhase.Playing, role = MemberRole.Host)))
        assertNull(watchPartyDestination(room(phase = RoomPhase.Ended)))
    }

    @Test
    fun `guests can suggest and mark ready but not manage`() {
        val e = watchPartyEligibility(room(staged = true), features, busy = false, personalVotesKnown = true)
        assertTrue(e.canSuggest && e.canLobbyReady)
        assertFalse(e.canStage || e.canStart || e.canSwitchMode || e.canSetPolicy || e.canEnd || e.canQueue)
    }

    @Test
    fun `host start needs a staged item and lobby ready is advisory for guests only`() {
        assertFalse(watchPartyEligibility(room(role = MemberRole.Host), features, false, true).canStart)
        val staged = watchPartyEligibility(room(role = MemberRole.Host, staged = true), features, false, true)
        assertTrue(staged.canStart)
        assertFalse(staged.canLobbyReady)
    }

    @Test
    fun `an action in flight disables the others`() {
        val e = watchPartyEligibility(room(role = MemberRole.Host, staged = true), features, busy = true, personalVotesKnown = true)
        assertFalse(e.canStart || e.canStage || e.canSuggest)
        assertTrue(e.busy)
    }

    @Test
    fun `votes wait for personal vote state and promotion needs the override capability`() {
        val vote = room(role = MemberRole.Host, mode = RoomSelectionMode.Vote)
        assertFalse(watchPartyEligibility(vote, features, false, personalVotesKnown = false).canVote)
        assertTrue(watchPartyEligibility(vote, features, false, personalVotesKnown = true).canVote)
        assertFalse(watchPartyEligibility(vote, features.copy(voteHostOverride = false), false, true).canPromote)
    }

    @Test
    fun `missing capabilities hide their controls`() {
        val host = room(role = MemberRole.Host, staged = true)
        val e = watchPartyEligibility(host, features.copy(stagedSelection = false, selectionModeSwitch = false), false, true)
        assertFalse(e.canStage || e.canStart || e.canSwitchMode || e.canQueue)
        assertFalse(watchPartyEligibility(room(phase = RoomPhase.Playing, role = MemberRole.Host), features.copy(stopPlayback = false), false, true).canStop)
    }

    @Test
    fun `hosting is offered unless the server is known not to host`() {
        assertTrue(watchPartyHostingOffered(WatchPartyAvailability.Available(features)))
        assertFalse(watchPartyHostingOffered(WatchPartyAvailability.Available(features.copy(stagedSelection = false))))
        assertFalse(watchPartyHostingOffered(WatchPartyAvailability.NotAllowed))
        // Unknown yet: the hub explains whatever the probe finds.
        assertTrue(watchPartyHostingOffered(null))
    }

    @Test
    fun `unknown roles grant nothing`() {
        val e = watchPartyEligibility(room(role = MemberRole.Unknown), features, false, true)
        assertFalse(e.canSuggest || e.canLobbyReady || e.canVote)
    }

    @Test
    fun `suggestions are removable by the host or the matching suggester only`() {
        val mine = Suggestion(id = "s", roomId = "r", suggesterUserId = "7", suggesterProfileId = "p", contentId = "c", contentType = "movie", title = "t", createdAt = "")
        assertTrue(canRemoveSuggestion(room(), mine))
        assertFalse(canRemoveSuggestion(room(), mine.copy(suggesterProfileId = "other")))
        assertTrue(canRemoveSuggestion(room(role = MemberRole.Host), mine.copy(suggesterUserId = "9")))
    }

    @Test
    fun `lobby readiness counts connected guests`() {
        val r = room(role = MemberRole.Host).copy(
            members = listOf(
                RoomMember(userId = "1", isHost = true, connected = true, lobbyReady = true),
                RoomMember(userId = "2", connected = true, lobbyReady = true),
                RoomMember(userId = "3", connected = true),
                RoomMember(userId = "4", connected = false, lobbyReady = true),
            ),
        )
        assertEquals(LobbyReadiness(ready = 1, guests = 2), lobbyReadiness(r))
    }

    // ---- invitations ---------------------------------------------------------------

    @Test
    fun `codes normalize case, spaces, and dashes`() {
        assertEquals("K7PQ2M4X", normalizeWatchPartyCode(" k7pq-2m4x "))
        assertNull(normalizeWatchPartyCode("K7PQ2M4"))
        assertNull(normalizeWatchPartyCode("K7PQ2M4O"))
    }

    @Test
    fun `web invitation links keep the base path`() {
        assertEquals(
            WatchPartyInvite.Link("https://media.example/silo", "Zq8vN3kR5tW1yB6cD9fG2hJ4"),
            parseWatchPartyInvite("https://media.example/silo/rooms/join?token=Zq8vN3kR5tW1yB6cD9fG2hJ4"),
        )
        assertEquals(
            WatchPartyInvite.Link("http://192.168.1.4:8096", "t"),
            parseWatchPartyInvite("http://192.168.1.4:8096/rooms/join?token=t"),
        )
    }

    @Test
    fun `app links match the Apple format and reject bad shapes`() {
        assertEquals(
            WatchPartyInvite.Link("https://media.example/silo", "tok"),
            parseWatchPartyInvite("silo://watch-party?server=https%3A%2F%2Fmedia.example%2Fsilo&token=tok"),
        )
        assertEquals(
            WatchPartyInvite.Link("https://media.example", "tok"),
            parseWatchPartyInvite("SILO://watch-party?server=https://media.example&token=tok"),
        )
        for (bad in listOf(
            "silo://watch-party?server=https://a&token=t&token=u",
            "silo://watch-party?token=t",
            "silo://watch-party?server=ftp://a&token=t",
            "silo://watch-party?server=https://user@a&token=t",
            "silo://invite?server=https://a&token=t",
            "silo://watch-party?server=https://a&token=",
            "https://a/rooms/join?code=ABCD1234",
            "https://a/rooms/join",
            "hello",
        )) {
            assertNull(parseWatchPartyInvite(bad), bad)
        }
    }

    @Test
    fun `the invitation link resolves against the server base path`() {
        assertEquals(
            "https://media.example/silo/rooms/join?token=t",
            watchPartyInviteUrl("https://media.example/silo/", "/rooms/join?token=t"),
        )
        assertNull(watchPartyInviteUrl("https://a", "https://evil/rooms/join?token=t"))
        assertEquals("https://a/rooms/join?token=t", parseWatchPartyInvite("https://a/rooms/join?token=t")?.let { "https://a/rooms/join?token=t" })
    }

    @Test
    fun `error text is plain and specific`() {
        assertEquals("Only the host can do that.", watchPartyErrorMessage(ApiResult.Error(403, "permission_denied", ""), "x"))
        assertEquals(
            "Couldn't reach the server. Check your connection and try again.",
            watchPartyErrorMessage(ApiResult.NetworkError(RuntimeException()), "x"),
        )
        assertEquals("fallback", watchPartyErrorMessage(ApiResult.Error(500, "internal", ""), "fallback"))
    }
}
