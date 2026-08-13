package org.siloserver.silo.common.player.video

import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlayerSubtitleInfo

sealed interface VideoPlayerUiState {
    val contentId: String
    val hasPlayableMedia: Boolean

    data class Loading(
        override val contentId: String,
    ) : VideoPlayerUiState {
        override val hasPlayableMedia: Boolean = false
    }

    data class Error(
        override val contentId: String,
        val message: String,
    ) : VideoPlayerUiState {
        override val hasPlayableMedia: Boolean = false
    }

    /**
     * Server unreachable with no local copy — the player VM surfaces a distinct
     * "Can't reach server" state with Retry / Try Anyway rather than a generic
     * error (issue #33). Carried straight through from
     * [VideoPlaybackStartResult.ServerUnreachable].
     */
    data class ServerUnreachable(
        override val contentId: String,
    ) : VideoPlayerUiState {
        override val hasPlayableMedia: Boolean = false
    }

    data class Ready(
        override val contentId: String,
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
        /** Initial position in the mounted Media3 timeline. */
        val startPositionSeconds: Double,
        /** Initial position in the full source/movie timeline. */
        val sourceStartPositionSeconds: Double = startPositionSeconds,
        val sessionId: String? = null,
        val serverUrl: String = "",
        val accessToken: String = "",
        val mediaFileId: Int? = null,
        val audioTrackIndex: Int = 0,
        /** Full source duration; null when the V3 plan leaves it unknown. */
        val durationSeconds: Double? = null,
        val subtitleUrls: List<PlayerSubtitleInfo> = emptyList(),
        val preferredAudioLanguage: String? = null,
        val preferredTextLanguage: String? = null,
        val preferredSubtitleMode: String? = null,
        val showForcedSubtitles: Boolean = true,
        val intro: TimeRange? = null,
        val credits: TimeRange? = null,
        val recap: TimeRange? = null,
        val preview: TimeRange? = null,
        val chapters: List<VersionChapter> = emptyList(),
        // Episode context for next-episode auto-advance (null for movies).
        val seriesId: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        /** Target-catalog decision for the one-shot episode-selection handoff. */
        val resolvedEpisodeSelection: ResolvedEpisodeSelection? = null,
    ) : VideoPlayerUiState {
        override val hasPlayableMedia: Boolean = true

        val startPositionMs: Long
            get() {
                val seconds = if (startPositionSeconds.isFinite()) startPositionSeconds else 0.0
                return (seconds * 1000.0).toLong().coerceAtLeast(0L)
            }
    }
}
