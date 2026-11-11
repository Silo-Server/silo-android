package org.siloserver.silo.android.profiles

import org.siloserver.silo.common.profiles.ProfileSelectionPrompt
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.network.ApiResult

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

/**
 * The profile a phone launch opens without the picker when none is
 * remembered: the account's only profile, when it has no PIN. Null shows the
 * picker.
 *
 * [fresh] is the server's current list. When the server answered with a
 * list, that list decides, so a profile that gained a PIN or a sibling on
 * another device goes to the picker, as it does on TV. When it answered with
 * an error, the picker explains it. When it did not answer ([fresh] is a
 * network error, or null once the wait ran out), [cachedOnlyProfileId], from
 * the account's cached list, decides, so an unreachable server never draws a
 * one-tile picker.
 */
fun launchOnlyProfileId(fresh: ApiResult<List<Profile>>?, cachedOnlyProfileId: String?): String? = when (fresh) {
    is ApiResult.Success -> fresh.data.singleOrNull()?.takeIf { !it.hasPin }?.id
    is ApiResult.Error -> null
    is ApiResult.NetworkError, null -> cachedOnlyProfileId
}
