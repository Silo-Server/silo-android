package org.siloserver.silo.common.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.apiv2.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ServerReachabilityMonitorTest {
    private val good = ApiV2ProbeResult.V2(SystemInfo("test", 2, "digest", SystemInfoLinks("/openapi", "/capabilities")))
    private val bad = ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.SERVER_ERROR, 503)
    private val targets = MutableStateFlow<ReachabilityTarget?>(ReachabilityTarget("s", "https://example.invalid", 1))
    private var calls = 0
    private var callbacks = 0
    private var response: suspend () -> ApiV2ProbeResult = { good }
    private fun TestScope.monitor() = ServerReachabilityMonitor(
        probe = { assertEquals(targets.value?.url, it); calls++; response() },
        scope = backgroundScope, captureTarget = { targets.value }, targetChanges = targets,
        nowMs = { testScheduler.currentTime }, onServerReconnected = { callbacks++ },
    )
    @Test fun unknownAndImmediateFreshSuccessWithSlowCadence() = runTest {
        val monitor = monitor()
        assertEquals(ServerReachabilityStatus.Unknown, monitor.state.value.status)
        assertTrue(monitor.state.value.canUseServer)
        monitor.startForeground(); runCurrent()
        assertEquals(ServerReachabilityStatus.Reachable, monitor.state.value.status)
        assertEquals(1, calls)
        advanceTimeBy(19_999); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
    }
    @Test fun failuresUseFastCadenceAndOnlySameServerReconnects() = runTest {
        val monitor = monitor(); response = { bad }
        monitor.startForeground(); runCurrent()
        assertEquals(ServerReachabilityStatus.Unreachable, monitor.state.value.status)
        assertFalse(monitor.state.value.canUseServer)
        response = { good }; advanceTimeBy(8_000); runCurrent()
        assertEquals(2, calls); assertEquals(1, callbacks)
        response = { bad }; monitor.retryNow()
        targets.value = ReachabilityTarget("other", "https://other.invalid", 2); runCurrent()
        assertEquals(ServerReachabilityStatus.Unknown, monitor.state.value.status)
        response = { good }; monitor.retryNow(); assertEquals(1, callbacks)
    }
    @Test fun lateResponseAfterABAAndStopCannotPublishOrReconnect() = runTest {
        val monitor = monitor(); runCurrent()
        response = { bad }; monitor.retryNow()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        response = { entered.complete(Unit); release.await(); good }
        val old = launch { monitor.retryNow() }; entered.await()
        targets.value = targets.value!!.copy(generation = 3) // same server and URL after A→B→A
        runCurrent(); release.complete(Unit); old.join()
        assertEquals(ServerReachabilityStatus.Unknown, monitor.state.value.status)
        assertEquals(0, callbacks)
        val entered2 = CompletableDeferred<Unit>(); val release2 = CompletableDeferred<Unit>()
        response = { entered2.complete(Unit); release2.await(); good }
        val stopped = launch { monitor.retryNow() }; entered2.await()
        monitor.stopForeground(); release2.complete(Unit); stopped.join()
        assertEquals(ServerReachabilityStatus.Unknown, monitor.state.value.status)
        response = { good }; monitor.startForeground(); runCurrent()
        assertEquals(ServerReachabilityStatus.Reachable, monitor.state.value.status)
        assertEquals(0, callbacks)
    }
    @Test fun queuedManualProbeSupersedesOldResponseAndMissingServerDoesNotDispatch() = runTest {
        val monitor = monitor(); runCurrent()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        response = { entered.complete(Unit); release.await(); bad }
        val first = launch { monitor.retryNow() }; entered.await()
        val second = launch { monitor.retryNow() }; runCurrent()
        response = { good }; release.complete(Unit); first.join(); second.join()
        assertEquals(ServerReachabilityStatus.Reachable, monitor.state.value.status)
        assertEquals(0, callbacks)
        targets.value = null; runCurrent(); monitor.retryNow()
        assertEquals(2, calls); assertEquals(ServerReachabilityStatus.Unknown, monitor.state.value.status)
    }
}
