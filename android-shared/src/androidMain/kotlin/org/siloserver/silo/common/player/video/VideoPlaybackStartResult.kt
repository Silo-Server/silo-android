package org.siloserver.silo.common.player.video

import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlayerSubtitleInfo

sealed interface VideoPlaybackStartResult {
    data class Ready(
        val contentId: String,
        val fileId: Int?,
        /** All server file versions for this item — powers in-player version
         *  switching (QA 2026-07-08 / tvOS parity). */
        val versions: List<org.siloserver.silo.model.catalog.FileVersion> = emptyList(),
        val fileResolution: String? = null,
        val streamUrl: String,
        val playMethod: PlayMethod,
        val playbackPlan: PlaybackExecutionPlan? = null,
        val playbackPlanV3: PlaybackPlanV3? = null,
        val requestHeaders: Map<String, String> = emptyMap(),
        val delivery: PlaybackDelivery? = null,
        val container: String? = null,
        val title: String,
        val subtitle: String?,
        val artworkUrl: String?,
        /** Position in the mounted Media3 timeline. This may be zero for a
         * server-reanchored HLS stream whose movie-time origin is non-zero. */
        val startPositionSeconds: Double,
        /** Position in the full movie/source timeline shown to the user and
         * reported to the server. */
        val sourceStartPositionSeconds: Double = startPositionSeconds,
        val sessionId: String? = null,
        val serverUrl: String = "",
        val accessToken: String = "",
        val mediaFileId: Int? = null,
        val audioTrackIndex: Int = 0,
        val durationSeconds: Double = 0.0,
        val subtitleUrls: List<PlayerSubtitleInfo> = emptyList(),
        val preferredAudioLanguage: String? = null,
        val preferredTextLanguage: String? = null,
        val preferredSubtitleMode: String? = null,
        val showForcedSubtitles: Boolean = true,
        val intro: TimeRange? = null,
        val credits: TimeRange? = null,
        val chapters: List<VersionChapter> = emptyList(),
        // Episode context for next-episode auto-advance (null for movies).
        val seriesId: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
    ) : VideoPlaybackStartResult

    data class Error(
        val contentId: String,
        val message: String,
    ) : VideoPlaybackStartResult
}
