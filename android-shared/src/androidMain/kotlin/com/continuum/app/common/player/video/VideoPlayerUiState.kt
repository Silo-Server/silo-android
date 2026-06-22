package com.continuum.app.common.player.video

import com.continuum.app.model.catalog.TimeRange
import com.continuum.app.model.catalog.VersionChapter
import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackExecutionPlan
import com.continuum.app.model.playback.PlayerSubtitleInfo

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

    data class Ready(
        override val contentId: String,
        val fileId: Int?,
        val streamUrl: String,
        val playMethod: PlayMethod,
        val playbackPlan: PlaybackExecutionPlan? = null,
        val delivery: PlaybackDelivery? = null,
        val container: String? = null,
        val title: String,
        val subtitle: String?,
        val artworkUrl: String?,
        val startPositionSeconds: Double,
        val sessionId: String? = null,
        val serverUrl: String = "",
        val accessToken: String = "",
        val mediaFileId: Int? = null,
        val audioTrackIndex: Int = 0,
        val durationSeconds: Double = 0.0,
        val subtitleUrls: List<PlayerSubtitleInfo> = emptyList(),
        val preferredAudioLanguage: String? = null,
        val preferredTextLanguage: String? = null,
        val intro: TimeRange? = null,
        val credits: TimeRange? = null,
        val chapters: List<VersionChapter> = emptyList(),
        // Episode context for next-episode auto-advance (null for movies).
        val seriesId: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
    ) : VideoPlayerUiState {
        override val hasPlayableMedia: Boolean = true

        val startPositionMs: Long
            get() {
                val seconds = if (startPositionSeconds.isFinite()) startPositionSeconds else 0.0
                return (seconds * 1000.0).toLong().coerceAtLeast(0L)
            }
    }
}
