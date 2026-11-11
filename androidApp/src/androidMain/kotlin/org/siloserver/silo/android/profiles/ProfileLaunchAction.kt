package org.siloserver.silo.android.profiles

import org.siloserver.silo.common.profiles.ProfileSelectionPrompt

/** What the phone does about Profile Selection when Silo opens or returns. */
enum class ProfileLaunchAction {
    /** Keep the current profile; Silo is in use again. */
    KeepProfile,

    /** Clear the current profile and show Who's Watching. */
    AskWhoIsWatching,

    /**
     * Clear the current profile and show Change Account: several accounts
     * are saved, so whose account comes before whose profile.
     */
    AskWhichAccount,

    /**
     * The setting asks, but the server cannot be reached and this profile
     * has downloads. Who's Watching cannot load profiles or check a PIN
     * offline, so keep the profile and its downloads usable, and ask once
     * the server is back.
     */
    KeepProfileOffline,
}

/**
 * The phone's launch and return decision. [prompt] is the shared Profile
 * Selection rule; [startsOfflineOnDownloads] says the server cannot be used
 * and the active profile has local downloads.
 */
fun profileLaunchAction(prompt: ProfileSelectionPrompt, startsOfflineOnDownloads: Boolean): ProfileLaunchAction =
    when {
        prompt == ProfileSelectionPrompt.None -> ProfileLaunchAction.KeepProfile
        startsOfflineOnDownloads -> ProfileLaunchAction.KeepProfileOffline
        prompt == ProfileSelectionPrompt.ChangeAccount -> ProfileLaunchAction.AskWhichAccount
        else -> ProfileLaunchAction.AskWhoIsWatching
    }
