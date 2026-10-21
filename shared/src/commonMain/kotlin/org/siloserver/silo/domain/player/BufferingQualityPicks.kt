package org.siloserver.silo.domain.player

/**
 * The viewer's picks on one screen (a quality from the menu, a version, the
 * phone's Wi-Fi/cellular quality pair), so that only the latest pick settles
 * the controller's pending pick. A pick can be queued behind a replan and
 * replaced by a newer one: the older pick's refusal, or its plan's adoption
 * while the newer one waits, leaves the newer one pending.
 */
class BufferingQualityPicks(private val controller: BufferingQualityController) {
    /** One pick. [matches] recognizes its plan's key; [outstanding] says its request is still on the way. */
    class Pick internal constructor(
        internal val matches: (BufferingQualityKey) -> Boolean,
        internal val outstanding: () -> Boolean,
    )

    private var latest: Pick? = null

    /** A pick was sent: any pill hides and the controller holds until this pick settles. */
    fun sent(matches: (BufferingQualityKey) -> Boolean, outstanding: () -> Boolean): Pick =
        Pick(matches, outstanding).also {
            latest = it
            controller.onQualityPickSent()
        }

    /** [pick] was refused. Only the latest pick's refusal counts. */
    fun refused(pick: Pick?) {
        if (pick == null || pick !== latest) return
        latest = null
        controller.onQualityPickRefused()
    }

    /**
     * Whether a pick still waits once a plan at [key] is adopted: the latest
     * pick is not this plan's and its request is still on the way. A pick
     * that no longer waits is forgotten.
     */
    fun stillPendingAfter(key: BufferingQualityKey): Boolean {
        val pick = latest ?: return false
        val waits = !pick.matches(key) && pick.outstanding()
        if (!waits) latest = null
        return waits
    }

    /** Different content, or an exit. */
    fun clear() {
        latest = null
    }
}
