package org.siloserver.silo.tv.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.siloserver.silo.tv.cast.TvSiloCastReceiverPolicy.Admission
import org.siloserver.silo.tv.cast.TvSiloCastReceiverPolicy.ControllerSummary

/** Several controllers on one TV; the same cases as silo-apple's SiloControlMultiControllerTests. */
class TvSiloCastReceiverPolicyTest {
    private val policy = TvSiloCastReceiverPolicy
    private var nextId = 0

    private fun summary(
        sequence: Long,
        hello: Boolean = true,
        passive: Boolean = false,
        authorized: Boolean = true,
        handingOff: Boolean = false,
    ) = ControllerSummary("c${nextId++}", sequence, hello, passive, authorized, handingOff)

    @Test
    fun admitsBelowTheCap() {
        val existing = (0 until policy.MAX_CONTROLLERS - 1).map { summary(it.toLong()) }
        assertEquals(Admission.Admit, policy.admission(existing))
    }

    @Test
    fun atTheCapEvictsTheOldestConnectionThatNeverSaidHello() {
        val existing = (0 until policy.MAX_CONTROLLERS).map { summary(it.toLong(), passive = true) }.toMutableList()
        val silentOld = summary(-2, hello = false)
        existing[3] = summary(99, hello = false)
        existing[5] = silentOld
        assertEquals(Admission.AdmitEvicting(silentOld.id), policy.admission(existing))
    }

    @Test
    fun atTheCapEvictsAnIdleCrossServerControllerBeforeAPassiveOne() {
        val existing = (0 until policy.MAX_CONTROLLERS).map { summary(it.toLong(), passive = true) }.toMutableList()
        val idle = summary(40, authorized = false)
        existing[2] = idle
        existing[4] = summary(10, authorized = false, handingOff = true)
        assertEquals(Admission.AdmitEvicting(idle.id), policy.admission(existing))
    }

    @Test
    fun atTheCapEvictsTheOldestPassiveControllerNext() {
        val existing = (0 until policy.MAX_CONTROLLERS).map { summary(it.toLong()) }.toMutableList()
        val passiveOld = summary(20, passive = true)
        existing[1] = summary(50, passive = true)
        existing[6] = passiveOld
        assertEquals(Admission.AdmitEvicting(passiveOld.id), policy.admission(existing))
    }

    @Test
    fun atTheCapNeverEvictsAControllerMidHandoffOrAnActiveOne() {
        val existing = (0 until policy.MAX_CONTROLLERS).map { summary(it.toLong()) }.toMutableList()
        existing[0] = summary(0, authorized = false, handingOff = true)
        existing[1] = summary(1, passive = true, handingOff = true)
        assertEquals(Admission.Refuse, policy.admission(existing))
    }

    @Test
    fun helloReplacesOnlyTheSameDevicesOtherConnections() {
        val replaced = policy.connectionsReplaced(
            deviceId = "phone-a",
            connection = "mine",
            existing = listOf("mine" to "phone-a", "old-mine" to "phone-a", "other" to "phone-b", "pre-hello" to null),
        )
        assertEquals(listOf("old-mine"), replaced)
        assertTrue(policy.connectionsReplaced("", "mine", listOf("x" to "")).isEmpty())
    }

    @Test
    fun aLaunchNeedsReadinessForTheActiveGeneration() {
        assertTrue(policy.canLaunchBorrowed("g1", "g1"))
        assertFalse(policy.canLaunchBorrowed("g1", "g2"))
        assertFalse(policy.canLaunchBorrowed(null, "g1"))
        assertFalse(policy.canLaunchBorrowed("g1", null))
    }

    @Test
    fun reuseNeedsTheSameConnectionOrTheIssuedToken() {
        fun reuse(matches: Boolean = true, identityConnection: String? = "conn-1", issued: String? = "tok", offered: String? = null) =
            policy.mayReuseIdentity(matches, identityConnection, "conn-2", issued, offered)
        assertTrue(policy.mayReuseIdentity(true, "conn-1", "conn-1", null, null))
        assertFalse("a copied device ID alone proves nothing", reuse())
        assertTrue(reuse(offered = "tok"))
        assertFalse(reuse(offered = "tok-guess"))
        assertFalse(reuse(issued = null, offered = "tok"))
        assertFalse(reuse(matches = false, offered = "tok"))
    }

    @Test
    fun asksBeforeReplacingAnotherProfilesTitleOnly() {
        assertTrue(policy.needsReplaceConfirmation(true, false, false, true))
        assertFalse(policy.needsReplaceConfirmation(false, false, false, true))
        assertFalse(policy.needsReplaceConfirmation(true, true, false, true))
        assertFalse(policy.needsReplaceConfirmation(true, false, true, true))
        // Older phones can't show the question, so they keep newest-launch-wins.
        assertFalse(policy.needsReplaceConfirmation(true, false, false, false))
    }

    @Test
    fun aConfirmationCoversOnlyTheTitleTheUserAgreedToReplace() {
        assertTrue(policy.confirmationCovers("oak", true, "oak"))
        assertFalse(policy.confirmationCovers("bobs-film", true, "oak"))
        assertFalse(policy.confirmationCovers("oak", false, "oak"))
    }

    @Test
    fun reauthorizationKeepsTheOwnerAndQuietlyDropsAnEndedLender() {
        // Same-server controller: still authorized whatever the identity does.
        assertEquals(
            TvSiloCastReceiverPolicy.Reauthorization(isAuthorized = true, clearsReadiness = false, notifyServerMismatch = false),
            policy.reauthorize(wasAuthorized = true, matchesEffectiveServer = true, readyGeneration = null, activeGeneration = "g1"),
        )
        // Cross-server controller whose own identity is still active.
        assertTrue(policy.reauthorize(true, false, "g1", "g1").isAuthorized)
        // Its identity ended (title over, idle end): unauthorized, readiness cleared, no misleading error.
        val ended = policy.reauthorize(true, false, "g1", null)
        assertFalse(ended.isAuthorized)
        assertTrue(ended.clearsReadiness)
        assertFalse(ended.notifyServerMismatch)
        // Another controller's cross-server identity took over under a same-server one.
        val displaced = policy.reauthorize(true, false, null, "g2")
        assertFalse(displaced.isAuthorized)
        assertTrue(displaced.notifyServerMismatch)
    }

    @Test
    fun standbyNamesTheNewestWaitingControllerAndCountsTheRest() {
        val older = summary(1)
        val newest = summary(5)
        val passive = summary(9, passive = true)
        val crossServer = summary(7, authorized = false)
        assertEquals(
            TvSiloCastReceiverPolicy.StandbyPick(newest.id, otherControllerCount = 1),
            policy.standbyPick(listOf(older, newest, passive, crossServer)),
        )
        assertNull("a passive integration never puts up standby", policy.standbyPick(listOf(passive)))
    }

    @Test
    fun resumeTokensAreLongAndUnique() {
        val first = policy.makeResumeToken()
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(first, policy.makeResumeToken())
    }

    @Test
    fun commandBudgetAllowsABurstThenRefills() {
        val budget = TvSiloCastReceiverPolicy.CommandBudget()
        val start = 1_000_000L
        repeat(TvSiloCastReceiverPolicy.CommandBudget.BURST.toInt()) { assertTrue(budget.allow(start)) }
        assertFalse(budget.allow(start))
        // 20 per second refills one token every 50 ms.
        assertTrue(budget.allow(start + 50))
        assertFalse(budget.allow(start + 50))
    }
}
