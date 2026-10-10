package org.siloserver.silo.common.player.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerReconciliationFenceTest {
    @Test fun newerEventRejectsPendingRead() {
        val fence = MarkerReconciliationFence()
        val read = fence.begin()
        fence.invalidate()
        assertFalse(fence.isCurrent(read))
    }

    @Test fun reconnectRejectsPreviousConnectionRead() {
        val fence = MarkerReconciliationFence()
        val first = fence.begin()
        val reconnect = fence.begin()
        assertFalse(fence.isCurrent(first))
        assertTrue(fence.isCurrent(reconnect))
    }
}
