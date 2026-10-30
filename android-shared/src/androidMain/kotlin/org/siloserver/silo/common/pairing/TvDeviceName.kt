package org.siloserver.silo.common.pairing

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The name the person gave this TV in Android TV settings (Device
 * Preferences > About > Device name), the one the TV shows on its own screens.
 * Every place the TV names itself to a server or a phone uses it, so a
 * browser approval page, a phone card and the TV agree, as Apple TV's
 * `UIDevice.current.name` does.
 */
object TvDeviceName {
    const val FALLBACK = "Android TV"

    fun current(context: Context): String {
        // DEVICE_NAME arrived in API 25; older TVs use the generic name.
        val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            runCatching {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()
        } else {
            null
        }
        return named?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK
    }
}
