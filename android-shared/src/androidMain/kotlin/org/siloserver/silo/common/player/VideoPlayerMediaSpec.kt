package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlayerSubtitleInfo

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
