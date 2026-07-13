package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvSeekRecoveryQueueTest {
    @Test
    fun rapidServerSeeksKeepOnlyLatestPendingTarget() {
        val queue = TvSeekRecoveryQueue()
        val first = assertIs<TvSeekRecoverySubmission.Start>(
            queue.submit(1L, reanchor(10.0)),
        ).request

        assertIs<TvSeekRecoverySubmission.Queued>(queue.submit(2L, reanchor(20.0)))
        assertIs<TvSeekRecoverySubmission.Queued>(queue.submit(3L, reanchor(30.0)))

        assertFalse(queue.isCurrent(first, activeSeekId = 3L))
        val promoted = queue.complete(first)
        assertEquals(3L, promoted?.seekId)
        assertEquals(30.0, promoted?.operation?.targetSourceSeconds)
        assertTrue(queue.isCurrent(requireNotNull(promoted), activeSeekId = 3L))
        assertNull(queue.complete(promoted))
        assertFalse(queue.hasInFlight)
    }

    @Test
    fun responseCannotAdoptAfterActiveSeekChanges() {
        val queue = TvSeekRecoveryQueue()
        val request = assertIs<TvSeekRecoverySubmission.Start>(
            queue.submit(7L, reanchor(70.0)),
        ).request

        assertTrue(queue.isCurrent(request, activeSeekId = 7L))
        assertFalse(queue.isCurrent(request, activeSeekId = 8L))
    }

    @Test
    fun failureRecoveryQueuesBehindCommittedReanchor() {
        val queue = TvSeekRecoveryQueue()
        val reanchor = assertIs<TvSeekRecoverySubmission.Start>(
            queue.submit(9L, reanchor(90.0)),
        ).request
        val failure = TvSeekRecoveryOperation.Failure(
            targetSourceSeconds = 90.0,
            classification = "decoder_failure",
            notice = "Decoder rejected the re-anchored route.",
        )

        assertIs<TvSeekRecoverySubmission.Queued>(queue.submit(9L, failure))
        assertFalse(queue.isCurrent(reanchor, activeSeekId = 9L))
        val promoted = requireNotNull(queue.complete(reanchor))
        assertEquals(failure, promoted.operation)
        assertTrue(queue.isCurrent(promoted, activeSeekId = 9L))
    }

    @Test
    fun contentResetInvalidatesInFlightResponseWithoutCompletingNewWork() {
        val queue = TvSeekRecoveryQueue()
        val old = assertIs<TvSeekRecoverySubmission.Start>(
            queue.submit(1L, reanchor(10.0)),
        ).request

        queue.reset()
        val fresh = assertIs<TvSeekRecoverySubmission.Start>(
            queue.submit(2L, reanchor(20.0)),
        ).request

        assertFalse(queue.isCurrent(old, activeSeekId = 2L))
        assertNull(queue.complete(old))
        assertTrue(queue.isCurrent(fresh, activeSeekId = 2L))
    }

    @Test
    fun mountGateRejectsStaleAcknowledgementsAndBlocksLoadReports() {
        val gate = TvTransportMountGate()

        gate.beginLoad()
        assertTrue(gate.suppressPositionReports)
        gate.expect(4L)
        assertFalse(gate.applied(3L))
        assertTrue(gate.suppressPositionReports)
        assertTrue(gate.applied(4L))
        assertFalse(gate.suppressPositionReports)

        gate.expect(5L)
        gate.expect(6L)
        assertFalse(gate.applied(5L))
        assertTrue(gate.suppressPositionReports)
        assertTrue(gate.applied(6L))
        assertFalse(gate.suppressPositionReports)
    }

    private fun reanchor(target: Double) = TvSeekRecoveryOperation.Reanchor(
        targetSourceSeconds = target,
        reason = "test",
    )
}
