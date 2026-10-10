package org.siloserver.silo.common.ui.marquee

/**
 * What a finished sign-in tells the profile picker that follows it.
 */
object SignInHandoff {
    /**
     * Set by a completed sign-in, including setup from a nearby phone: a
     * household with exactly one profile and no PIN goes straight to Home
     * instead of a one-person picker. The picker clears it on first read.
     */
    @Volatile
    var skipsSingleProfilePicker: Boolean = false

    fun consumeSkipsSingleProfilePicker(): Boolean = skipsSingleProfilePicker.also { skipsSingleProfilePicker = false }
}
