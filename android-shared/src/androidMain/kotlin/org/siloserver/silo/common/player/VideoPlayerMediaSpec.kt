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
import org.siloserver.silo.playback.isBitmapSubtitleCodecFamily
import org.siloserver.silo.playback.isClientMountableBitmapCodecFamily
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired

/** Mounts only the selected artifact. Explicit embedded decisions mount none. */
fun subtitlesForVideoMediaMount(
    subtitles: List<PlayerSubtitleInfo>,
    playbackPlan: PlaybackExecutionPlan?,
    subtitleIdentity: SubtitleIdentity,
    preferMuxedTracks: Boolean = false,
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
                ?.takeUnless { row ->
                    preferMuxedTracks && row.isMuxedInDirectPlayStream(playbackPlan)
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

/**
 * True when this inventory row describes a track that is muxed into the
 * stream Media3 is playing AND the client can render that track from the
 * stream itself, so no server artifact needs attaching for it.
 *
 * Only the untouched original carries the file's own tracks; every remux /
 * transcode delivery drops or rewrites them, and there the sidecar is the only
 * way to get the subtitle. Bitmap families the client cannot decode in-stream
 * are excluded too — for those the artifact (or burn-in) is the real path.
 */
internal fun PlayerSubtitleInfo.isMuxedInDirectPlayStream(plan: PlaybackExecutionPlan): Boolean {
    if (plan.delivery != PlaybackDelivery.ORIGINAL_HTTP) return false
    // Explicit server sidecars remain sidecars; only a selected embedded decision bypasses extraction.
    if (serverDelivery != null) return nativeContainerTrackId != null
    val embedded = catalogSource?.trim()?.equals("embedded", ignoreCase = true) == true ||
        (catalogSource == null && source?.trim()?.equals("embedded", ignoreCase = true) == true)
    if (!embedded) return false
    val family = canonicalSubtitleCodecFamily(codec ?: subtitleCodecFromUrl(url))
    return !isBitmapSubtitleCodecFamily(family) || isClientMountableBitmapCodecFamily(family)
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
    /**
     * Delivery-scoped validated claims the plan relies on
     * (for example [org.siloserver.silo.model.playback.CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM]),
     * derived from the plan's decision reason. Drives renderer decoder routing.
     */
    val activeClaims: List<String> = emptyList(),
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
