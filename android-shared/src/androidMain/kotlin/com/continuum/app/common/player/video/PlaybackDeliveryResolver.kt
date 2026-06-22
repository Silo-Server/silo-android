package com.continuum.app.common.player.video

import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackSessionResponse

fun PlaybackSessionResponse.resolvedPlaybackDelivery(): PlaybackDelivery? {
    playbackPlan?.delivery?.let { return it }
    val streamType = playbackInfo?.streamType
        ?.trim()
        ?.lowercase()
    return when {
        playMethod == PlayMethod.DIRECT -> PlaybackDelivery.ORIGINAL_HTTP
        playMethod == PlayMethod.REMUX && streamType == "progressive" ->
            PlaybackDelivery.SERVER_REMUX_PROGRESSIVE
        playMethod == PlayMethod.REMUX && streamType == "hls" ->
            PlaybackDelivery.SERVER_REMUX_HLS
        playMethod == PlayMethod.TRANSCODE && streamType == "hls" ->
            PlaybackDelivery.SERVER_TRANSCODE_HLS
        playMethod == PlayMethod.REMUX && streamUrl.isLikelyHlsPlaylistUrl() ->
            PlaybackDelivery.SERVER_REMUX_HLS
        playMethod == PlayMethod.TRANSCODE && streamUrl.isLikelyHlsPlaylistUrl() ->
            PlaybackDelivery.SERVER_TRANSCODE_HLS
        else -> null
    }
}

fun PlaybackSessionResponse.canPlayResolvedStreamDirectly(): Boolean =
    playMethod == PlayMethod.DIRECT ||
        resolvedPlaybackDelivery() == PlaybackDelivery.SERVER_REMUX_PROGRESSIVE

private fun String.isLikelyHlsPlaylistUrl(): Boolean {
    val normalized = substringBefore('?')
        .substringBefore('#')
        .trim()
        .lowercase()
    return normalized.endsWith(".m3u8") ||
        normalized.endsWith("/master.m3u8") ||
        normalized.contains(".m3u8/")
}
