package org.siloserver.silo.common.downloads

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadSlotLocksTest {

    @Test
    fun everyCallerSharesOneLockPerSlot() {
        val capture = DownloadSlotLocks.of("server", "profile", 7)
        assertTrue(capture.tryLock())
        try {
            // A worker or view model looking the slot up separately gets the same lock.
            assertSame(capture, DownloadSlotLocks.of("server", "profile", 7))
            assertFalse(DownloadSlotLocks.of("server", "profile", 7).tryLock())

            val otherSlot = DownloadSlotLocks.of("server", "profile", 8)
            assertTrue(otherSlot.tryLock())
            otherSlot.unlock()
        } finally {
            capture.unlock()
        }
    }
}
