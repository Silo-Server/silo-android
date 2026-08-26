package org.siloserver.silo.android

import android.content.Context
import org.siloserver.silo.common.network.androidClientFamily
import org.siloserver.silo.model.settings.SiloClientFamily

/**
 * The one place the phone app classifies itself as `mobile` vs `tablet` for
 * synced UI customization.
 *
 * Always resolve this from the APPLICATION context. In multi-window an
 * Activity carries its own override [android.content.res.Configuration] whose
 * `smallestScreenWidthDp` can differ from the application's, so an
 * Activity-derived answer would disagree with the value
 * `DefaultUiCustomizationStore`'s `familyProvider` reads on every write —
 * making `reclassifyClientFamily()` fire on a change the store cannot see, and
 * stay silent on one it can.
 */
fun mobileUiCustomizationFamily(context: Context): SiloClientFamily {
    val wire = androidClientFamily(
        platform = "android",
        smallestScreenWidthDp = context.resources.configuration.smallestScreenWidthDp,
    )
    return SiloClientFamily.entries.first { it.wire == wire }
}
