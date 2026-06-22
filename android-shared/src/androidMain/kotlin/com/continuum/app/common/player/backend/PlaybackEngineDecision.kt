package com.continuum.app.common.player.backend

import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackEngineKind

/**
 * Structured, loggable record of why a playback engine was chosen and which
 * engine was actually bound. The reason string mirrors VideoPlaybackBackendSelector's
 * Auto clause order so logs and policy never drift. `actual` may differ from
 * `selected` when the factory/owner downgrades MPV -> Media3 (e.g. the bound
 * player is not an MpvPlayer); logging `selected` alone would claim MPV while
 * Media3 actually plays. Track-A observability requirement.
 */
data class PlaybackEngineDecision(
    val selected: VideoPlaybackBackendKind,
    val actual: VideoPlaybackBackendKind,
    val reason: String,
    val contentId: String?,
    val fileId: Int?,
) {
    val downgraded: Boolean get() = selected != actual

    fun toLogLine(): String =
        "playback-engine selected=$selected actual=$actual downgraded=$downgraded " +
            "reason=$reason contentId=$contentId fileId=$fileId"

    companion object {
        fun from(
            request: VideoPlaybackBackendRequest,
            selected: VideoPlaybackBackendKind,
            actual: VideoPlaybackBackendKind,
        ) = PlaybackEngineDecision(
            selected = selected,
            actual = actual,
            reason = reasonFor(request),
            contentId = request.contentId,
            fileId = request.fileId,
        )

        private fun reasonFor(request: VideoPlaybackBackendRequest): String =
            when (request.preference) {
                VideoPlaybackBackendPreference.Media3 -> "preference=Media3"
                VideoPlaybackBackendPreference.Mpv -> "preference=Mpv"
                VideoPlaybackBackendPreference.Auto -> when {
                    !request.mpvSupportedOnDevice -> "mpvSupportedOnDevice=false"
                    request.isCasting -> "isCasting"
                    request.isDrmProtected -> "isDrmProtected"
                    request.isExternalDisplay -> "isExternalDisplay"
                    request.isAdaptiveHlsStream -> "isAdaptiveHlsStream"
                    request.delivery == PlaybackDelivery.SERVER_REMUX_HLS -> "delivery=server_remux_hls"
                    request.delivery == PlaybackDelivery.SERVER_TRANSCODE_HLS -> "delivery=server_transcode_hls"
                    request.plannedEngine == PlaybackEngineKind.MEDIA3_DIRECT -> "plannedEngine=media3_direct"
                    request.plannedEngine == PlaybackEngineKind.MEDIA3_PROGRESSIVE_REMUX -> "plannedEngine=media3_progressive_remux"
                    request.plannedEngine == PlaybackEngineKind.MEDIA3_HLS -> "plannedEngine=media3_hls"
                    request.plannedEngine == PlaybackEngineKind.MPV_DIRECT -> "plannedEngine=mpv_direct"
                    request.delivery == PlaybackDelivery.SERVER_REMUX_PROGRESSIVE -> "delivery=server_remux_progressive"
                    request.playMethod == PlayMethod.TRANSCODE -> "transcode"
                    request.hasHardContainer -> "hasHardContainer"
                    request.hasStyledSubtitles -> "hasStyledSubtitles"
                    else -> "default"
                }
            }
    }
}
