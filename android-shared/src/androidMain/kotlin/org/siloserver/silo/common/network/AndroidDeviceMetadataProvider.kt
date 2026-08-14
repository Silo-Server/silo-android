package org.siloserver.silo.common.network

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import org.siloserver.silo.network.SiloDeviceMetadata
import org.siloserver.silo.network.DeviceMetadataProvider
import java.util.UUID

/**
 * @param buildIdentity the app module's build number and release channel. It
 *   is passed in because `android-shared` cannot see either app's
 *   `BuildConfig`, and because the installed `versionCode` is the
 *   form-factor-doubled release code rather than CI's build counter. An
 *   unstamped build reports its build as absent rather than as build zero: the
 *   server treats the build as an opaque string, so a placeholder here would
 *   surface verbatim as "(0)" in admin Activity, and the channel already says
 *   `dev`.
 */
class AndroidDeviceMetadataProvider(
    private val context: Context,
    private val platform: String,
    private val buildIdentity: SiloClientBuildIdentity,
) : DeviceMetadataProvider {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cachedClientName: String by lazy { clientNameFor(platform) }
    private val cachedClientVersion: String? by lazy { appVersionName() }

    override suspend fun current(): SiloDeviceMetadata {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { generated ->
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        }
        val model = listOfNotNull(Build.MANUFACTURER?.trim(), Build.MODEL?.trim())
            .distinct()
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android Device" }
        return SiloDeviceMetadata(
            id = deviceId,
            name = model,
            platform = platform,
            clientName = cachedClientName,
            clientVersion = cachedClientVersion,
            clientBuild = buildIdentity.reportedBuildNumber,
            clientChannel = buildIdentity.reportedChannel,
        )
    }

    private fun clientNameFor(platform: String): String =
        when (platform) {
            "android-tv" -> "Silo Android TV"
            "android" -> "Silo Android"
            else -> "Silo Android"
        }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String? =
        runCatching<PackageInfo> {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
            ?.versionName
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val PREFS_NAME = "silo_device_metadata"
        const val KEY_DEVICE_ID = "device_id"
    }
}
