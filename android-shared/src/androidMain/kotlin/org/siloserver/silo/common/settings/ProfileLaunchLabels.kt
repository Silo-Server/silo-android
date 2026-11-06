package org.siloserver.silo.common.settings

import androidx.annotation.StringRes
import org.siloserver.silo.common.R
import org.siloserver.silo.common.profiles.ProfileLaunchBehavior

/** Profile Selection's choice names, the same words as Roku's. */
@StringRes
fun profileLaunchTitle(behavior: ProfileLaunchBehavior): Int = when (behavior) {
    ProfileLaunchBehavior.Automatic -> R.string.settings_profileLaunch_automatic
    ProfileLaunchBehavior.EveryTime -> R.string.settings_profileLaunch_askEveryLaunch
    ProfileLaunchBehavior.AfterOneHour -> R.string.settings_profileLaunch_afterOneHour
    ProfileLaunchBehavior.AfterTwelveHours -> R.string.settings_profileLaunch_afterTwelveHours
}

/**
 * The line under each Profile Selection choice. Only Automatic names the
 * device, so each app passes its own wording of that line.
 */
@StringRes
fun profileLaunchDescription(behavior: ProfileLaunchBehavior, @StringRes automaticDetail: Int): Int = when (behavior) {
    ProfileLaunchBehavior.Automatic -> automaticDetail
    ProfileLaunchBehavior.EveryTime -> R.string.settings_profileLaunch_askEveryLaunch_detail
    ProfileLaunchBehavior.AfterOneHour -> R.string.settings_profileLaunch_afterOneHour_detail
    ProfileLaunchBehavior.AfterTwelveHours -> R.string.settings_profileLaunch_afterTwelveHours_detail
}
