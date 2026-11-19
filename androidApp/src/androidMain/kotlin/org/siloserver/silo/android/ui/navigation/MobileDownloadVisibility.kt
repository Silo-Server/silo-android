package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.common.downloads.DownloadStorage

/** Bytes downloaded by the active account's [profileId]; [accountId] null or blank = before accounts. */
fun scopedLocalDownloadBytes(
    storage: DownloadStorage,
    serverId: String?,
    accountId: String?,
    profileId: String?,
): Long {
    val scopedServerId = serverId?.takeIf { it.isNotBlank() } ?: return 0L
    val scopedProfileId = profileId?.takeIf { it.isNotBlank() } ?: return 0L
    return storage.totalBytesUsed(scopedServerId, accountId.orEmpty(), scopedProfileId)
}

fun hasLocalDownloadsForScope(
    storage: DownloadStorage,
    serverId: String?,
    accountId: String?,
    profileId: String?,
): Boolean = scopedLocalDownloadBytes(storage, serverId, accountId, profileId) > 0L

fun shouldShowDownloadsTab(
    serverRecordCount: Int,
    activeScopeLocalBytes: Long,
): Boolean = serverRecordCount > 0 || activeScopeLocalBytes > 0L

fun shouldStartOnDownloads(
    hasLocalDownloads: Boolean,
    isDeviceOnline: Boolean,
    canUseServer: Boolean,
): Boolean = hasLocalDownloads && (!isDeviceOnline || !canUseServer)
