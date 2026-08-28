package org.siloserver.silo.android.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Android battery-policy integration for the long-lived TV control socket. */
object RemoteControlBatteryOptimization {
    private const val PREFERENCES_NAME = "remote_control"
    private const val PROMPT_SHOWN_KEY = "battery_optimization_prompt_shown"

    fun isExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun shouldShowPrompt(context: Context): Boolean =
        !isExempt(context) &&
            !context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(PROMPT_SHOWN_KEY, false)

    fun markPromptShown(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PROMPT_SHOWN_KEY, true)
            .apply()
    }

    /**
     * Opens Android's exemption list. Using the system list instead of
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS avoids the restricted
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission while still taking the
     * user directly to the setting they need to change.
     */
    fun openSettings(context: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val batterySettings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(flags)
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(flags)

        runCatching { context.startActivity(batterySettings) }
            .recoverCatching { context.startActivity(fallback) }
    }
}
