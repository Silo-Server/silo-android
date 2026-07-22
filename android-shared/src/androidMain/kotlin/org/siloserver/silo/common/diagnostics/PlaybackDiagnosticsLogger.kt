package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.siloserver.silo.common.diagnostics.logging.SiloLog
import org.siloserver.silo.common.player.PlaybackAnalyticsListener
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.common.player.reducePlayerStats
import org.siloserver.silo.model.diagnostics.DiagnosticsAttrRegistry.Attr
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory

/**
 * Curated `cat=playback` diagnostics wiring: one line per analytics event,
 * plus a periodic (~5s) `StatsSnapshot` timeline line while a session is
 * attached — decoder, resolution, HDR mode, bitrate, dropped frames, audio
 * underruns around a failure. Attribute keys are exactly the server-registered
 * playback set; free-text carries no URLs or media identifiers.
 *
 * Attach when a player session starts, detach on release. Snapshot lines are
 * only emitted while debug logging (or a manual capture) is active — the
 * per-event lines always feed the always-on ring.
 */
class PlaybackDiagnosticsLogger(
    private val sinkTypeProvider: () -> String?,
    private val statsTimelineEnabled: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventsJob: Job? = null
    private var tickerJob: Job? = null

    @Volatile
    private var stats = PlayerStatsSnapshot()

    fun attach(events: SharedFlow<PlaybackAnalyticsListener.Event>) {
        detach()
        stats = PlayerStatsSnapshot()
        eventsJob = scope.launch {
            events.collect { event ->
                stats = reducePlayerStats(stats, event)
                logEvent(event)
            }
        }
        tickerJob = scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                if (statsTimelineEnabled()) logStatsSnapshot()
            }
        }
    }

    fun detach() {
        eventsJob?.cancel()
        eventsJob = null
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun logEvent(event: PlaybackAnalyticsListener.Event) {
        when (event) {
            is PlaybackAnalyticsListener.Event.VideoDecoderInitialized ->
                SiloLog.i(
                    DiagnosticsLogCategory.PLAYBACK, TAG, "video decoder initialized",
                    mapOf("decoder" to Attr.Str(event.decoderName)),
                )
            is PlaybackAnalyticsListener.Event.AudioDecoderInitialized ->
                SiloLog.i(
                    DiagnosticsLogCategory.PLAYBACK, TAG, "audio decoder initialized",
                    mapOf("decoder" to Attr.Str(event.decoderName)),
                )
            is PlaybackAnalyticsListener.Event.VideoFormatChanged ->
                SiloLog.i(
                    DiagnosticsLogCategory.PLAYBACK, TAG, "video format changed",
                    buildMap {
                        event.format.sampleMimeType?.let { put("fmt", Attr.Str(it)) }
                        if (event.format.width > 0) put("width", Attr.Int64(event.format.width.toLong()))
                        if (event.format.height > 0) put("height", Attr.Int64(event.format.height.toLong()))
                    },
                )
            is PlaybackAnalyticsListener.Event.AudioFormatChanged ->
                SiloLog.i(
                    DiagnosticsLogCategory.PLAYBACK, TAG, "audio format changed",
                    buildMap {
                        event.format.sampleMimeType?.let { put("fmt", Attr.Str(it)) }
                        sinkTypeProvider()?.let { put("sink", Attr.Str(it)) }
                    },
                )
            is PlaybackAnalyticsListener.Event.DroppedFrames ->
                SiloLog.w(
                    DiagnosticsLogCategory.PLAYBACK, TAG, "dropped frames",
                    attrs = mapOf("dropped_frames" to Attr.Int64(event.count.toLong())),
                )
            is PlaybackAnalyticsListener.Event.AudioUnderrun ->
                SiloLog.w(DiagnosticsLogCategory.PLAYBACK, TAG, "audio underrun")
            is PlaybackAnalyticsListener.Event.LoadError ->
                SiloLog.w(DiagnosticsLogCategory.PLAYBACK, TAG, "load error", event.throwable)
            is PlaybackAnalyticsListener.Event.PlayerError ->
                SiloLog.e(DiagnosticsLogCategory.PLAYBACK, TAG, "player error", event.error)
            is PlaybackAnalyticsListener.Event.BandwidthEstimate ->
                SiloLog.v(
                    DiagnosticsLogCategory.PLAYBACK, TAG, "bandwidth estimate",
                    mapOf("bitrate_kbps" to Attr.Int64(event.bitrateBps / 1000)),
                )
            is PlaybackAnalyticsListener.Event.TrackSnapshot ->
                SiloLog.d(DiagnosticsLogCategory.PLAYBACK, TAG, "track snapshot: ${event.description}")
        }
    }

    private fun logStatsSnapshot() {
        val snapshot = stats
        SiloLog.d(
            DiagnosticsLogCategory.PLAYBACK, STATS_TAG, "player stats",
            buildMap {
                snapshot.videoDecoderName?.let { put("decoder", Attr.Str(it)) }
                snapshot.videoMimeType?.let { put("fmt", Attr.Str(it)) }
                snapshot.videoWidth?.let { put("width", Attr.Int64(it.toLong())) }
                snapshot.videoHeight?.let { put("height", Attr.Int64(it.toLong())) }
                snapshot.hdrMode?.let { put("hdr_mode", Attr.Str(it)) }
                snapshot.bitrateBps?.let { put("bitrate_kbps", Attr.Int64(it / 1000)) }
                put("dropped_frames", Attr.Int64(snapshot.droppedFrames.toLong()))
                put("audio_underruns", Attr.Int64(snapshot.audioUnderruns.toLong()))
                sinkTypeProvider()?.let { put("sink", Attr.Str(it)) }
            },
        )
    }

    private companion object {
        const val TAG = "Playback"
        const val STATS_TAG = "StatsSnapshot"
        const val STATS_INTERVAL_MS = 5_000L
    }
}
