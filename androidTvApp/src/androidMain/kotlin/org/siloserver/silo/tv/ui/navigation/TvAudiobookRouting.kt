package org.siloserver.silo.tv.ui.navigation

import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.common.player.video.VideoPlayerRouteArgs

/**
 * Decide the playback route for a Play action on the TV detail screen.
 *
 * Audiobook-type items ([isAudiobookItemType]) open the dedicated
 * [TvRoute.AudiobookPlayer]; everything else opens the video [TvRoute.Player].
 * Pure (returns the route string, no Android/Nav types) so the decision is
 * unit-tested independently of navigation — see `TvAudiobookRoutingTest`.
 */
fun tvPlayDestinationFor(
    itemType: String?,
    contentId: String,
    fileId: Int?,
): String =
    tvPlayDestinationFor(
        itemType = itemType,
        contentId = contentId,
        fileId = fileId,
        resumePositionSeconds = null,
    )

fun tvPlayDestinationFor(
    itemType: String?,
    contentId: String,
    fileId: Int?,
    resumePositionSeconds: Double?,
    audioTrackIndex: Int? = null,
    subtitleTrackIndex: Int? = null,
    quality: String? = null,
): String =
    if (isAudiobookItemType(itemType)) {
        // Audiobooks have no audio/subtitle track selection — ignore the indexes.
        TvRoute.AudiobookPlayer(
            contentId = contentId,
            fileId = fileId,
            startPositionSeconds = resumePositionSeconds,
        ).route
    } else {
        TvRoute.Player(
            contentId = contentId,
            fileId = fileId,
            quality = quality,
            resumePositionSeconds = resumePositionSeconds,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
        ).route
    }

/** Parsed, bounded playback parameters accepted from the app-owned `silo://play` link. */
internal data class TvPlaybackDeepLinkArgs(
    val fileId: Int? = null,
    val quality: String? = null,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
)

internal fun parseTvPlaybackDeepLinkArgs(
    queryParameter: (String) -> String?,
): TvPlaybackDeepLinkArgs = TvPlaybackDeepLinkArgs(
    fileId = queryParameter("fileId")?.toIntOrNull()?.takeIf { it > 0 },
    quality = VideoPlayerRouteArgs.normalizeQuality(queryParameter(VideoPlayerRouteArgs.QUALITY)),
    audioTrackIndex = queryParameter("audioTrackIndex")?.toIntOrNull()?.takeIf { it >= 0 },
    subtitleTrackIndex = queryParameter("subtitleTrackIndex")?.toIntOrNull()?.takeIf { it >= -1 },
)
