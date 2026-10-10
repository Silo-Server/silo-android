package org.siloserver.silo.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.network.HomeRealtimeClient
import org.siloserver.silo.network.HomeRealtimeEvent
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.isAccessChangedClose
import org.siloserver.silo.network.apiv2.isAccessChangedFrame
import io.ktor.websocket.CloseReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRealtimeAccessChangeTest {

    private class ScriptedClient(private val script: (Int) -> Flow<HomeRealtimeEvent>) : HomeRealtimeClient {
        var connects = 0
        override fun connect(): Flow<HomeRealtimeEvent> = script(++connects)
    }

    @Test
    fun `an access change refreshes Home once per burst`() = runTest {
        val signals = AccessChangeSignals()
        val client = ScriptedClient { flow { awaitCancellation() } }
        val coordinator = HomeRealtimeCoordinator(client, TokenManagerImpl(), signals)
        var refreshes = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.refreshSignals.collect { refreshes++ }
        }
        testScheduler.runCurrent()

        // Both events sockets see the change, each reporting it once.
        signals.reportAccessChanged()
        signals.reportAccessChanged()
        testScheduler.advanceTimeBy(10_000L)

        assertEquals(1, refreshes)
    }

    @Test
    fun `reports of one change from both sockets refresh once`() = runTest {
        val signals = AccessChangeSignals { testScheduler.currentTime }
        val changes = mutableListOf<Long>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            signals.changes.collect { changes += testScheduler.currentTime }
        }
        testScheduler.runCurrent()

        // Each socket runs its own 15-second server check, so the second
        // report of the same change can arrive seconds after the first.
        signals.reportAccessChanged()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(12_000L)
        signals.reportAccessChanged()
        testScheduler.runCurrent()
        assertEquals(listOf(0L), changes, "the first report refreshes at once; the second is the same change")

        // A later change refreshes again.
        testScheduler.advanceTimeBy(AccessChangeSignals.ACCESS_CHANGE_COALESCE_MS)
        signals.reportAccessChanged()
        testScheduler.runCurrent()
        assertEquals(2, changes.size)
    }

    @Test
    fun `a second change reported by a reconnected socket inside the window refreshes`() = runTest {
        val signals = AccessChangeSignals { testScheduler.currentTime }
        val changes = mutableListOf<Long>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            signals.changes.collect { changes += testScheduler.currentTime }
        }
        testScheduler.runCurrent()
        val homeOpened = signals.connectionTime()
        val notificationsOpened = signals.connectionTime()

        // Home reports the first edit and reconnects under the new policy.
        testScheduler.advanceTimeBy(3_000L)
        signals.reportAccessChanged(homeOpened)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(1_500L)
        val homeReopened = signals.connectionTime()

        // Notifications reports the same edit from its older connection.
        testScheduler.advanceTimeBy(7_500L)
        signals.reportAccessChanged(notificationsOpened)
        testScheduler.runCurrent()
        assertEquals(1, changes.size, "the older connection's report is the change already loaded")

        // A second edit, seen by the reconnected Home socket 15 s after it opened.
        testScheduler.advanceTimeBy(4_000L)
        signals.reportAccessChanged(homeReopened)
        testScheduler.runCurrent()
        assertEquals(2, changes.size, "a connection opened after the refresh reports a new change")
    }

    @Test
    fun `nothing refreshes without an access change`() = runTest {
        val signals = AccessChangeSignals()
        val coordinator = HomeRealtimeCoordinator(ScriptedClient { flow { awaitCancellation() } }, TokenManagerImpl(), signals)
        var refreshes = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.refreshSignals.collect { refreshes++ }
        }

        testScheduler.advanceTimeBy(10_000L)

        assertEquals(0, refreshes)
    }

    @Test
    fun `the socket reconnects promptly after an access-changed close`() = runTest {
        // First connection: subscribed, then the server's 4001 close ends it.
        val client = ScriptedClient { attempt ->
            if (attempt == 1) flowOf(HomeRealtimeEvent.Connected, HomeRealtimeEvent.Closed())
            else flow { awaitCancellation() }
        }
        val coordinator = HomeRealtimeCoordinator(client, TokenManagerImpl(), AccessChangeSignals())
        val job = coordinator.connect(backgroundScope)

        testScheduler.advanceTimeBy(1_100L)

        assertEquals(2, client.connects, "a fresh connection (and ticket) after the initial backoff")
        job.cancel()
    }

    @Test
    fun `access-change frames and close codes are recognised`() {
        assertTrue(isAccessChangedFrame("""{"type":"access_changed"}"""))
        assertFalse(isAccessChangedFrame("""{"type":"subscribed","channel":"catalog"}"""))
        assertFalse(isAccessChangedFrame("not json"))
        assertTrue(isAccessChangedClose(CloseReason(4001, "access_changed")))
        assertFalse(isAccessChangedClose(CloseReason(CloseReason.Codes.NORMAL, "")))
        assertFalse(isAccessChangedClose(null))
    }
}
