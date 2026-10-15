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
    fun `a guest acknowledges a waiting seek within five seconds and a host within fifteen`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        room.roomSnapshot.value = playing(state = RoomPlaybackState.Waiting)
        command(room, "cmd-g", TransportAction.Seek, 300.0, RoomPlaybackState.Waiting)
        player.update { copy(sourcePositionSeconds = 294.9) }
        advanceTimeBy(1_000)
        assertTrue(room.sent.none { it.kind == "ready" }, "a guest 5.1 s off acknowledged")

        // A rebuilt stream that lands 4.9 s short still acknowledges, naming the seek.
        player.update { copy(sourcePositionSeconds = 295.1) }
        advanceTimeBy(600)
        assertEquals("cmd-g", room.sent.first { it.kind == "ready" }.commandId)
        assertTrue(room.sent.any { it.kind == "report_ready" && it.commandId == "cmd-g" })

        // Beyond the guest gate only the host acknowledges.
        room.sent.clear()
        command(room, "cmd-h", TransportAction.Seek, 400.0, RoomPlaybackState.Waiting)
        player.update { copy(sourcePositionSeconds = 386.0) }
        advanceTimeBy(1_000)
        assertTrue(room.sent.none { it.kind == "ready" })

        room.roomSnapshot.value = playing(state = RoomPlaybackState.Waiting, role = MemberRole.Host)
        advanceTimeBy(300)
        assertEquals("cmd-h", room.sent.first { it.kind == "ready" }.commandId)
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
    fun `a waiting command replayed after reconnect executes on the new socket`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        room.roomSnapshot.value = playing(state = RoomPlaybackState.Waiting)
        command(room, "replayed", TransportAction.Seek, 250.0, RoomPlaybackState.Waiting, executeInMs = 400)
        advanceTimeBy(100)
        room.connectionState.value = connection.copy(epoch = 2)
        advanceTimeBy(500)
        assertTrue(player.seeks.isEmpty())

        command(room, "replayed", TransportAction.Seek, 250.0, RoomPlaybackState.Waiting)
        advanceTimeBy(100)
        assertEquals(listOf(250.0), player.seeks)
        player.update { copy(sourcePositionSeconds = 250.0, playWhenReady = false) }
        advanceTimeBy(600)
        assertTrue(room.sent.any { it.kind == "ready" && it.commandId == "replayed" })
    }

    @Test
    fun `a command waits for clock synchronization without using device clock skew`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        val binding = bind(room, player)
        val notices = mutableListOf<RoomPlaybackNotice>()
        backgroundScope.launch { binding.notices.toList(notices) }
        runCurrent()
        room.clock.value = RoomClockEstimate(offsetMs = null, rttMs = null, generation = 1)
        command(room, "no-clock", TransportAction.Play, 200.0, RoomPlaybackState.Playing, executeInMs = -60_000)
        advanceTimeBy(4_000)
        assertTrue(player.seeks.isEmpty(), "a command without a clock sample must not seek using the device time")
        assertTrue(player.playing.none { it }, "playback must wait for a clock sample")
        assertEquals("no-clock", binding.debug.value.pendingCommandId)
        assertEquals(listOf<RoomPlaybackNotice>(RoomPlaybackNotice.ClockUnavailable), notices)

        room.clock.value = RoomClockEstimate(offsetMs = -60_000, rttMs = 20, generation = 1)
        advanceTimeBy(300)
        assertEquals(204.0, player.seeks.single(), 0.4)
        assertEquals(true, player.playing.last())
    }

    @Test
    fun `a stale socket command cannot suppress the current socket replay`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        room.connectionState.value = connection.copy(epoch = 2)
        command(room, "same-id", TransportAction.Seek, 250.0, RoomPlaybackState.Playing)
        room.latestTransportCommand.value = room.latestTransportCommand.value!!.copy(connection = connection)
        advanceTimeBy(100)
        assertTrue(player.seeks.isEmpty())

        command(room, "same-id", TransportAction.Seek, 250.0, RoomPlaybackState.Playing)
        advanceTimeBy(100)
        assertEquals(listOf(250.0), player.seeks)
    }

    @Test
    fun `clock invalidation during a scheduled delay holds the command until synchronization returns`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        command(room, "clock-reset", TransportAction.Play, 200.0, RoomPlaybackState.Playing, executeInMs = 400)
        advanceTimeBy(100)
        room.clock.value = RoomClockEstimate(offsetMs = null, rttMs = null, generation = 1)
        advanceTimeBy(4_000)
        assertTrue(player.seeks.isEmpty())
        assertTrue(player.playing.none { it })

        room.clock.value = RoomClockEstimate(offsetMs = 0, rttMs = 20, generation = 1)
        advanceTimeBy(300)
        assertEquals(204.0, player.seeks.single(), 0.4)
        assertEquals(true, player.playing.last())
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

    /**
     * Keep the playing member [drift] seconds behind a room that started at
     * 100 s at time zero, publishing the position every 50 ms for [forMs].
     */
    private fun TestScope.follow(player: FakePlayer, drift: Double, forMs: Long) {
        repeat((forMs / 50).toInt()) {
            player.update { copy(sourcePositionSeconds = 100.0 + testScheduler.currentTime / 1000.0 - drift) }
            advanceTimeBy(50)
        }
    }

    @Test
    fun `a playing member's drift is nudged without a seek, even when buffered, and settles at exactly 1x`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 99.0) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(1.04, player.rates.last()!!, 0.0001)
        assertTrue(player.seeks.isEmpty())

        follow(player, drift = 0.0, forMs = 1_500)
        assertNull(player.rates.last())
        assertTrue(player.seeks.isEmpty())
    }

    @Test
    fun `the drift loop starts a nudge beyond the deadband and holds it until within the settle band`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        command(room, "play", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)

        follow(player, drift = 0.3, forMs = 1_500)
        assertTrue(player.rates.isEmpty(), "nudged inside the deadband: ${player.rates}")

        follow(player, drift = 0.6, forMs = 600)
        assertEquals(1.025, player.rates.last()!!, 0.0001)

        // Back inside the deadband but outside the settle band: keep nudging.
        follow(player, drift = 0.25, forMs = 600)
        assertEquals(1.01, player.rates.last()!!, 0.0001)

        follow(player, drift = 0.0, forMs = 600)
        assertNull(player.rates.last())
        assertTrue(player.seeks.isEmpty(), "the loop never seeks")
    }

    @Test
    fun `the drift loop never seeks a large drift and waits a second after each command`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        command(room, "play", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        follow(player, drift = 9.0, forMs = 900)
        assertTrue(player.rates.isEmpty(), "the loop ran before the command settled")

        follow(player, drift = 9.0, forMs = 600)
        assertEquals(1.04, player.rates.last()!!, 0.0001)
        assertTrue(player.seeks.isEmpty())
    }

    @Test
    fun `a received command changes nothing until it executes`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 99.0) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(1.04, player.rates.last()!!, 0.0001)
        val playCalls = player.playing.size

        command(room, "later", TransportAction.Pause, 101.0, RoomPlaybackState.Paused, executeInMs = 1_500)
        follow(player, drift = 0.0, forMs = 1_400)
        assertEquals(1.04, player.rates.last()!!, 0.0001)
        assertEquals(playCalls, player.playing.size)

        advanceTimeBy(200)
        assertNull(player.rates.last())
        assertEquals(false, player.playing.last())
    }

    @Test
    fun `the drift loop returns to 1x when local playback stops and never resumes it`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 99.0) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        val playCalls = player.playing.size

        player.update { copy(isPlaying = false, state = RoomPlayerState.Buffering) }
        advanceTimeBy(300)
        assertNull(player.rates.last())
        assertEquals(playCalls, player.playing.size)
    }

    @Test
    fun `a pause seeks a large drift even when it must reload, but leaves a small unbuffered one`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer().apply { buffered = { false } }
        bind(room, player)
        command(room, "p1", TransportAction.Pause, 103.0, RoomPlaybackState.Paused)
        advanceTimeBy(100)
        assertTrue(player.seeks.isEmpty())
        assertEquals(false, player.playing.last())

        command(room, "p2", TransportAction.Pause, 140.0, RoomPlaybackState.Paused)
        advanceTimeBy(100)
        assertEquals(listOf(140.0), player.seeks)
    }

    @Test
    fun `a play from rest seeks a reachable one second gap and plays at exactly 1x`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(isPlaying = false, playWhenReady = false, sourcePositionSeconds = 98.5) }
        command(room, "play", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(100.0, player.seeks.single(), 0.1)
        assertEquals(true, player.playing.last())
        assertTrue(player.rates.all { it == null })
    }

    @Test
    fun `a playing sample is projected forward by its age before deciding`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        advanceTimeBy(1_000)
        // Read 800 ms ago at 99.5: the player is at about 100.3 now, inside the deadband.
        player.update { copy(sourcePositionSeconds = 99.5, sampledAtMs = testScheduler.currentTime - 800) }
        command(room, "play", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertTrue(player.rates.isEmpty(), "nudged from a stale position: ${player.rates}")
        assertTrue(player.seeks.isEmpty())
    }

    @Test
    fun `a held reload keeps the drift loop from nudging`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer().apply { buffered = { false } }
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 50.0) }
        command(room, "r1", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(100)
        command(room, "r2", TransportAction.Play, 110.0, RoomPlaybackState.Playing)
        advanceTimeBy(3_000)
        assertEquals(1, player.seeks.size)
        assertTrue(player.rates.isEmpty(), "nudged while a reload was held: ${player.rates}")
    }

    @Test
    fun `the drift loop stays frozen while a command is pending, even after the snapshot flips`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 99.0) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(1.04, player.rates.last()!!, 0.0001)

        command(room, "pause", TransportAction.Pause, 101.5, RoomPlaybackState.Paused, executeInMs = 1_500)
        room.roomSnapshot.value = playing(state = RoomPlaybackState.Paused, paused = true)
        advanceTimeBy(1_000)
        assertEquals(1.04, player.rates.last()!!, 0.0001)

        advanceTimeBy(600)
        assertNull(player.rates.last())
        assertEquals(false, player.playing.last())
    }

    @Test
    fun `a reload that never lands stops blocking the drift loop after thirty seconds`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer().apply { buffered = { false } }
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 50.0) }
        command(room, "r1", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(100)
        assertEquals(1, player.seeks.size)

        advanceTimeBy(29_000)
        assertTrue(player.rates.isEmpty())
        advanceTimeBy(2_000)
        assertEquals(1.04, player.rates.last()!!, 0.0001)

        // The expired reload adds no backoff: the next large correction reloads at once.
        command(room, "r2", TransportAction.Play, 140.0, RoomPlaybackState.Playing)
        advanceTimeBy(100)
        assertEquals(2, player.seeks.size)
    }

    @Test
    fun `rate support is not judged while a command is pending`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 99.0, playbackSpeed = 1.0) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(1.04, player.rates.last()!!, 0.0001)

        // The engine still says 1x past the confirmation window, but a
        // command is scheduled: the rate stands until it executes.
        command(room, "later", TransportAction.Play, 101.5, RoomPlaybackState.Playing, executeInMs = 1_500)
        advanceTimeBy(1_400)
        assertEquals(1.04, player.rates.last()!!, 0.0001)
    }

    @Test
    fun `a same-session remount measures rate support again`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 99.0, playbackSpeed = 1.0, mountGeneration = 1) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        follow(player, drift = 2.0, forMs = 1_200)
        assertEquals(1, player.seeks.size, "rate support was never ruled out")

        // A server seek recovery mounts new media under the same session.
        player.update { copy(seekPending = true, mountGeneration = 2) }
        advanceTimeBy(100)
        player.update { copy(seekPending = false, playbackSpeed = 1.04) }
        follow(player, drift = 0.0, forMs = 100)
        player.update { copy(sourcePositionSeconds = sourcePositionSeconds - 2.0) }
        command(room, "again", TransportAction.Play, 100.0 + testScheduler.currentTime / 1000.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(1.04, player.rates.last()!!, 0.0001)
        assertEquals(1, player.seeks.size)
    }

    @Test
    fun `the fallback's own in-place seek keeps seek-only correction`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 99.0, playbackSpeed = 1.0, mountGeneration = 1) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        follow(player, drift = 2.0, forMs = 1_200)
        assertEquals(1, player.seeks.size, "rate support was never ruled out")

        // That seek is native within the mounted item: seeking, same mount.
        player.update { copy(seekPending = true) }
        advanceTimeBy(100)
        player.update { copy(seekPending = false) }
        follow(player, drift = 0.0, forMs = 100)
        player.update { copy(sourcePositionSeconds = sourcePositionSeconds - 2.0) }
        command(room, "again", TransportAction.Play, 100.0 + testScheduler.currentTime / 1000.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(2, player.seeks.size, "a nudge was tried again on a mount that ignores rate")
        assertNull(player.rates.last())
    }

    @Test
    fun `a mount that ignores the rate falls back to spaced, backed-off in-place seeks`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        // The engine keeps reporting 1x whatever is asked (tunneling, passthrough).
        player.update { copy(sourcePositionSeconds = 99.0, playbackSpeed = 1.0) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(1.04, player.rates.last()!!, 0.0001)

        follow(player, drift = 2.0, forMs = 1_200)
        assertNull(player.rates.last())
        assertEquals(1, player.seeks.size)
        val firstSeek = player.seeks.single()
        assertTrue(firstSeek in 100.9..101.3, "seeked to $firstSeek")

        // The seek (at about 1 s) left the member as far behind as before: a
        // miss, so the next waits two seconds and aims ahead by the shortfall.
        follow(player, drift = 2.0, forMs = 1_700)
        assertEquals(1, player.seeks.size)
        follow(player, drift = 2.0, forMs = 200)
        assertEquals(2, player.seeks.size)
        assertEquals(100.0 + 3.0 + 2.0, player.seeks.last(), 0.15)
        assertNull(player.rates.last(), "nudged again on a mount without rate support")

        // A command's nudge becomes an in-place seek too.
        follow(player, drift = 0.0, forMs = 100)
        player.update { copy(sourcePositionSeconds = sourcePositionSeconds - 2.0) }
        command(room, "again", TransportAction.Play, 100.0 + testScheduler.currentTime / 1000.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(3, player.seeks.size)
        assertNull(player.rates.last())
    }

    @Test
    fun `a member buffering toward play reloads a large gap through the budget`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer().apply { buffered = { false } }
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 50.0, isPlaying = false, state = RoomPlayerState.Buffering) }
        command(room, "r1", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(100)
        assertEquals(1, player.seeks.size)

        command(room, "r2", TransportAction.Play, 110.0, RoomPlaybackState.Playing)
        advanceTimeBy(100)
        assertEquals(1, player.seeks.size, "an unpaced rebuild while buffering toward play")
    }

    @Test
    fun `a host's stale sample after its own pause never reports the pause as playing`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        room.roomSnapshot.value = playing(role = MemberRole.Host)
        advanceTimeBy(100)
        command(room, "pause", TransportAction.Pause, 100.0, RoomPlaybackState.Paused)
        // The engine sample still says playing.
        advanceTimeBy(2_000)
        assertTrue(
            room.sent.none { it.kind == "report" && it.commandId == "pause" && !it.paused },
            "a report named the pause with the old play state",
        )

        player.update { copy(playWhenReady = false, isPlaying = false) }
        advanceTimeBy(1_600)
        val named = room.sent.filter { it.kind == "report" && it.commandId == "pause" }
        assertTrue(named.isNotEmpty())
        assertTrue(named.all { it.paused })
    }

    @Test
    fun `a player that never shows the command's state reports again without naming it`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        command(room, "pause", TransportAction.Pause, 100.0, RoomPlaybackState.Paused)
        advanceTimeBy(4_000)
        val reports = room.sent.filter { it.kind == "report" }
        assertTrue(reports.isNotEmpty(), "reports never resumed")
        assertTrue(reports.none { it.commandId == "pause" })
    }

    @Test
    fun `a player slower than the report deadline names the command once it shows it`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        command(room, "pause", TransportAction.Pause, 100.0, RoomPlaybackState.Paused)
        advanceTimeBy(4_000)
        assertTrue(room.sent.none { it.kind == "report" && it.commandId == "pause" })

        player.update { copy(playWhenReady = false, isPlaying = false) }
        advanceTimeBy(1_600)
        val last = room.sent.last { it.kind == "report" }
        assertEquals("pause", last.commandId)
        assertTrue(last.paused)
    }

    /** A rate-incapable member 2 s behind whose last fallback seek missed by 2 s: the lead is now 2 s. */
    private fun TestScope.rateIncapableWithLead(room: FakeRoom, player: FakePlayer) {
        player.update { copy(sourcePositionSeconds = 99.0, playbackSpeed = 1.0, mountGeneration = 1) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        follow(player, drift = 2.0, forMs = 1_200) // fallback seek at about 1 s
        follow(player, drift = 2.0, forMs = 900) // measured at about 2 s: lead 2 s, next seek at 3 s
        assertEquals(1, player.seeks.size)
    }

    @Test
    fun `a command's fallback seek drops the lead when the led target is not reachable in place`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        // Reachable up to 2.5 s ahead of the playhead.
        player.buffered = { it <= player.observations.value.sourcePositionSeconds + 2.5 }
        bind(room, player)
        rateIncapableWithLead(room, player)

        val roomNow = 100.0 + testScheduler.currentTime / 1000.0
        player.update { copy(sourcePositionSeconds = roomNow - 2.0) }
        command(room, "again", TransportAction.Play, roomNow, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(2, player.seeks.size)
        assertEquals(roomNow, player.seeks.last(), 0.1)
    }

    @Test
    fun `the loop's fallback seek drops the lead when the led target is not reachable in place`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        player.buffered = { it <= player.observations.value.sourcePositionSeconds + 2.5 }
        bind(room, player)
        rateIncapableWithLead(room, player)

        follow(player, drift = 2.0, forMs = 900)
        assertEquals(2, player.seeks.size, "no seek: only the led target was checked")
        // At about 3 s the room is at 103; 105 (led) is past what is reachable.
        assertEquals(103.0, player.seeks.last(), 0.15)
    }

    @Test
    fun `a member stopped ahead of the room seeks back for a pause and a play from rest`() = runTest {
        val room = FakeRoom()
        // Reachable: anywhere behind the playhead (a seekable item), nothing ahead of it.
        val player = FakePlayer()
        player.buffered = { it <= player.observations.value.sourcePositionSeconds }
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 103.0, isPlaying = false, playWhenReady = false) }
        command(room, "pause", TransportAction.Pause, 100.0, RoomPlaybackState.Paused)
        advanceTimeBy(100)
        assertEquals(listOf(100.0), player.seeks)

        player.update { copy(sourcePositionSeconds = 103.0) }
        command(room, "play", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(2, player.seeks.size)
        assertEquals(100.0, player.seeks.last(), 0.1)
        assertEquals(true, player.playing.last())
    }

    @Test
    fun `a rate-incapable member ahead of the room seeks back`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        player.buffered = { it <= player.observations.value.sourcePositionSeconds }
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 101.0, playbackSpeed = 1.0, mountGeneration = 1) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(0.96, player.rates.last()!!, 0.0001)

        follow(player, drift = -3.0, forMs = 1_200)
        assertNull(player.rates.last())
        assertEquals(1, player.seeks.size)
        assertTrue(player.seeks.single() in 100.9..101.3, "seeked to ${player.seeks.single()}")
    }

    @Test
    fun `slow fallback seeks aim ahead by their measured shortfall`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 99.0, playbackSpeed = 1.0, mountGeneration = 1) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        follow(player, drift = 2.0, forMs = 1_200)
        // The first fallback seek, at about 1 s, aims at the room: 101.
        assertEquals(1, player.seeks.size)
        assertEquals(101.0, player.seeks.single(), 0.2)

        // It takes 1.25 s to land, so playback resumes 1.25 s behind the room.
        player.update { copy(seekPending = true) }
        advanceTimeBy(1_250)
        player.update { copy(seekPending = false) }
        follow(player, drift = 1.25, forMs = 1_650)
        assertEquals(1, player.seeks.size, "the interval did not back off after a miss")

        // The next one aims ahead by the shortfall it measured.
        follow(player, drift = 1.25, forMs = 400)
        assertEquals(2, player.seeks.size)
        val roomAtSeek = 100.0 + 4.25
        assertEquals(roomAtSeek + 1.25, player.seeks.last(), 0.15)

        // With the same latency it now lands on the room and stops.
        player.update { copy(seekPending = true) }
        advanceTimeBy(1_250)
        player.update { copy(seekPending = false) }
        follow(player, drift = 0.0, forMs = 4_000)
        assertEquals(2, player.seeks.size)
    }

    @Test
    fun `ordinary reports name the last applied command`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        command(room, "c1", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(1_600)
        assertEquals("c1", room.sent.last { it.kind == "report" }.commandId)
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

    @Test
    fun `a play correction held by the reload budget runs once the budget allows`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer().apply { buffered = { false } }
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 50.0) }
        command(room, "r1", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(100)
        command(room, "r2", TransportAction.Play, 110.0, RoomPlaybackState.Playing)
        advanceTimeBy(9_000)
        assertEquals(1, player.seeks.size)

        // The superseded reload backs off for ten seconds; then the held
        // correction reloads toward where the room is now.
        advanceTimeBy(1_500)
        assertEquals(2, player.seeks.size)
        assertTrue(player.seeks.last() >= 119.0, "aimed at ${player.seeks.last()}")
    }

    @Test
    fun `a reconnect returns a correction rate to exactly 1x`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer().apply { buffered = { false } }
        bind(room, player)
        player.update { copy(sourcePositionSeconds = 99.0) }
        command(room, "drift", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(50)
        assertEquals(1.04, player.rates.last()!!, 0.0001)

        room.connectionState.value = connection.copy(writable = false)
        advanceTimeBy(300)
        assertNull(player.rates.last())
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
    fun `a new title starts with no stall history`() = runTest {
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
        room.roomSnapshot.value = playing(revision = 4)
        advanceTimeBy(300)
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

    @Test
    fun `a room play never resumes a locally suspended player`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(suspended = true, playWhenReady = false) }
        command(room, "play", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(100)
        assertTrue(player.playing.none { it })
    }

    @Test
    fun `a command during a mount waits for it and still advances a late play`() = runTest {
        val room = FakeRoom()
        val player = FakePlayer()
        bind(room, player)
        player.update { copy(seekPending = true, sourcePositionSeconds = 20.0) }
        command(room, "after-mount", TransportAction.Play, 100.0, RoomPlaybackState.Playing)
        advanceTimeBy(1_000)
        assertTrue(player.seeks.isEmpty())

        player.update { copy(seekPending = false) }
        advanceTimeBy(300)
        assertEquals(101.0, player.seeks.single(), 0.4)
        assertEquals(true, player.playing.last())
    }

    private companion object {
        const val WALL_ORIGIN = 1_790_000_000_000L
    }
}
