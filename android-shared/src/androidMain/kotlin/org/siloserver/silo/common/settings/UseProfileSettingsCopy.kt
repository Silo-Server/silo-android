package org.siloserver.silo.common.settings

/**
 * The words for going back to the profile's playback settings, shared by the
 * phone and TV settings so the two say the same thing.
 */
object UseProfileSettingsCopy {
    /**
     * The first option of every picker for a setting the profile also holds,
     * and the action under such a switch. Choosing it clears this device's
     * value.
     */
    const val OPTION = "Use profile setting"

    /** The row that does it for one switch. */
    const val ROW = "Use Profile Setting"

    /** The row that does it for every playback setting at once. */
    const val RESET_ALL_ROW = "Use Profile Settings"

    const val CONFIRM_TITLE = "Use your profile's settings on this device?"

    // Truthful about the local-only settings (picture-in-picture, Force HDR
    // Passthrough, rewind on resume…): they have no profile value, so the
    // action returns them to their defaults.
    const val CONFIRM_MESSAGE =
        "This removes the settings changed on this device. Settings that only apply to this " +
            "device go back to their defaults."

    const val CONFIRM_BUTTON = "Use Profile Settings"

    const val FOOTER =
        "Goes back to your profile's playback settings on this device. Settings that only apply " +
            "to this device go back to their defaults."

    /** Accessibility label for the row under [setting]'s switch. */
    fun rowDescription(setting: String): String = "Use profile setting for $setting"

    /**
     * The notice after the reset. [landed] is false whenever the server's
     * answer didn't confirm it: offline, a refused delete, or a profile switch
     * mid-reset. The queue is in memory and drops what it can't retry, so the
     * text promises nothing about later; running the reset again is safe.
     */
    fun notice(landed: Boolean): String =
        if (landed) {
            "Now using your profile's settings."
        } else {
            "Couldn't confirm the change with the server. Try again later."
        }
}
