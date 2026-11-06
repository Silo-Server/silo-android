package org.siloserver.silo.common.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R

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
    val option: String
        @Composable get() = stringResource(R.string.settings_useProfile)

    /** The row that does it for one switch. */
    val row: String
        @Composable get() = stringResource(R.string.settings_profileSettings_useRow)

    /** The row that does it for every playback setting at once. */
    val resetAllRow: String
        @Composable get() = stringResource(R.string.settings_profileSettings_useAll)

    val confirmTitle: String
        @Composable get() = stringResource(R.string.settings_profileSettings_confirmTitle)

    // Truthful about the local-only settings (picture-in-picture, Force HDR
    // Passthrough, rewind on resume…): they have no profile value, so the
    // action returns them to their defaults.
    val confirmMessage: String
        @Composable get() = stringResource(R.string.settings_profileSettings_confirmMessage)

    val confirmButton: String
        @Composable get() = stringResource(R.string.settings_profileSettings_useAll)

    val footer: String
        @Composable get() = stringResource(R.string.settings_profileSettings_footer)

    /** Accessibility label for the row under [setting]'s switch. */
    @Composable
    fun rowDescription(setting: String): String = stringResource(R.string.settings_profileSettings_rowDescription, setting)

    /**
     * The notice after the reset, as a string resource (for a toast). [landed]
     * is false whenever the server's answer didn't confirm it: offline, a
     * refused delete, or a profile switch mid-reset. The queue is in memory
     * and drops what it can't retry, so the text promises nothing about
     * later; running the reset again is safe.
     */
    @StringRes
    fun notice(landed: Boolean): Int =
        if (landed) R.string.settings_profileSettings_landed else R.string.settings_profileSettings_notConfirmed
}
