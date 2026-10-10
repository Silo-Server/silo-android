package org.siloserver.silo.android.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Starts "Switch Profile" in the navigation host's scope, which outlives the
 * tab screen the request came from. Run from a tab's own scope, opening a
 * title while settings were still being pushed cancelled the switch.
 */
val LocalProfileSwitch = staticCompositionLocalOf<() -> Unit> { {} }
