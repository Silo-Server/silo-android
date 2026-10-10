package org.siloserver.silo.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A ViewModel's cursor carries access changes across the times its screen is
 * out of composition, so returning to the screen applies a change reported
 * while another destination was in front.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessChangeCursorTest {

    @Test
    fun `the first collection does not refresh`() = runTest {
        val signals = AccessChangeSignals { testScheduler.currentTime }
        signals.reportAccessChanged()
        val cursor = AccessChangeCursor()

        assertEquals(0, collectWhileVisible(signals, cursor) {})
    }

    @Test
    fun `a change reported while away refreshes on return`() = runTest {
        val signals = AccessChangeSignals { testScheduler.currentTime }
        val cursor = AccessChangeCursor()
        collectWhileVisible(signals, cursor) {}

        // The screen is out of composition (detail in front) when it arrives.
        signals.reportAccessChanged()

        assertEquals(1, collectWhileVisible(signals, cursor) {})
        assertEquals(0, collectWhileVisible(signals, cursor) {}, "an applied change does not refresh twice")
    }

    @Test
    fun `the second socket's report of an applied change does not refresh on return`() = runTest {
        val signals = AccessChangeSignals { testScheduler.currentTime }
        val cursor = AccessChangeCursor()
        val whileVisible = collectWhileVisible(signals, cursor) {
            signals.reportAccessChanged()
        }
        assertEquals(1, whileVisible)

        // The other events socket reports the same change seconds later, while
        // the screen is away.
        testScheduler.advanceTimeBy(12_000L)
        signals.reportAccessChanged()

        assertEquals(0, collectWhileVisible(signals, cursor) {})
    }

    @Test
    fun `a later change while away refreshes on return`() = runTest {
        val signals = AccessChangeSignals { testScheduler.currentTime }
        val cursor = AccessChangeCursor()
        collectWhileVisible(signals, cursor) { signals.reportAccessChanged() }

        testScheduler.advanceTimeBy(AccessChangeSignals.ACCESS_CHANGE_COALESCE_MS)
        signals.reportAccessChanged()

        assertEquals(1, collectWhileVisible(signals, cursor) {})
    }

    /** Collects with [cursor] like a composed screen, runs [whileVisible], then leaves. */
    private fun TestScope.collectWhileVisible(
        signals: AccessChangeSignals,
        cursor: AccessChangeCursor,
        whileVisible: () -> Unit,
    ): Int {
        var refreshes = 0
        val job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            signals.changes(cursor).collect { refreshes++ }
        }
        testScheduler.runCurrent()
        whileVisible()
        testScheduler.runCurrent()
        job.cancel()
        testScheduler.runCurrent()
        return refreshes
    }
}
