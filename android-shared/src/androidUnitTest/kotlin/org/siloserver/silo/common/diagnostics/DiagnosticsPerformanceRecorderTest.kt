package org.siloserver.silo.common.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsPerformanceRecorderTest {
    @Test
    fun windowAggregatesRefreshAwareSlowFramesAndMainThreadStalls() {
        val window = PerformanceWindowAggregator(refreshPeriodMs = 16.67)

        window.recordFrame(15)
        window.recordFrame(34)
        window.recordFrame(60)
        window.recordMainThreadDelay(50)
        window.recordMainThreadDelay(275)
        window.recordMainThreadDelay(800)

        val snapshot = window.snapshotAndReset()

        assertEquals(3, snapshot.frameCount)
        assertEquals(2, snapshot.slowFrameCount)
        assertEquals(60, snapshot.p95FrameMs)
        assertEquals(60, snapshot.worstFrameMs)
        assertEquals(2, snapshot.mainThreadStallCount)
        assertEquals(800, snapshot.longestMainThreadStallMs)
        assertEquals(0, window.snapshotAndReset().frameCount)
    }

    @Test
    fun valuesAreBoundedBeforeTheyBecomeDiagnosticsAttributes() {
        val window = PerformanceWindowAggregator(refreshPeriodMs = 0.1)

        window.recordFrame(Long.MAX_VALUE)
        window.recordMainThreadDelay(Long.MAX_VALUE)

        val snapshot = window.snapshotAndReset()

        assertEquals(60_000, snapshot.worstFrameMs)
        assertEquals(60_000, snapshot.longestMainThreadStallMs)
    }

    @Test
    fun resourceCheckpointsAreRateLimitedPerSurfaceAndCause() {
        val cadence = DiagnosticsCheckpointCadence(intervalMs = 30_000)

        assertTrue(
            cadence.shouldEmit(
                DiagnosticsListSurface.PHONE_HOME,
                DiagnosticsResourceCheckpointCause.LIST_READY,
                nowMs = 100_000,
            ),
        )
        assertFalse(
            cadence.shouldEmit(
                DiagnosticsListSurface.PHONE_HOME,
                DiagnosticsResourceCheckpointCause.LIST_READY,
                nowMs = 129_999,
            ),
        )
        assertTrue(
            cadence.shouldEmit(
                DiagnosticsListSurface.PHONE_HOME,
                DiagnosticsResourceCheckpointCause.SCROLL_START,
                nowMs = 100_001,
            ),
        )
        assertTrue(
            cadence.shouldEmit(
                DiagnosticsListSurface.PHONE_HOME,
                DiagnosticsResourceCheckpointCause.LIST_READY,
                nowMs = 130_000,
            ),
        )
    }
}
