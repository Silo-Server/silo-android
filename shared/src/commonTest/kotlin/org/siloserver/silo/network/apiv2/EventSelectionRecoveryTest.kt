package org.siloserver.silo.network.apiv2

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.siloserver.silo.model.notifications.WsSubscribe
import org.siloserver.silo.network.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventSelectionRecoveryTest {
    @Test fun retriesLatestCorrelatedSelectionOncePerTimerWithoutReconnectOrStaleAck() = runTest {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val desires = MutableSharedFlow<List<String>>()
        val sent = mutableListOf<WsSubscribe>()
        val received = mutableListOf<String>()
        val session = EventSelectionSession(RealtimeBackoff({ testScheduler.currentTime }, { 0 }), true, { testScheduler.currentTime })
        val job = backgroundScope.launch {
            session.frames(listOf("catalog"), { incoming.receive() }, {
                sent += SiloJson.decodeFromString(WsSubscribe.serializer(), it)
            }, { true }, desires).collect { received += it }
        }
        runCurrent()
        assertEquals(1, sent.size)
        val oldId = sent.single().requestId!!
        incoming.send(refusal(oldId, 500))
        runCurrent(); advanceTimeBy(200)
        desires.emit(listOf("user_state")); runCurrent()
        assertEquals(1, sent.size)
        incoming.send("""{"type":"subscribed","request_id":"$oldId"}""")
        incoming.send(refusal(oldId, 250))
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(2, sent.size)
        val latest = sent.last()
        assertEquals(listOf("user_state"), latest.channels)
        assertFalse(received.any { it.contains("subscribed") })
        incoming.send(refusal(latest.requestId!!, 500))
        runCurrent(); advanceTimeBy(499); runCurrent(); assertEquals(2, sent.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, sent.size)
        assertEquals(latest, sent.last())
        incoming.send("""{"type":"subscribed","request_id":"${latest.requestId}"}""")
        runCurrent(); advanceTimeBy(600); runCurrent()
        assertEquals(3, sent.size)
        job.cancel()
    }

    @Test fun oldServersDoNotGainRefusalRetriesAndAuthorityLossCancelsTimer() = runTest {
        suspend fun exercise(enabled: Boolean, loseAuthority: Boolean): Int {
            var current = true
            val incoming = Channel<String>(Channel.UNLIMITED)
            val sent = mutableListOf<WsSubscribe>()
            val job = backgroundScope.launch {
                EventSelectionSession(RealtimeBackoff({ testScheduler.currentTime }, { 0 }), enabled, { testScheduler.currentTime })
                    .frames(listOf("notifications"), { incoming.receive() }, {
                        sent += SiloJson.decodeFromString(WsSubscribe.serializer(), it)
                    }, { current }).collect { }
            }
            runCurrent()
            incoming.send(refusal(sent.single().requestId!!, 250)); runCurrent()
            if (loseAuthority) current = false
            advanceTimeBy(300); runCurrent()
            job.cancel()
            return sent.size
        }
        assertEquals(1, exercise(false, false))
        assertEquals(1, exercise(true, true))
    }

    private fun refusal(id: String, wait: Long) =
        """{"type":"error","code":"realtime_rate_limited","request_id":"$id","retry_after_ms":$wait,"work_started":false}"""
}
