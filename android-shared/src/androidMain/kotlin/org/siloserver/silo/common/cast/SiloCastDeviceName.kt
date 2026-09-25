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

    /**
     * [name] + [suffix] as a DNS-SD service instance name, which may not
     * exceed 63 UTF-8 bytes: NSD refuses to register a longer one. Cuts
     * [name] on a code point boundary; the TXT `name` keeps it whole.
     */
    fun instanceName(name: String, suffix: String = ""): String {
        val budget = MAX_INSTANCE_NAME_BYTES - suffix.utf8Size()
        if (name.utf8Size() <= budget) return name + suffix
        var end = 0
        var used = 0
        while (end < name.length) {
            val next = name.offsetByCodePoints(end, 1)
            val size = name.substring(end, next).utf8Size()
            if (used + size > budget) break
            used += size
            end = next
        }
        return name.substring(0, end).trimEnd() + suffix
    }

    private const val MAX_INSTANCE_NAME_BYTES = 63

    private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size
}
