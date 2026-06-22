package com.continuum.app.common.player.video

import com.continuum.app.model.catalog.FileVersion
import com.continuum.app.model.playback.ClientPlaybackContext
import com.continuum.app.model.playback.EngineCapabilityEnvelope
import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackEngineKind

fun FileVersion.requestedOriginalPlaybackMethod(
    playbackContext: ClientPlaybackContext,
    audioTrackIndex: Int? = null,
): PlayMethod? {
    val container = normalizedPlaybackContainer(container) ?: return null
    val videoCodec = normalizedVideoCodec(codecVideo ?: videoTracks?.firstOrNull()?.codec)
    val audioCodec = selectedAudioCodec(audioTrackIndex)

    return if (playbackContext.hasDirectOriginalRoute(container, videoCodec, audioCodec)) {
        PlayMethod.DIRECT
    } else {
        null
    }
}

private fun FileVersion.selectedAudioCodec(audioTrackIndex: Int?): String? {
    val selectedTrack = audioTrackIndex?.let { selectedIndex ->
        audioTracks?.firstOrNull { it.index == selectedIndex }
    }
    val fallbackTrack = audioTracks
        ?.firstOrNull { it.isDefault }
        ?: audioTracks?.firstOrNull()
    return normalizedAudioCodec(selectedTrack?.codec ?: codecAudio ?: fallbackTrack?.codec)
}

private fun ClientPlaybackContext.hasDirectOriginalRoute(
    container: String,
    videoCodec: String?,
    audioCodec: String?,
): Boolean {
    val media3 = engines[PlaybackEngineKind.MEDIA3_DIRECT]
    if (media3.canDirectPlay(container, videoCodec, audioCodec)) return true

    val mpv = engines[PlaybackEngineKind.MPV_DIRECT]
    return isMpvOriginalPlaybackContainer(container) &&
        mpv.canDirectPlay(container, videoCodec, audioCodec)
}

private fun EngineCapabilityEnvelope?.canDirectPlay(
    container: String,
    videoCodec: String?,
    audioCodec: String?,
): Boolean {
    val engine = this ?: return false
    if (!engine.enabled || !engine.supportedOnDevice) return false
    if (container !in engine.containers.mapNotNull(::normalizedPlaybackContainer).toSet()) return false

    val supportedVideo = engine.videoCodecs.mapNotNull(::normalizedVideoCodec).toSet()
    if (videoCodec != null && videoCodec !in supportedVideo) return false

    val supportedAudio = (engine.audioDecodeCodecs + engine.audioPassthroughCodecs)
        .mapNotNull(::normalizedAudioCodec)
        .toSet()
    if (audioCodec != null && audioCodec !in supportedAudio) return false

    return true
}

private fun normalizedVideoCodec(codec: String?): String? {
    val value = codec.normalizedToken() ?: return null
    return when {
        value.contains("dolbyvision") || value.startsWith("dvhe") || value.startsWith("dvh1") ||
            value.startsWith("dva1") || value.startsWith("dvav") -> "dolby_vision"
        value.contains("h265") || value.contains("x265") || value.contains("hevc") ||
            value.startsWith("hvc1") || value.startsWith("hev1") -> "hevc"
        value.contains("h264") || value.contains("x264") || value.contains("avc") ||
            value.startsWith("avc1") -> "h264"
        value.contains("av1") || value.startsWith("av01") -> "av1"
        value.contains("vp9") || value.startsWith("vp09") -> "vp9"
        value.contains("vp8") || value.startsWith("vp08") -> "vp8"
        else -> value
    }
}

private fun normalizedAudioCodec(codec: String?): String? {
    val value = codec.normalizedToken() ?: return null
    return when {
        value.contains("eac3joc") || value.contains("ec3joc") -> "eac3_joc"
        value.contains("eac3") || value.contains("ec3") -> "eac3"
        value.contains("truehd") || value.contains("mlp") -> "truehd"
        value.contains("dtshd") -> "dts_hd"
        value.contains("dts") -> "dts"
        value.contains("ac3") || value.contains("a52") -> "ac3"
        value.contains("aac") || value.startsWith("mp4a") -> "aac"
        value.contains("flac") -> "flac"
        value.contains("opus") -> "opus"
        value.contains("vorbis") -> "vorbis"
        value.contains("mp3") || value.contains("mpeg") -> "mp3"
        else -> value
    }
}

private fun String?.normalizedToken(): String? =
    this
        ?.trim()
        ?.lowercase()
        ?.filter { it.isLetterOrDigit() }
        ?.takeIf { it.isNotBlank() }
