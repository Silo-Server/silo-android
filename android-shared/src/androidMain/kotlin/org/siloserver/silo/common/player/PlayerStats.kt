@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.common.player

/**
 * Snapshot of player statistics surfaced in the players' stats surfaces
 * (TV HUD Stats pane, phone Playback Stats overlay). Built by
 * [reducePlayerStats] from a stream of [PlaybackAnalyticsListener.Event]s.
 *
 * All fields nullable — fields populate as events arrive; rendering should
 * tolerate any subset being null. `droppedFrames` and `audioUnderruns` are
 * cumulative counters since the snapshot was created.
 */
data class PlayerStatsSnapshot(
    val backendKind: String? = null,
    val backendDisplayName: String? = null,
    val backendRoute: String? = null,
    val subtitleRendering: String? = null,
    val hardContainers: String? = null,
    val videoDecoderName: String? = null,
    val audioDecoderName: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val resolution: String? = null,            // e.g. "1920x1080"
    val frameRate: Float? = null,
    val hdrMode: String? = null,               // e.g. "Dolby Vision", "HDR10", "SDR"
    val bitrateBps: Long? = null,
    val droppedFrames: Int = 0,                // cumulative since session start
    val audioUnderruns: Int = 0,               // cumulative
)

/**
 * Pure event-to-snapshot reducer. Used by both players' ViewModels; tested in
 * isolation. Does NOT clear state on unrelated events (e.g. a DroppedFrames
 * event leaves format/decoder fields untouched).
 */
fun reducePlayerStats(
    current: PlayerStatsSnapshot,
    event: PlaybackAnalyticsListener.Event,
): PlayerStatsSnapshot = when (event) {
    is PlaybackAnalyticsListener.Event.VideoDecoderInitialized ->
        current.copy(videoDecoderName = event.decoderName)
    is PlaybackAnalyticsListener.Event.AudioDecoderInitialized ->
        current.copy(audioDecoderName = event.decoderName)
    is PlaybackAnalyticsListener.Event.VideoFormatChanged -> current.copy(
        videoCodec = event.format.codecs ?: event.format.sampleMimeType,
        resolution = if (event.format.width > 0 && event.format.height > 0) {
            "${event.format.width}x${event.format.height}"
        } else current.resolution,
        frameRate = if (event.format.frameRate > 0f) event.format.frameRate else current.frameRate,
        hdrMode = describeHdrMode(event.format) ?: current.hdrMode,
    )
    is PlaybackAnalyticsListener.Event.AudioFormatChanged ->
        current.copy(audioCodec = event.format.codecs ?: event.format.sampleMimeType)
    is PlaybackAnalyticsListener.Event.DroppedFrames ->
        current.copy(droppedFrames = current.droppedFrames + event.count)
    is PlaybackAnalyticsListener.Event.AudioUnderrun ->
        current.copy(audioUnderruns = current.audioUnderruns + 1)
    is PlaybackAnalyticsListener.Event.BandwidthEstimate ->
        current.copy(bitrateBps = event.bitrateBps)
    is PlaybackAnalyticsListener.Event.LoadError ->
        current // load errors don't mutate the stats snapshot
    is PlaybackAnalyticsListener.Event.PlayerError ->
        current // player errors are logged separately and don't mutate the stats snapshot
    is PlaybackAnalyticsListener.Event.TrackSnapshot ->
        current // diagnostic-only; keep on-screen stats stable
}

/**
 * Describe the HDR mode of a video [androidx.media3.common.Format].
 *
 * Dolby Vision detection is by codec string (`dvh1`, `dvhe`) and runs BEFORE
 * the `colorTransfer` switch because DV bitstreams can carry varying color
 * transfers and Apple's reference treats DV as its own mode. Returns `null`
 * if no HDR signal is present (caller keeps the prior value).
 */
private fun describeHdrMode(format: androidx.media3.common.Format): String? {
    val codecs = format.codecs.orEmpty()
    if (codecs.contains("dvh", ignoreCase = true) || codecs.contains("dvhe", ignoreCase = true)) {
        return "Dolby Vision"
    }
    val colorInfo = format.colorInfo ?: return null
    return when (colorInfo.colorTransfer) {
        androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10"
        androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"
        androidx.media3.common.C.COLOR_TRANSFER_SDR -> "SDR"
        else -> null
    }
}
