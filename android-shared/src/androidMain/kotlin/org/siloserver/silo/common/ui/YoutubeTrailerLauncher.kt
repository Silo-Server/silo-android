package org.siloserver.silo.common.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.siloserver.silo.model.catalog.ItemVideo

/**
 * Hands a remote trailer to the installed YouTube app, falling back to the
 * public watch page in a browser when no app accepts the deep link.
 */
fun openYoutubeTrailer(context: Context, video: ItemVideo) {
    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${video.siteKey}"))
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.youtube.com/watch?v=${video.siteKey}"),
    )
    runCatching { context.startActivity(appIntent) }
        .recoverCatching { context.startActivity(webIntent) }
        .onFailure {
            Toast.makeText(context, "No app is available to open this trailer", Toast.LENGTH_SHORT).show()
        }
}
