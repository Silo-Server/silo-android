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

    /**
     * A DNS-SD service name holds at most 63 UTF-8 bytes: Android 14 cuts a
     * longer one, and older releases refuse to register it, which would hide
     * the TV from phones. One cut here keeps every place showing the same name.
     */
    private const val MAX_UTF8_BYTES = 63

    fun current(context: Context): String {
        // DEVICE_NAME arrived in API 25; older TVs use the generic name.
        val named = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            runCatching {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()
        } else {
            null
        }
        return named?.let(::fitted)?.takeIf { it.isNotEmpty() } ?: FALLBACK
    }

    /** [name] trimmed and cut to [MAX_UTF8_BYTES] without splitting a character. */
    internal fun fitted(name: String): String {
        val trimmed = name.trim()
        var bytes = 0
        var end = 0
        while (end < trimmed.length) {
            val codePoint = trimmed.codePointAt(end)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > MAX_UTF8_BYTES) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return trimmed.substring(0, end).trimEnd()
    }
}
