package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.notifications.WsTicketResponse
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.model.watchtogether.TransportAction
import org.siloserver.silo.model.watchtogether.WatchTogetherCapabilitiesV2
import org.siloserver.silo.model.watchtogether.WsStateReport
import org.siloserver.silo.model.watchtogether.readWatchPartyFixture
import org.siloserver.silo.network.RoomRealtimeEvent
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.apiv2.PlaybackCapabilitiesV2
import org.siloserver.silo.network.decodeRoomFrame
import org.siloserver.silo.network.validRoomTicket
import org.siloserver.silo.util.formatEpochMillisRfc3339
import org.siloserver.silo.util.parseRfc3339ToEpochMillis
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartyWireTest {

    private fun frame(name: String, receivedAtMs: Long? = null) =
        decodeRoomFrame(SiloJson, readWatchPartyFixture(name), receivedAtMs)

    // ---- socket frames -----------------------------------------------------------

    @Test
    fun `socket snapshot decodes numeric ids and catching-up state`() {
        val room = assertIs<RoomRealtimeEvent.SnapshotEvent>(frame("socket_snapshot.json")).room
        assertEquals(57, room.selectedFileId)
        assertEquals("12", room.members.first().userId)
        assertFalse(room.hostConnected)
        assertTrue(room.selfIgnoreWait)
        assertTrue(room.selfMember!!.isBuffering)
        assertTrue(room.selfCatchingUp)
    }

    @Test
    fun `catching up requires a playing or paused room`() {
        val room = assertIs<RoomRealtimeEvent.SnapshotEvent>(frame("socket_snapshot.json")).room
        assertFalse(room.copy(playbackState = RoomPlaybackState.Waiting).selfCatchingUp)
        assertFalse(room.copy(selfIgnoreWait = false, members = room.members.map { it.copy(isBuffering = false) }).selfCatchingUp)
    }

    @Test
    fun `transport command decodes its command id and schedule`() {
        val command = assertIs<RoomRealtimeEvent.TransportCommandEvent>(frame("socket_transport_command.json")).command
        assertEquals("cmd_5b2e", command.commandId)
        assertEquals(TransportAction.Seek, command.action)
        assertEquals(RoomPlaybackState.Waiting, command.playbackState)
        assertEquals(parseRfc3339ToEpochMillis("2026-09-24T18:11:02.250Z"), parseRfc3339ToEpochMillis(command.executeAt))
    }

    @Test
    fun `suggestion broadcast decodes numeric suggester ids`() {
        val list = assertIs<RoomRealtimeEvent.SuggestionsEvent>(frame("socket_suggestions_update.json")).suggestions
        assertEquals(listOf("31", "12"), list.map { it.suggesterUserId })
        assertTrue(list.none { it.votedByMe })
    }

    @Test
    fun `connection replaced and room closed are distinct terminal frames`() {
        val replaced = assertIs<RoomRealtimeEvent.ConnectionReplaced>(frame("socket_connection_replaced.json"))
        assertEquals("This profile joined the Watch Party on another device.", replaced.reason)
        assertEquals("host_left", assertIs<RoomRealtimeEvent.Closed>(frame("socket_room_closed.json")).reason)
    }

    @Test
    fun `pong carries the receipt time stamped at the socket`() {
        val pong = assertIs<RoomRealtimeEvent.Pong>(frame("socket_pong.json", receivedAtMs = 42L))
        assertEquals(42L, pong.clientReceivedMs)
        assertEquals("2026-09-24T18:00:00.240Z", pong.serverReceivedAt)
    }

    @Test
    fun `unknown frames are ignored and malformed known frames are reported`() {
        assertNull(decodeRoomFrame(SiloJson, """{"type":"reaction","emoji":"x"}"""))
        assertNull(decodeRoomFrame(SiloJson, "not json"))
        assertIs<RoomRealtimeEvent.Malformed>(decodeRoomFrame(SiloJson, """{"type":"snapshot","room":"nope"}"""))
        assertIs<RoomRealtimeEvent.Malformed>(decodeRoomFrame(SiloJson, """{"type":"snapshot","room":{"room_id":""}}"""))
        assertIs<RoomRealtimeEvent.Malformed>(decodeRoomFrame(SiloJson, """{"type":"transport_command"}"""))
        assertIs<RoomRealtimeEvent.Malformed>(decodeRoomFrame(SiloJson, """{"type":"suggestions_update","suggestions":[{}]}"""))
    }

    @Test
    fun `state reports carry a command only when they acknowledge readiness`() {
        fun keys(report: WsStateReport) =
            SiloJson.parseToJsonElement(SiloJson.encodeToString(WsStateReport.serializer(), report)).jsonObject.keys
        assertEquals(
            setOf("type", "session_id", "position_seconds", "is_paused"),
            keys(WsStateReport(sessionId = "ps", positionSeconds = 1.0, isPaused = false)),
        )
        assertEquals(
            setOf("type", "session_id", "position_seconds", "is_paused", "command_id", "is_ready"),
            keys(WsStateReport(sessionId = "ps", positionSeconds = 1.0, isPaused = false, commandId = "c", isReady = true)),
        )
    }

    @Test
    fun `room tickets must fit the server bounds and a header`() {
        val ok = WsTicketResponse(ticket = "abc_DEF-1.2", expiresIn = 30, maxConnectionSeconds = 300, protocol = "silo.room.v2")
        assertTrue(validRoomTicket(ok))
        assertTrue(validRoomTicket(ok.copy(expiresIn = 0)))
        assertFalse(validRoomTicket(ok.copy(expiresIn = 31)))
        assertFalse(validRoomTicket(ok.copy(maxConnectionSeconds = 0)))
        assertFalse(validRoomTicket(ok.copy(maxConnectionSeconds = 301)))
        assertFalse(validRoomTicket(ok.copy(protocol = "silo.events.v2")))
        assertFalse(validRoomTicket(ok.copy(ticket = "")))
        assertFalse(validRoomTicket(ok.copy(ticket = "a b")))
    }

    // ---- capabilities ------------------------------------------------------------

    private val roomCaps = SiloJson.decodeFromString(
        WatchTogetherCapabilitiesV2.serializer(),
        readWatchPartyFixture("capabilities.json"),
    ).copy(allowed = true)
    private val playbackCaps = PlaybackCapabilitiesV2(
        state = "available",
        allowed = true,
        protocolVersions = listOf(3),
        features = listOf(
            WatchPartyPlaybackFeatures.Coordinator,
            WatchPartyPlaybackFeatures.FixedMediaFile,
            WatchPartyPlaybackFeatures.SourceFallback,
        ),
    )

    @Test
    fun `a fully capable server makes every optional feature available`() {
        val features = assertIs<WatchPartyAvailability.Available>(watchPartyAvailability(roomCaps, playbackCaps)).features
        assertTrue(features.stagedSelection && features.stopPlayback && features.selectionModeSwitch)
        assertTrue(features.lobbyReady && features.picker && features.memberState && features.voteHostOverride)
        assertTrue(features.sourceFallback)
        assertEquals(200, features.maxMemberStateIds)
    }

    @Test
    fun `entry requires the v2 socket, replacement signal, coordinator and fixed file`() {
        fun missing(room: WatchTogetherCapabilitiesV2 = roomCaps, playback: PlaybackCapabilitiesV2 = playbackCaps) =
            assertIs<WatchPartyAvailability.Unsupported>(watchPartyAvailability(room, playback)).missing
        assertEquals(listOf("room_socket"), missing(room = roomCaps.copy(socketProtocol = "")))
        assertEquals(listOf("connection_replaced"), missing(room = roomCaps.copy(connectionReplaced = false)))
        assertEquals(listOf("rooms"), missing(room = roomCaps.copy(state = "not_configured")))
        assertEquals(
            listOf(WatchPartyPlaybackFeatures.Coordinator),
            missing(playback = playbackCaps.copy(features = listOf(WatchPartyPlaybackFeatures.FixedMediaFile))),
        )
        assertEquals(
            listOf(WatchPartyPlaybackFeatures.FixedMediaFile),
            missing(playback = playbackCaps.copy(features = listOf(WatchPartyPlaybackFeatures.Coordinator))),
        )
    }

    @Test
    fun `an account that may not use rooms is not allowed rather than unsupported`() {
        assertEquals(WatchPartyAvailability.NotAllowed, watchPartyAvailability(roomCaps.copy(allowed = false), playbackCaps))
        assertEquals(WatchPartyAvailability.NotAllowed, watchPartyAvailability(roomCaps, playbackCaps.copy(allowed = false)))
    }

    @Test
    fun `optional flags gate independently`() {
        val features = assertIs<WatchPartyAvailability.Available>(
            watchPartyAvailability(
                roomCaps.copy(stopPlayback = false, voteHostOverride = false, memberState = false, maxMemberStateIds = 0),
                playbackCaps.copy(features = playbackCaps.features - WatchPartyPlaybackFeatures.SourceFallback),
            ),
        ).features
        assertFalse(features.stopPlayback)
        assertFalse(features.voteHostOverride)
        assertFalse(features.memberState)
        assertEquals(0, features.maxMemberStateIds)
        assertFalse(features.sourceFallback)
        assertTrue(features.stagedSelection)
    }

    // ---- clock -------------------------------------------------------------------

    @Test
    fun `clock keeps the lowest round trip sample`() {
        val clock = RoomClockEstimator()
        // Offset 200, round trip 300 (queued).
        assertTrue(clock.record(0, 350, 350, 300, monotonicNowMs = 300))
        // Offset 200, round trip 40.
        assertTrue(clock.record(1_000, 1_220, 1_220, 1_040, monotonicNowMs = 1_040))
        assertEquals(200L, clock.estimate.offsetMs)
        assertEquals(40L, clock.estimate.rttMs)
    }

    @Test
    fun `clock rejects impossible or implausible samples`() {
        val clock = RoomClockEstimator()
        assertFalse(clock.record(100, 0, 0, 50, monotonicNowMs = 50))
        assertFalse(clock.record(0, 10, 5, 20, monotonicNowMs = 20))
        assertFalse(clock.record(0, 3_000, 3_000, 6_000, monotonicNowMs = 6_000))
        assertNull(clock.estimate.offsetMs)
    }

    @Test
    fun `a wall clock jump discards earlier samples`() {
        val clock = RoomClockEstimator()
        clock.record(0, 220, 220, 40, monotonicNowMs = 40)
        val generation = clock.estimate.generation

        assertTrue(clock.checkContinuity(wallNowMs = 60_000, monotonicNowMs = 1_000))

        assertNull(clock.estimate.offsetMs)
        assertTrue(clock.estimate.generation > generation)
        assertFalse(clock.checkContinuity(wallNowMs = 61_000, monotonicNowMs = 2_000))
    }

    // ---- proof, time, ranking -----------------------------------------------------

    @Test
    fun `room proof expiry is read from the token`() {
        val token = SiloJson.parseToJsonElement(readWatchPartyFixture("room_http.json")).jsonObject["room_access_token"]!!
        assertEquals(1_790_461_331_000L, roomProofExpiryMs(token.toString().trim('"')))
        assertNull(roomProofExpiryMs("jwt-room"))
        assertNull(roomProofExpiryMs("a.%%%.c"))
        assertEquals("RoomProof(token=<redacted>, expiresAtEpochMs=null)", RoomProof("secret", null).toString())
    }

    @Test
    fun `timestamps round trip through the formatter`() {
        for (ms in listOf(0L, 1_790_461_331_123L, 951_782_400_000L, -1L)) {
            assertEquals(ms, parseRfc3339ToEpochMillis(formatEpochMillisRfc3339(ms)))
        }
        assertEquals("2026-09-24T18:00:00.240Z", formatEpochMillisRfc3339(parseRfc3339ToEpochMillis("2026-09-24T18:00:00.240Z")!!))
    }

    private fun suggestion(id: String, votes: Int, createdAt: String) = Suggestion(
        id = id, roomId = "r", contentId = id, contentType = "movie", title = id, voteCount = votes, createdAt = createdAt,
    )

    @Test
    fun `suggestions rank by votes then age regardless of arrival order`() {
        val ranked = rankSuggestions(
            listOf(
                suggestion("new-tie", 2, "2026-09-24T18:00:00Z"),
                suggestion("unvoted", 0, "2026-09-24T17:00:00Z"),
                suggestion("old-tie", 2, "2026-09-24T17:30:00Z"),
            ),
        )
        assertEquals(listOf("old-tie", "new-tie", "unvoted"), ranked.map { it.id })
        assertEquals("old-tie", roomVoteWinner(ranked.reversed())?.id)
        assertNull(roomVoteWinner(listOf(suggestion("unvoted", 0, "2026-09-24T17:00:00Z"))))
    }
}
