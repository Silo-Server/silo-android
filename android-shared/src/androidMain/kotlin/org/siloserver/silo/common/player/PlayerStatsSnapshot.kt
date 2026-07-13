package org.siloserver.silo.common.player

import androidx.media3.common.C
import androidx.media3.common.Format

/**
 * Snapshot of player statistics surfaced in playback diagnostics UI.
 * Built by [reducePlayerStats] from a stream of [PlaybackAnalyticsListener.Event]s.
 *
 * All fields nullable except counters. Fields populate as events arrive; UIs
 * should tolerate any subset being null. `droppedFrames` and `audioUnderruns`
 * are cumulative counters since the snapshot was created.
 */
data class PlayerStatsSnapshot(
    val backendKind: String? = null,
    val backendDisplayName: String? = null,
    val backendRoute: String? = null,
    val subtitleRendering: String? = null,
    val hardContainers: String? = null,
    val videoDecoderName: String? = null,
    val videoDecoderInitializationMs: Long? = null,
    val audioDecoderName: String? = null,
    val videoCodec: String? = null,
    val videoMimeType: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val colorTransfer: String? = null,
    val colorRange: String? = null,
    val audioCodec: String? = null,
    val resolution: String? = null,
    val frameRate: Float? = null,
    val hdrMode: String? = null,
    val bitrateBps: Long? = null,
    val droppedFrames: Int = 0,
    val audioUnderruns: Int = 0,
)

/**
 * Pure event-to-snapshot reducer. It never clears state on unrelated events,
 * so a dropped-frame event leaves the last format/decoder details intact.
 */
fun reducePlayerStats(
    current: PlayerStatsSnapshot,
    event: PlaybackAnalyticsListener.Event,
): PlayerStatsSnapshot = when (event) {
    is PlaybackAnalyticsListener.Event.VideoDecoderInitialized ->
        current.copy(
            videoDecoderName = event.decoderName,
            videoDecoderInitializationMs = event.initializationDurationMs,
        )
    is PlaybackAnalyticsListener.Event.AudioDecoderInitialized ->
        current.copy(audioDecoderName = event.decoderName)
    is PlaybackAnalyticsListener.Event.VideoFormatChanged -> current.copy(
        videoCodec = event.format.codecs ?: event.format.sampleMimeType,
        videoMimeType = event.format.sampleMimeType,
        videoWidth = event.format.width.takeIf { it > 0 } ?: current.videoWidth,
        videoHeight = event.format.height.takeIf { it > 0 } ?: current.videoHeight,
        resolution = if (event.format.width > 0 && event.format.height > 0) {
            "${event.format.width}x${event.format.height}"
        } else {
            current.resolution
        },
        frameRate = if (event.format.frameRate > 0f) event.format.frameRate else current.frameRate,
        hdrMode = describeHdrMode(event.format) ?: current.hdrMode,
        colorTransfer = describeColorTransfer(event.format) ?: current.colorTransfer,
        colorRange = describeColorRange(event.format) ?: current.colorRange,
    )
    is PlaybackAnalyticsListener.Event.AudioFormatChanged ->
        current.copy(audioCodec = event.format.codecs ?: event.format.sampleMimeType)
    is PlaybackAnalyticsListener.Event.DroppedFrames ->
        current.copy(droppedFrames = current.droppedFrames + event.count)
    is PlaybackAnalyticsListener.Event.AudioUnderrun ->
        current.copy(audioUnderruns = current.audioUnderruns + 1)
    is PlaybackAnalyticsListener.Event.BandwidthEstimate ->
        current.copy(bitrateBps = event.bitrateBps)
    is PlaybackAnalyticsListener.Event.LoadError,
    is PlaybackAnalyticsListener.Event.PlayerError,
    is PlaybackAnalyticsListener.Event.TrackSnapshot,
    -> current
}

fun PlayerStatsSnapshot.firstFrameDiagnostics(firstFrameMs: Long): Map<String, String> = buildMap {
    videoDecoderName?.let { put("decoder_name", it) }
    videoDecoderInitializationMs?.let { put("decoder_init_ms", it.toString()) }
    put("first_frame_ms", firstFrameMs.coerceAtLeast(0).toString())
    videoMimeType?.let { put("video_mime", it) }
    videoCodec?.let { put("video_codecs", it) }
    videoWidth?.let { put("video_width", it.toString()) }
    videoHeight?.let { put("video_height", it.toString()) }
    colorTransfer?.let { put("color_transfer", it) }
    colorRange?.let { put("color_range", it) }
}

private fun describeColorTransfer(format: Format): String? = when (format.colorInfo?.colorTransfer) {
    C.COLOR_TRANSFER_ST2084 -> "st2084"
    C.COLOR_TRANSFER_HLG -> "hlg"
    C.COLOR_TRANSFER_SDR -> "sdr"
    else -> null
}

private fun describeColorRange(format: Format): String? = when (format.colorInfo?.colorRange) {
    C.COLOR_RANGE_FULL -> "full"
    C.COLOR_RANGE_LIMITED -> "limited"
    else -> null
}

private fun describeHdrMode(format: Format): String? {
    val codecs = format.codecs.orEmpty()
    if (codecs.contains("dvh", ignoreCase = true) || codecs.contains("dvhe", ignoreCase = true)) {
        return "Dolby Vision"
    }
    val colorInfo = format.colorInfo ?: return null
    return when (colorInfo.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_SDR -> "SDR"
        else -> null
    }
}
