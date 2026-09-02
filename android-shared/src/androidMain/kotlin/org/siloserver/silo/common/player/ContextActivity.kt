package org.siloserver.silo.common.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** The Activity that owns this context, or null for an application/service context. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
