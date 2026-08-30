package org.siloserver.silo.common.diagnostics

import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsImageHealthTest {
    @AfterTest
    fun tearDown() {
        SiloLog.installSink(null)
    }

    @Test
    fun accumulatorEmitsRateLimitedFailuresAndAggregateSourceMix() {
        var nowMs = 10_000L
        val accumulator = DiagnosticsImageHealthAccumulator(
            windowSize = 4,
            failureIntervalMs = 60_000,
            nowMs = { nowMs },
        )

        assertNull(accumulator.success(DiagnosticsImageSource.MEMORY).window)
        val firstFailure = accumulator.failure(
            DiagnosticsImageStage.FETCH,
            DiagnosticsImageFailureKind.HTTP,
        )
        assertNotNull(firstFailure.failure)
        assertNull(firstFailure.window)
        assertNull(
            accumulator.failure(
                DiagnosticsImageStage.DECODE,
                DiagnosticsImageFailureKind.DECODE,
            ).failure,
        )
        val window = assertNotNull(accumulator.success(DiagnosticsImageSource.NETWORK).window)

        assertEquals(4, window.completed)
        assertEquals(2, window.failures)
        assertEquals(1, window.memorySuccesses)
        assertEquals(1, window.networkSuccesses)

        nowMs += 60_000
        assertNotNull(
            accumulator.failure(
                DiagnosticsImageStage.DECODE,
                DiagnosticsImageFailureKind.DECODE,
            ).failure,
        )
    }

    @Test
    fun imageFailureClassificationAndLogsNeverUseThrowableMessages() {
        val privateMessage = "https://private.example/media/private-title.jpg"
        val kind = classifyImageFailure(
            DiagnosticsImageStage.DECODE,
            IOException(privateMessage),
        )
        val lines = mutableListOf<String>()
        SiloLog.installSink { lines += it }

        DiagnosticsImageLogger.failure(
            DiagnosticsImageFailure(DiagnosticsImageStage.DECODE, kind),
        )
        DiagnosticsImageLogger.window(
            DiagnosticsImageWindow(
                completed = 50,
                failures = 5,
                memorySuccesses = 20,
                diskSuccesses = 15,
                networkSuccesses = 10,
            ),
        )

        assertEquals(DiagnosticsImageFailureKind.DECODE, kind)
        assertTrue(lines[0].contains("\"reason\":\"decode\""), lines[0])
        assertTrue(lines[1].contains("failure_rate_10_24"), lines[1])
        assertTrue(lines[1].contains("memory_25_49_disk_25_49_network_1_24"), lines[1])
        assertFalse(lines.any { it.contains(privateMessage) })
    }

    @Test
    fun subOnePercentImageShareStaysInTheSmallestNonzeroBucket() {
        val lines = mutableListOf<String>()
        SiloLog.installSink { lines += it }

        DiagnosticsImageLogger.window(
            DiagnosticsImageWindow(
                completed = 200,
                failures = 0,
                memorySuccesses = 1,
                diskSuccesses = 99,
                networkSuccesses = 100,
            ),
        )

        assertTrue(lines.single().contains("memory_1_24_disk_25_49_network_50_74"), lines.single())
    }
}
