package org.siloserver.silo.android.ui.screens.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.downloads.DownloadOpenTarget
import java.io.File

internal fun openDownloadInExternalApp(context: Context, item: DownloadItem): Boolean {
    val target = DownloadOpenTarget.from(
        isComplete = item.isComplete,
        localUri = item.localUri,
        displayName = item.displayName,
        container = item.container,
    ) ?: return false
    return openDownloadTargetInExternalApp(context, target)
}

internal fun openDownloadTargetInExternalApp(context: Context, target: DownloadOpenTarget): Boolean {
    val intent = externalDownloadOpenIntent(context, target)
    return runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(CommonR.string.downloads_action_openWith)))
        true
    }.getOrDefault(false)
}

internal fun externalDownloadOpenIntent(
    context: Context,
    target: DownloadOpenTarget,
): Intent {
    val uri = readableDownloadUri(context, target.uriString)
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, target.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun readableDownloadUri(context: Context, uriString: String): Uri {
    val uri = Uri.parse(uriString)
    if (uri.scheme != "file") return uri
    val path = uri.path?.takeIf { it.isNotBlank() } ?: return uri
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(path),
    )
}
