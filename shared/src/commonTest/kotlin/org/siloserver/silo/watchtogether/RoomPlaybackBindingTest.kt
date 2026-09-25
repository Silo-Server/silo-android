package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.TransportAction
import org.siloserver.silo.model.watchtogether.TransportCommand
import org.siloserver.silo.repository.RoomTransportAuthorization
import org.siloserver.silo.repository.ScheduledTransportCommand
import org.siloserver.silo.repository.WatchTogetherConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomPlaybackBindingTest {

    private val connection = WatchTogetherConnectionState(generation = 1, epoch = 1, writable = true)

    private fun playing(
        state: RoomPlaybackState = RoomPlaybackState.Playing,
        revision: Long = 3,
        role: MemberRole = MemberRole.Guest,
        selfReady: Boolean = false,
        selfBuffering: Boolean = false,
        ignoreWait: Boolean = false,
        paused: Boolean = false,
        anchor: Double = 100.0,
        canControl: Boolean = false,
    ) = RoomSnapshot(
        roomId = "room-1",
        phase = RoomPhase.Playing,
        playbackState = state,
        selectionRevision = revision,
        selfRole = role,
        isPaused = paused,
        anchorPositionSeconds = anchor,
        selfIgnoreWait = ignoreWait,
        selfCanControlTransport = canControl || role == MemberRole.Host,
        members = listOf(RoomMember(userId = "1", isSelf = true, isReady = selfReady, isBuffering = selfBuffering)),
    )

    private class Sent(val kind: String, val position: Double, val paused: Boolean, val commandId: String? = null, val ready: Boolean = false)

    private inner class FakeRoom : RoomPlaybackRoom {
        override val roomSnapshot = MutableStateFlow<RoomSnapshot?>(playing())
        override val connectionState = MutableStateFlow(connection)
        override val roomDeliveryEcho = MutableStateFlow<RoomDeliveryEcho?>(null)
        override val latestTransportCommand = MutableStateFlow<ScheduledTransportCommand?>(null)
        override val clock = MutableStateFlow(RoomClockEstimate(offsetMs = 0L, rttMs = 20L, generation = 0))
        val attaches = mutableListOf<String>()
        val sent = mutableListOf<Sent>()
        val requests = mutableListOf<Pair<String, Double?>>()
        var echoAttaches = true

        override suspend fun attachSession(sessionId: String): Boolean {
            attaches += sessionId
            if (echoAttaches) {
                val c = connectionState.value
                roomDeliveryEcho.value = RoomDeliveryEcho(c.generation, c.epoch, sessionId)
            }
            return true
        }
        override suspend fun stateReport(sessionId: String, positionSeconds: Double, isPaused: Boolean, commandId: String?, isReady: Boolean) =
            true.also { sent += Sent(if (isReady) "report_ready" else "report", positionSeconds, isPaused, commandId, isReady) }
        override suspend fun ready(sessionId: String, positionSeconds: Double, isPaused: Boolean, commandId: String?) =
            true.also { sent += Sent("ready", positionSeconds, isPaused, commandId) }
        override suspend fun buffering(sessionId: String, positionSeconds: Double, isPaused: Boolean) =
            true.also { sent += Sent("buffering", positionSeconds, isPaused) }
        override suspend fun checkClockContinuity() = clock.value
        override fun currentTransportAuthorization(): RoomTransportAuthorization? =
            roomSnapshot.value?.let { RoomTransportAuthorization("room-1", 1, 1, 1, it) }
        override suspend fun transportRequestForAuthorization(
            authorization: RoomTransportAuthorization,
            intent: RoomTransportIntent,
            action: String,
            positionSeconds: Double?,
            isPaused: Boolean,
        ) = true.also { requests += action to positionSeconds }

        fun kinds() = sent.map { it.kind }
    }

    private class FakePlayer : RoomPlayerPort {
        override val observations = MutableStateFlow(
            RoomPlayerObservation(
                sessionId = "ps-1",
                selectionRevision = 3,
                sourcePositionSeconds = 100.0,
                durationSeconds = 3_000.0,
                playWhenReady = true,
                isPlaying = true,
                state = RoomPlayerState.Ready,
            ),
        )
        val seeks = mutableListOf<Double>()
        val playing = mutableListOf<Boolean>()
        val rates = mutableListOf<Double?>()
        var buffered: (Double) -> Boolean = { true }

        override fun seekTo(sourceSeconds: Double) {
            seeks += sourceSeconds
        }
        override fun setPlaying(playing: Boolean) {
            this.playing += playing
        }
        override fun setCorrectionRate(rate: Double?) {
            rates += rate
        }
        override fun isBuffered(sourceSeconds: Double) = buffered(sourceSeconds)

        fun update(block: RoomPlayerObservation.() -> RoomPlayerObservation) {
            observations.value = observations.value.block()
        }
    }

    private fun TestScope.bind(room: FakeRoom, player: FakePlayer): RoomPlaybackBinding =
        RoomPlaybackBinding(
            room = room,
            player = player,
            parentScope = backgroundScope,
            monotonicNowMs = { testScheduler.currentTime },
            wallNowMs = { WALL_ORIGIN + testScheduler.currentTime },
        ).also {
            it.start()
            runCurrent()
        }

    private fun TestScope.command(
        room: FakeRoom,
        id: String,
        action: TransportAction,
        position: Double,
        state: RoomPlaybackState,
        executeInMs: Long = 0L,
        revision: Long = 3,
        sessionId: String = "",
    ) {
        room.latestTransportCommand.value = ScheduledTransportCommand(
            command = TransportCommand(
                commandId = id,
                sessionId = sessionId,
                selectionRevision = revision,
                action = action,
                positionSeconds = position,
                executeAt = "",
                playbackState = state,
            ),
            executeAtMs = WALL_ORIGIN + testScheduler.currentTime + executeInMs,
            connection = room.connectionState.value,
        )
    }

    // ---- attach and reports ------------------------------------------------------

    @Test
    fun `reports start only after the server echoes the attached session`() = runTest {
        val room = FakeRoom().apply { echoAttaches = false }
        val player = FakePlayer()
        bind(room, player)
        advanceTimeBy(3_000)
        assertEquals(listOf("ps-1"), room.attaches.distinct())
        assertTrue(room.sent.isEmpty())

        room.roomDeliveryEcho.value = RoomDeliveryEcho(1, 1, "ps-1")
        advanceTimeBy(300)
        assertEquals("report", room.sent.single().kind)
        assertEquals(100.0, room.sent.single().position)
    }

    @Test
    fun `a missing echo resends the attach`() = runTest {
        val room = FakeRoom().apply { echoAttaches = false }
        bind(room, FakePlayer())
        advanceTimeBy(4_600)
        assertTrue(room.attaches.size >= 2)
    }

    @Test
    fun `a new socket epoch attaches again`() = runTest {
        val room = FakeRoom()
        bind(room, FakePlayer())
        room.connectionState.value = connection.copy(epoch = 2)
        advanceTimeBy(600)
        assertEquals(2, room.attaches.size)
    }

    @Test
    fun `reports observed state every one and a half seconds`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        advanceTimeBy(1_000)
        player.update { copy(sourcePositionSeconds = 101.0, playWhenReady = false) }
        advanceTimeBy(1_000)

        val reports = room.sent.filter { it.kind == "report" }
        assertEquals(2, reports.size)
        assertEquals(101.0, reports.last().position)
        assertTrue(reports.last().paused)
    }

    @Test
    fun `no reports while stalled, seeking, or suspended`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        room.sent.clear()
        for (change in listOf<RoomPlayerObservation.() -> RoomPlayerObservation>(
            { copy(state = RoomPlayerState.Buffering) },
            { copy(state = RoomPlayerState.Ready, seekPending = true) },
            { copy(seekPending = false, suspended = true) },
        )) {
            player.update(change)
            advanceTimeBy(1_600)
            assertTrue(room.sent.none { it.kind == "report" }, "reported during $change")
            room.sent.clear()
        }
    }

    // ---- commands and readiness --------------------------------------------------------

    @Test
    fun `a waiting seek is applied at its execute time and acknowledged once landed`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        room.roomSnapshot.value = playing(state = RoomPlaybackState.Waiting)
        command(room, "cmd-1", TransportAction.Seek, 500.0, RoomPlaybackState.Waiting, executeInMs = 400)
        advanceTimeBy(300)
        assertTrue(player.seeks.isEmpty())

        advanceTimeBy(200)
        assertEquals(listOf(500.0), player.seeks)
        assertEquals(false, player.playing.last())

        // Still landing: no acknowledgement for the old position.
        player.update { copy(seekPending = true) }
        advanceTimeBy(600)
        assertTrue(room.sent.none { it.kind == "ready" })

        player.update { copy(seekPending = false, sourcePositionSeconds = 499.6, playWhenReady = false) }
        advanceTimeBy(300)
        val ready = room.sent.first { it.kind == "ready" }
        assertEquals("cmd-1", ready.commandId)
        assertEquals(499.6, ready.position)
        assertTrue(room.sent.any { it.kind == "report_ready" && it.commandId == "cmd-1" })
    }

    @Test
    fun `readiness retries until a fresh snapshot confirms it`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        // The snapshot current when the command arrives still says ready from an earlier cycle.
        room.roomSnapshot.value = playing(state = RoomPlaybackState.Waiting, selfReady = true)
        command(room, "cmd-2", TransportAction.Pause, 100.0, RoomPlaybackState.Waiting)
        player.update { copy(playWhenReady = false) }
        advanceTimeBy(1_600)
        assertTrue(room.sent.count { it.kind == "ready" } >= 3)

        // The server's acknowledgement arrives as a new snapshot.
        room.roomSnapshot.value = playing(state = RoomPlaybackState.Waiting, selfReady = true, anchor = 100.5)
        advanceTimeBy(300)
        room.sent.clear()
        advanceTimeBy(1_000)
        assertTrue(room.sent.none { it.kind == "ready" })
    }

    @Test
    fun `a second seek on the same session re-arms readiness`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        room.roomSnapshot.value = playing(state = RoomPlaybackState.Waiting)
        command(room, "cmd-a", TransportAction.Seek, 200.0, RoomPlaybackState.Waiting)
        player.update { copy(sourcePositionSeconds = 200.0) }
        advanceTimeBy(300)
        room.roomSnapshot.value = playing(state = RoomPlaybackState.Waiting, selfReady = true)
        advanceTimeBy(300)

        command(room, "cmd-b", TransportAction.Seek, 200.0, RoomPlaybackState.Waiting)
        advanceTimeBy(300)

        assertEquals(listOf("cmd-a", "cmd-b"), room.sent.filter { it.kind == "ready" }.map { it.commandId }.distinct())
    }

    @Test
    fun `a guest outside the seek tolerance does not acknowledge but a host within fifteen seconds does`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        room.roomSnapshot.value = playing(state = RoomPlaybackState.Waiting)
        command(room, "cmd-g", TransportAction.Seek, 300.0, RoomPlaybackState.Waiting)
        player.update { copy(sourcePositionSeconds = 294.0) }
        advanceTimeBy(1_000)
        assertTrue(room.sent.none { it.kind == "ready" })

        room.roomSnapshot.value = playing(state = RoomPlaybackState.Waiting, role = MemberRole.Host)
        advanceTimeBy(300)
        assertEquals("cmd-g", room.sent.first { it.kind == "ready" }.commandId)
    }

    @Test
    fun `a superseded delayed command never executes`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        command(room, "old", TransportAction.Seek, 50.0, RoomPlaybackState.Playing, executeInMs = 2_000)
        advanceTimeBy(100)
        command(room, "new", TransportAction.Seek, 70.0, RoomPlaybackState.Playing, executeInMs = 100)
        advanceTimeBy(3_000)
        assertEquals(listOf(70.0), player.seeks)
    }

    @Test
    fun `commands for another session, revision, or epoch are ignored`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        command(room, "c1", TransportAction.Seek, 10.0, RoomPlaybackState.Playing, sessionId = "someone-else")
        advanceTimeBy(100)
        command(room, "c2", TransportAction.Seek, 20.0, RoomPlaybackState.Playing, revision = 2)
        advanceTimeBy(100)
        room.latestTransportCommand.value = room.latestTransportCommand.value!!.copy(
            command = room.latestTransportCommand.value!!.command.copy(commandId = "c3", selectionRevision = 3),
            connection = connection.copy(epoch = 9),
        )
        advanceTimeBy(100)
        assertTrue(player.seeks.isEmpty())
    }

    @Test
    fun `a late play advances its target by the elapsed server time`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 90.0) }
        command(room, "late", TransportAction.Play, 100.0, RoomPlaybackState.Playing, executeInMs = -4_000)
        advanceTimeBy(100)
        assertEquals(104.0, player.seeks.single(), 0.2)
        assertEquals(true, player.playing.last())
    }

    // ---- corrections -----------------------------------------------------------------

    @Test
    fun `small unbuffered drift converges by rate and returns to exactly 1x`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer().apply { buffered = { false } }
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 99.0) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(1.125, player.rates.last()!!, 0.001)
        assertTrue(player.seeks.isEmpty())

        // 300 ms later the room is at about 100.3.
        player.update { copy(sourcePositionSeconds = 100.3) }
        advanceTimeBy(300)
        assertNull(player.rates.last())
    }

    @Test
    fun `an unbuffered pause correction never reloads media`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer().apply { buffered = { false } }
        bind(room, player)
        command(room, "p", TransportAction.Pause, 140.0, RoomPlaybackState.Paused)
        advanceTimeBy(100)
        assertTrue(player.seeks.isEmpty())
        assertEquals(false, player.playing.last())
    }

    @Test
    fun `unbuffered play corrections are paced by the reload budget`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer().apply { buffered = { false } }
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 50.0) }
        command(room, "r1", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(100)
        assertEquals(1, player.seeks.size)

        command(room, "r2", TransportAction.Play, 110.0, RoomPlaybackState.Playing)
        advanceTimeBy(100)
        assertEquals(1, player.seeks.size, "a second reload started before the first could land")

        // An explicit seek is never delayed by the budget.
        command(room, "s", TransportAction.Seek, 400.0, RoomPlaybackState.Playing)
        advanceTimeBy(100)
        assertEquals(400.0, player.seeks.last())
    }

    // ---- buffering and catching up ------------------------------------------------------

    @Test
    fun `a stall under two seconds stays local and a longer one is reported once`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(state = RoomPlayerState.Buffering) }
        advanceTimeBy(1_500)
        player.update { copy(state = RoomPlayerState.Ready) }
        advanceTimeBy(300)
        assertTrue(room.sent.none { it.kind == "buffering" })

        player.update { copy(state = RoomPlayerState.Buffering) }
        advanceTimeBy(5_000)
        assertEquals(1, room.sent.count { it.kind == "buffering" })
    }

    @Test
    fun `two sustained stalls within five minutes offer a lower quality`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        val binding = bind(room, player)
        repeat(2) {
            player.update { copy(state = RoomPlayerState.Buffering) }
            advanceTimeBy(2_300)
            player.update { copy(state = RoomPlayerState.Ready) }
            advanceTimeBy(300)
        }
        assertTrue(binding.offerLowerQuality.value)
        binding.onQualityChanged()
        assertFalse(binding.offerLowerQuality.value)
    }

    @Test
    fun `a viewer the room left behind acknowledges recovery until it is ready`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        val binding = bind(room, player)
        room.roomSnapshot.value = playing(ignoreWait = true)
        player.update { copy(state = RoomPlayerState.Buffering) }
        advanceTimeBy(600)
        assertTrue(binding.catchingUp.value)
        assertTrue(room.sent.none { it.kind == "ready" })

        player.update { copy(state = RoomPlayerState.Ready) }
        advanceTimeBy(1_100)
        assertTrue(room.sent.count { it.kind == "ready" } >= 2)

        room.roomSnapshot.value = playing(selfReady = true)
        advanceTimeBy(300)
        room.sent.clear()
        advanceTimeBy(1_000)
        assertTrue(room.sent.none { it.kind == "ready" })
        assertFalse(binding.catchingUp.value)
    }

    @Test
    fun `ending a local suspension re-attaches so the room resends its position`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(suspended = true, playWhenReady = false) }
        advanceTimeBy(600)
        player.update { copy(suspended = false) }
        advanceTimeBy(600)
        assertEquals(2, room.attaches.size)
    }

    // ---- user intents -----------------------------------------------------------------

    @Test
    fun `a guest seek is denied locally and never sent`() = runTest {
        val room = FakeRoom()
        val binding = bind(room, FakePlayer())
        val notices = mutableListOf<RoomPlaybackNotice>()
        backgroundScope.launch { binding.notices.toList(notices) }
        runCurrent()

        assertEquals(RoomTransportResult.Denied, binding.requestSeek(20.0))
        runCurrent()
        assertTrue(room.requests.isEmpty())
        assertEquals(listOf<RoomPlaybackNotice>(RoomPlaybackNotice.Denied(RoomTransportIntent.Seek)), notices)
    }

    @Test
    fun `a tap during a reconnect says so instead of dropping silently`() = runTest {
        val room = FakeRoom()
        val binding = bind(room, FakePlayer())
        room.roomSnapshot.value = playing(canControl = true)
        room.connectionState.value = connection.copy(writable = false)
        assertEquals(RoomTransportResult.Reconnecting, binding.requestPlayPause(pause = true))
        assertTrue(room.requests.isEmpty())
    }

    @Test
    fun `permitted requests go to the room and never touch the player`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        val binding = bind(room, player)
        room.roomSnapshot.value = playing(role = MemberRole.Host)
        assertEquals(RoomTransportResult.Sent, binding.requestSeek(250.0))
        assertEquals(RoomTransportResult.Sent, binding.requestPlayPause(pause = true))
        runCurrent()
        assertEquals(listOf<Pair<String, Double?>>("seek" to 250.0, "pause" to 100.0), room.requests)
        assertTrue(player.seeks.isEmpty())
        assertTrue(player.playing.isEmpty())
    }

    @Test
    fun `play from the end is not sent to the room`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        val binding = bind(room, player)
        room.roomSnapshot.value = playing(role = MemberRole.Host)
        player.update { copy(state = RoomPlayerState.Ended, playWhenReady = false) }
        assertEquals(RoomTransportResult.Ignored, binding.requestPlayPause(pause = false))
    }

    @Test
    fun `leaving the playing phase stops corrections at 1x`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer().apply { buffered = { false } }
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 99.0) }
        command(room, "d", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        room.roomSnapshot.value = playing().copy(phase = RoomPhase.Lobby)
        advanceTimeBy(300)
        assertNull(player.rates.last())
    }

    private companion object {
        const val WALL_ORIGIN = 1_790_000_000_000L
    }
}
