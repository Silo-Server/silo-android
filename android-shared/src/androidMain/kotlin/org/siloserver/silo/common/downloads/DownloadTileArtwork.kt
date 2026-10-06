package org.siloserver.silo.common.downloads

import org.siloserver.silo.model.download.DownloadMediaType
import org.siloserver.silo.model.download.DownloadSidecar
import java.io.File

/** Image source and blur-up placeholder for a download's 2:3 tile. */
data class DownloadTileArtwork(val url: String?, val thumbhash: String?)

/**
 * The 2:3 tile artwork for one download. A movie uses its saved poster, else
 * the catalog [DownloadSidecar.posterUrl]. An episode uses the saved series
 * poster, else the catalog URL (already the series poster): its own saved
 * `poster` is a 16:9 still and never goes in a 2:3 tile. Saved paths are
 * checked on disk, so call off the main thread.
 */
fun DownloadSidecar.tileArtwork(isFile: (String) -> Boolean = ::isSavedArtworkFile): DownloadTileArtwork =
    listOf(this).tileArtwork(isFile)

/**
 * The tile artwork for a group of downloads (a series or season row): any
 * member's saved series poster, then (movies only) any member's saved poster,
 * then the first catalog poster URL.
 */
fun List<DownloadSidecar>.tileArtwork(isFile: (String) -> Boolean = ::isSavedArtworkFile): DownloadTileArtwork {
    val saved = firstNotNullOfOrNull { sidecar -> sidecar.offlineSeriesPosterPath?.takeIf(isFile) }
        ?: firstNotNullOfOrNull { sidecar ->
            sidecar.offlinePosterPath?.takeIf { !sidecar.isEpisodeDownload() && isFile(it) }
        }
    return DownloadTileArtwork(
        url = saved?.let(::localArtworkUrl) ?: firstNotNullOfOrNull { it.posterUrl },
        thumbhash = firstNotNullOfOrNull { it.seriesPosterThumbhash } ?: firstNotNullOfOrNull { it.posterThumbhash },
    )
}

/**
 * True for a TV episode download. Rows written before `mediaType` was stored
 * count as episodes when they carry a series title, as the Downloads tab does.
 */
fun DownloadSidecar.isEpisodeDownload(): Boolean =
    when (DownloadMediaType.fromWire(mediaType)) {
        DownloadMediaType.TvShow -> true
        DownloadMediaType.Unknown -> !seriesTitle.isNullOrBlank()
        else -> false
    }

private fun isSavedArtworkFile(path: String): Boolean = File(path).isFile

private fun localArtworkUrl(path: String): String = "file://$path"
