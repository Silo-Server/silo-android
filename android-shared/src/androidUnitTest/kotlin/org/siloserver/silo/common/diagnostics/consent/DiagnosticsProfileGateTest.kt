package org.siloserver.silo.common.diagnostics.consent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsProfileGateTest {

    @Test
    fun `starts ineligible`() {
        val gate = DiagnosticsProfileGate { false }
        assertFalse(gate.isEligibleNow(), "gate must fail closed before any resolution")
    }

    @Test
    fun `reevaluate with non-child resolution becomes eligible`() = runTest {
        val gate = DiagnosticsProfileGate { false }
        gate.reevaluate("prof_1")
        assertTrue(gate.isEligibleNow())
    }

    @Test
    fun `child or unknown resolution stays ineligible`() = runTest {
        val childGate = DiagnosticsProfileGate { true }
        childGate.reevaluate("prof_child")
        assertFalse(childGate.isEligibleNow())

        val unknownGate = DiagnosticsProfileGate { null }
        unknownGate.reevaluate("prof_unknown")
        assertFalse(unknownGate.isEligibleNow(), "unresolvable profile must fail closed")
    }

    @Test
    fun `null profile is eligible only when explicitly allowed`() = runTest {
        val gate = DiagnosticsProfileGate { false }
        gate.reevaluate(null)
        assertFalse(gate.isEligibleNow())
        gate.reevaluate(null, noProfileIsEligible = true)
        assertTrue(gate.isEligibleNow())
    }

    @Test
    fun `invalidate is immediately ineligible`() = runTest {
        val gate = DiagnosticsProfileGate { false }
        gate.reevaluate("prof_1")
        assertTrue(gate.isEligibleNow())

        gate.invalidate()
        assertFalse(gate.isEligibleNow())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `stale slow resolution cannot re-enable after invalidate`() = runTest {
        val slowResolver = CompletableDeferred<Boolean?>()
        val gate = DiagnosticsProfileGate { slowResolver.await() }

        val reevaluation = launch { gate.reevaluate("prof_1") }
        runCurrent() // resolver is now suspended mid-reevaluate

        gate.invalidate() // profile switch begins while the lookup is in flight
        slowResolver.complete(false) // stale answer: "non-child" for the OLD profile
        reevaluation.join()

        assertFalse(
            gate.isEligibleNow(),
            "a superseded resolution must be discarded — it could re-enable capture for a child profile",
        )
    }
}
