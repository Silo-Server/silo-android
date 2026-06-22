package com.continuum.app.common.player.backend

import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackEngineKind
import com.continuum.app.model.playback.PlaybackRouteFamily
import kotlinx.serialization.Serializable

@Serializable
data class VideoPlaybackBackendRequest(
    val contentId: String? = null,
    val fileId: Int? = null,
    val playMethod: PlayMethod? = null,
    val delivery: PlaybackDelivery? = null,
    val plannedEngine: PlaybackEngineKind? = null,
    val routeFamily: PlaybackRouteFamily? = null,
    val formFactor: VideoPlaybackFormFactor = VideoPlaybackFormFactor.Unknown,
    val preference: VideoPlaybackBackendPreference = VideoPlaybackBackendPreference.Auto,
    val hasHardContainer: Boolean = false,
    val hasStyledSubtitles: Boolean = false,
    // True when the resolved playback URL is an adaptive HLS playlist. Some
    // legacy sessions arrive without a v2 playbackPlan/delivery even though the
    // URL is a master.m3u8; route those to Media3 up front instead of trying MPV
    // and relying on failure recovery.
    val isAdaptiveHlsStream: Boolean = false,
    // Route/session intent — any of these forces Media3 under Auto, because Cast,
    // DRM, and external/secondary displays are paths where ExoPlayer is the
    // correct/only engine and MPV's direct rendering does not apply.
    val isCasting: Boolean = false,
    val isDrmProtected: Boolean = false,
    val isExternalDisplay: Boolean = false,
    // Device-class floor result (computed at the call site from Build.VERSION +
    // Build.SUPPORTED_ABIS via MpvDeviceFloor). Default true so pure/unit call
    // sites keep prior behavior; production call sites pass the real value.
    val mpvSupportedOnDevice: Boolean = true,
)

fun isLikelyAdaptiveHlsStreamUrl(streamUrl: String?): Boolean {
    val normalized = streamUrl
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.trim()
        ?.lowercase()
        ?: return false
    return normalized.endsWith(".m3u8") ||
        normalized.endsWith("/master.m3u8") ||
        normalized.contains(".m3u8/")
}
