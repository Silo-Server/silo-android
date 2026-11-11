package org.siloserver.silo.android.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Starts "Switch Profile" in the navigation host's scope, which outlives the
 * tab screen the request came from. Run from a tab's own scope, opening a
 * title while settings were still being pushed cancelled the switch.
 */
val LocalProfileSwitch = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Starts the profile menu's "Add Profile" in the navigation host's scope:
 * the primary profile's PIN first when it has one, then the new-profile
 * form over the screen the menu was opened from.
 */
val LocalAddProfile = staticCompositionLocalOf<() -> Unit> { {} }
