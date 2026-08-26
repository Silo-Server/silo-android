package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.common.downloads.DownloadStorage

fun scopedLocalDownloadBytes(
    storage: DownloadStorage,
    serverId: String?,
    profileId: String?,
): Long {
    val scopedServerId = serverId?.takeIf { it.isNotBlank() } ?: return 0L
    val scopedProfileId = profileId?.takeIf { it.isNotBlank() } ?: return 0L
    return storage.totalBytesUsed(scopedServerId, scopedProfileId)
}

fun hasLocalDownloadsForScope(
    storage: DownloadStorage,
    serverId: String?,
    profileId: String?,
): Boolean = scopedLocalDownloadBytes(storage, serverId, profileId) > 0L

fun shouldStartOnDownloads(
    hasLocalDownloads: Boolean,
    isDeviceOnline: Boolean,
    canUseServer: Boolean,
): Boolean = hasLocalDownloads && (!isDeviceOnline || !canUseServer)
