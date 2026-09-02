package org.siloserver.silo.common.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build

/** The Activity that owns this context, or null for an application/service context. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * The id of the display owning this context's Activity, or null when the
 * context has no Activity or the platform predates per-context displays.
 */
fun Context.playbackDisplayId(): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val activity = findActivity() ?: return null
    return runCatching { activity.display?.displayId }.getOrNull()
}
