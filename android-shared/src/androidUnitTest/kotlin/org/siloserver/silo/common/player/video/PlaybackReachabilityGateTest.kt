package org.siloserver.silo.common.player.video

import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.HealthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReachabilityGateTest {

    @Test
    fun `unknown status proceeds optimistically`() = runTest {
        val monitor = monitorAt(backgroundScope, probeResult = null, probe = false)

        assertEquals(
            PreplayReachabilityDecision.Proceed,
            evaluatePreplayReachability(monitor, force = false, hasLocalDownload = { false }),
        )
        assertTrue(shouldReachServerForPlayback(monitor, force = false))
    }

    @Test
    fun `reachable status proceeds`() = runTest {
        val monitor = monitorAt(
            backgroundScope,
            probeResult = ApiResult.Success(HealthStatus(status = "ok")),
        )

        assertEquals(
            PreplayReachabilityDecision.Proceed,
            evaluatePreplayReachability(monitor, force = false, hasLocalDownload = { true }),
        )
        assertTrue(shouldReachServerForPlayback(monitor, force = false))
    }

    @Test
    fun `unreachable without local download is ServerUnreachable`() = runTest {
        val monitor = monitorAt(
            backgroundScope,
            probeResult = ApiResult.Error(503, "unavailable", "origin down"),
        )

        assertEquals(
            PreplayReachabilityDecision.ServerUnreachable,
            evaluatePreplayReachability(monitor, force = false, hasLocalDownload = { false }),
        )
        assertFalse(shouldReachServerForPlayback(monitor, force = false))
    }

    @Test
    fun `unreachable with local download plays offline`() = runTest {
        val monitor = monitorAt(
            backgroundScope,
            probeResult = ApiResult.Error(503, "unavailable", "origin down"),
        )

        assertEquals(
            PreplayReachabilityDecision.PlayLocalOffline,
            evaluatePreplayReachability(monitor, force = false, hasLocalDownload = { true }),
        )
    }

    @Test
    fun `force bypasses the gate even while unreachable`() = runTest {
        val monitor = monitorAt(
            backgroundScope,
            probeResult = ApiResult.Error(503, "unavailable", "origin down"),
        )

        assertEquals(
            PreplayReachabilityDecision.Proceed,
            evaluatePreplayReachability(monitor, force = true, hasLocalDownload = { false }),
        )
        assertTrue(shouldReachServerForPlayback(monitor, force = true))
    }

    /**
     * Builds a monitor and, unless [probe] is false, drives one foreground probe
     * so [probeResult] lands as the cached status the gate reads.
     */
    private fun kotlinx.coroutines.test.TestScope.monitorAt(
        scope: CoroutineScope,
        probeResult: ApiResult<HealthStatus>?,
        probe: Boolean = true,
    ): ServerReachabilityMonitor {
        val monitor = ServerReachabilityMonitor(
            probe = {
                if (probeResult == null || probeResult is ApiResult.Success)
                    org.siloserver.silo.network.apiv2.ApiV2ProbeResult.V2(org.siloserver.silo.network.apiv2.SystemInfo("test", 2, "digest", org.siloserver.silo.network.apiv2.SystemInfoLinks("/openapi", "/capabilities")))
                else org.siloserver.silo.network.apiv2.ApiV2ProbeResult.Failure(org.siloserver.silo.network.apiv2.ApiV2ProbeResult.Kind.CONNECTION)
            },
            captureTarget = { org.siloserver.silo.common.network.ReachabilityTarget("s", "https://example.invalid", 1) },
            scope = scope,
        )
        if (probe) {
            monitor.startForeground()
            runCurrent()
        }
        return monitor
    }
}
