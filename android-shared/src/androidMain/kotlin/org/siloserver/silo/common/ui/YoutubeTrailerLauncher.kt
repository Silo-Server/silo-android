package org.siloserver.silo.common.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.siloserver.silo.common.player.TvModeDetector
import org.siloserver.silo.model.catalog.ItemVideo

private const val YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv"

/**
 * Hands a remote trailer to the installed YouTube app, falling back to the
 * public watch page in a browser when no app accepts the deep link.
 *
 * YouTube for Android TV opens a `vnd.youtube:` link on its home screen
 * without starting the video, so TVs give that app the watch page link
 * first. Without it, TVs use the same order as phones.
 */
fun openYoutubeTrailer(context: Context, video: ItemVideo) {
    val watchPage = Uri.parse("https://www.youtube.com/watch?v=${video.siteKey}")
    val intents = buildList {
        if (TvModeDetector.isTv(context)) {
            add(Intent(Intent.ACTION_VIEW, watchPage).setPackage(YOUTUBE_TV_PACKAGE))
        }
        add(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${video.siteKey}")))
        add(Intent(Intent.ACTION_VIEW, watchPage))
    }
    val opened = intents.any { intent -> runCatching { context.startActivity(intent) }.isSuccess }
    if (!opened) {
        Toast.makeText(context, "No app is available to open this trailer", Toast.LENGTH_SHORT).show()
    }
}
