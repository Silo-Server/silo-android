package org.siloserver.silo.common.diagnostics.consent

import java.util.concurrent.atomic.AtomicLong

/**
 * Fail-closed child-profile gate.
 *
 * Diagnostics actions (settings, prompts, capture persistence, upload) require
 * a non-child profile. The gate defaults to ineligible until an async profile
 * lookup positively confirms non-child, and a monotonic generation counter
 * discards stale async results — a slow lookup from a superseded profile
 * switch can never re-enable capture for a child profile.
 */
class DiagnosticsProfileGate(
    private val resolveIsChild: suspend (profileId: String) -> Boolean?,
) {
    @Volatile
    private var eligibleNonChild: Boolean = false

    private val generation = AtomicLong(0)

    /** Synchronous, immediate disarm — call at profile-switch start / sign-out. */
    fun invalidate() {
        generation.incrementAndGet()
        eligibleNonChild = false
    }

    /**
     * Re-resolves eligibility for [activeProfileId]. A null profile (none
     * selected yet) is eligible only when [noProfileIsEligible] — crashes
     * during login/profile selection are account-scoped, so capture there is
     * allowed, but consent management still requires a resolved non-child
     * profile.
     */
    suspend fun reevaluate(activeProfileId: String?, noProfileIsEligible: Boolean = false) {
        val gen = generation.incrementAndGet()
        val eligible = if (activeProfileId == null) {
            noProfileIsEligible
        } else {
            resolveIsChild(activeProfileId) == false
        }
        if (gen != generation.get()) return // superseded meanwhile — drop stale result
        eligibleNonChild = eligible
    }

    fun isEligibleNow(): Boolean = eligibleNonChild
}
