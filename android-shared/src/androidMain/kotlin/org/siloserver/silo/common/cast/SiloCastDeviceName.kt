package org.siloserver.silo.common.cast

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The name the owner gave this device ("Living Room", "Alex's Pixel"), which
 * the other side of Remote Control shows in its picker, standby screen and
 * "From …" notice. Apple peers send `UIDevice.current.name` for the same
 * slot. The model ("SHIELD Android TV") is only the fallback for a device
 * that was never named.
 */
object SiloCastDeviceName {
    fun resolve(context: Context, fallback: String): String {
        val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            runCatching {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()
        } else {
            null
        }
        return named?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallback
    }
}
