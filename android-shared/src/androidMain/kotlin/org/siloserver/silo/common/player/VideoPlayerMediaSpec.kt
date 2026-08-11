package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.model.playback.isLocalDownloadedSubtitle
import org.siloserver.silo.playback.canonicalSubtitleCodecFamily
import org.siloserver.silo.playback.canonicalSubtitleLanguage
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired

/**
 * Subtitle artifacts attached to one Media3 media mount.
 *
 * Neutral v3 keeps the complete server inventory in UI state so the picker can
 * display every choice. That inventory is not a preload list: attaching every
 * inventory URL to the MediaItem makes Media3 open every sidecar eagerly and a
 * slow duplicate request can block video preparation. The active plan owns the
 * server artifact, while [subtitleIdentity] owns any client-local artifact.
 * Exactly one external artifact may be attached: preloading every downloaded
 * row recreates the same fan-out under different indexes.
 *
 * A missing plan is the legacy/offline path, where the supplied list remains
 * the media-mount contract.
 */
fun subtitlesForVideoMediaMount(
    subtitles: List<PlayerSubtitleInfo>,
    playbackPlan: PlaybackExecutionPlan?,
    subtitleIdentity: SubtitleIdentity,
): List<PlayerSubtitleInfo> {
    if (playbackPlan == null) return subtitles

    val selected = when (subtitleIdentity) {
        is SubtitleIdentity.ServerSidecar -> {
            subtitleIdentity.serverIndex
                .takeIf { it == playbackPlan.selectedTracks.subtitleIndex }
                ?.let { serverIndex ->
                    subtitles.singleOrNull { subtitle ->
                        subtitle.index == serverIndex && !subtitle.isLocalDownloadedSubtitle()
                    }
                }
        }
        is SubtitleIdentity.Downloaded -> subtitles.singleOrNull { subtitle ->
            subtitle.isLocalDownloadedSubtitle() &&
                subtitle.downloadId == subtitleIdentity.downloadId
        }
        is SubtitleIdentity.LocalMedia3 -> subtitles.selectLocalMedia3Subtitle(
            subtitleIdentity.media,
        )
        SubtitleIdentity.Off,
        is SubtitleIdentity.ServerBurnIn,
        is SubtitleIdentity.Embedded,
        -> null
    }
    return listOfNotNull(selected)
}

private fun List<PlayerSubtitleInfo>.selectLocalMedia3Subtitle(
    identity: SubtitleMediaIdentity,
): PlayerSubtitleInfo? {
    identity.trackId?.let { trackId ->
        filter { subtitle ->
            subtitle.serverTrackId == null &&
                subtitle.serverDelivery == null &&
                subtitle.mediaTrackId == trackId
        }.singleOrNull()?.let { return it }
    }
    return filter { subtitle ->
        subtitle.serverTrackId == null &&
            subtitle.serverDelivery == null &&
            subtitle.matchesLocalMediaIdentity(identity)
    }.singleOrNull()
}

private fun PlayerSubtitleInfo.matchesLocalMediaIdentity(
    identity: SubtitleMediaIdentity,
): Boolean {
    val comparisons = listOfNotNull(
        identity.label?.let { expected ->
            (catalogLabel ?: label)?.trim()?.equals(expected.trim(), ignoreCase = true) == true
        },
        identity.language?.let { expected ->
            canonicalSubtitleLanguage(language) == canonicalSubtitleLanguage(expected)
        },
        identity.codecFamily?.let { expected ->
            canonicalSubtitleCodecFamily(codec ?: subtitleCodecFromUrl(url)) ==
                canonicalSubtitleCodecFamily(expected)
        },
        identity.forced?.let { expected -> forced == expected },
        identity.hearingImpaired?.let { expected ->
            subtitleLabelIndicatesHearingImpaired(catalogLabel ?: label) == expected
        },
    )
    return comparisons.isNotEmpty() && comparisons.all { it }
}

private fun subtitleCodecFromUrl(url: String): String? = url
    .substringBefore('?')
    .substringBefore('#')
    .substringAfterLast('/')
    .substringAfterLast('.', "")
    .takeIf(String::isNotBlank)

data class VideoPlayerMediaSpec(
    /**
     * Catalog identity of what is playing, carried onto the MediaItem for
     * media-session identity and playback diagnostics.
     */
    val contentId: String? = null,
    val streamUrl: String,
    val playMethod: PlayMethod,
    val delivery: PlaybackDelivery? = null,
    val serverUrl: String,
    val container: String? = null,
    val subtitles: List<PlayerSubtitleInfo> = emptyList(),
    val title: String? = null,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val startPositionSeconds: Double = 0.0,
    val timelineOffsetSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val audioPassthroughCodecs: List<String> = emptyList(),
    val requestHeaders: Map<String, String> = emptyMap(),
    val expectedDynamicRange: String? = null,
    val expectedColorRange: String? = null,
    val transformations: List<String> = emptyList(),
    val runtimeCorrections: List<String> = emptyList(),
) {
    val startPositionMs: Long
        get() {
            val seconds = if (startPositionSeconds.isFinite()) startPositionSeconds else 0.0
            return (seconds * 1000.0).toLong().coerceAtLeast(0L)
        }

    val durationMs: Long?
        get() {
            val seconds = durationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return null
            return (seconds * 1000.0).toLong().coerceAtLeast(1L)
        }
}
