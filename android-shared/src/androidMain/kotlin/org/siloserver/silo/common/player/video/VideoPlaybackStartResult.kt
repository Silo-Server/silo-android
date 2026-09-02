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
        /** TV's target-catalog resolution of [VideoPlaybackStartRequest.episodeSelectionHandoff]. */
        val resolvedEpisodeSelection: ResolvedEpisodeSelection? = null,
    ) : VideoPlaybackStartResult

    data class Error(
        val contentId: String,
        val message: String,
        val diagnosticsCode: PlaybackDiagnosticsCode? = null,
    ) : VideoPlaybackStartResult

    /**
     * The configured server is unreachable and no playable local download
     * exists, so playback was not even attempted (no doomed player spin-up).
     * Distinct from [Error] so the UI can offer Retry / Try Anyway instead of a
     * generic failure. Mirrors silo-apple ItemDetailView.swift:817-857.
     */
    data class ServerUnreachable(
        val contentId: String,
    ) : VideoPlaybackStartResult
}

/**
 * The user-visible text for a server playback terminal. `terminal.message` is
 * written by the server for end users and can stand alone; the terminal reason
 * slug (e.g. "transcode_start_failed") belongs in diagnostics/telemetry only,
 * never in the on-screen sentence.
 */
fun serverTerminalUserMessage(message: String): String =
    message.trim().ifEmpty { "Playback is unavailable right now." }

@JvmInline
value class PlaybackDiagnosticsCode private constructor(val wireValue: String) {
    companion object {
        val CATALOG = PlaybackDiagnosticsCode("catalog_error")
        val NETWORK = PlaybackDiagnosticsCode("network_error")
        val NO_VERSIONS = PlaybackDiagnosticsCode("no_versions")
        val NO_ACTIVE_PROFILE = PlaybackDiagnosticsCode("no_active_profile")
        val NOT_AUTHENTICATED = PlaybackDiagnosticsCode("not_authenticated")
        val START_REQUEST = PlaybackDiagnosticsCode("start_request_error")
        val SERVER_UPGRADE_REQUIRED = PlaybackDiagnosticsCode("server_upgrade_required")
        val UNEXPECTED = PlaybackDiagnosticsCode("unexpected_error")

        fun serverTerminal(reason: String): PlaybackDiagnosticsCode? =
            reason.trim().lowercase().takeIf(SAFE_SERVER_TERMINAL_REASONS::contains)?.let(::PlaybackDiagnosticsCode)

        private val SAFE_SERVER_TERMINAL_REASONS = setOf(
            "adaptation_exhausted",
            "adaptation_unavailable",
            "audio_conversion_unsupported",
            "audio_transcoding_disabled",
            "client_hls_unsupported",
            "conversion_tool_unavailable",
            "hdr_transcode_unsupported",
            "invalid_playback_plan",
            "invalid_terminal_response",
            "no_alternate_version",
            "replan_loop_detected",
            "source_metadata_incomplete",
            "source_unavailable",
            "transcoding_disabled",
            "transcode_start_failed",
            "transcode_node_unavailable",
            "transcode_node_capability_unavailable",
        )
    }
}
