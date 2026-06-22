package com.continuum.app.common.player.backend

import com.continuum.app.model.playback.PlaybackDelivery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackEngineDecisionTest {
    @Test
    fun decisionRecordsSelectedAndActualAndDeterminingAxis() {
        val request = VideoPlaybackBackendRequest(hasStyledSubtitles = true)
        // selected == actual: MPV chosen and an MpvPlayer was actually bound.
        val decision = PlaybackEngineDecision.from(
            request,
            selected = VideoPlaybackBackendKind.Mpv,
            actual = VideoPlaybackBackendKind.Mpv,
        )
        assertEquals(VideoPlaybackBackendKind.Mpv, decision.selected)
        assertEquals(VideoPlaybackBackendKind.Mpv, decision.actual)
        assertEquals("hasStyledSubtitles", decision.reason)
    }

    @Test
    fun decisionRecordsDowngradeWhenActualDiffersFromSelected() {
        // Selector said MPV but the factory/owner bound Media3 (downgrade) — the
        // log MUST show both, or logs can claim MPV while Media3 actually plays.
        val request = VideoPlaybackBackendRequest(hasHardContainer = true)
        val line = PlaybackEngineDecision
            .from(request, selected = VideoPlaybackBackendKind.Mpv, actual = VideoPlaybackBackendKind.Media3)
            .toLogLine()
        assertTrue(line.contains("selected=Mpv"))
        assertTrue(line.contains("actual=Media3"))
        assertTrue(line.contains("downgraded=true"))
    }

    @Test
    fun decisionRendersOneLineLog() {
        val request = VideoPlaybackBackendRequest(isCasting = true)
        val line = PlaybackEngineDecision
            .from(request, selected = VideoPlaybackBackendKind.Media3, actual = VideoPlaybackBackendKind.Media3)
            .toLogLine()
        assertTrue(line.contains("actual=Media3"))
        assertTrue(line.contains("reason=isCasting"))
    }

    @Test
    fun decisionExplainsUrlInferredHlsWithoutLoggingTheUrl() {
        val request = VideoPlaybackBackendRequest(
            hasStyledSubtitles = true,
            isAdaptiveHlsStream = true,
        )
        val line = PlaybackEngineDecision
            .from(request, selected = VideoPlaybackBackendKind.Media3, actual = VideoPlaybackBackendKind.Media3)
            .toLogLine()

        assertTrue(line.contains("reason=isAdaptiveHlsStream"))
        assertTrue(!line.contains("m3u8"))
        assertTrue(!line.contains("http"))
    }

    @Test
    fun decisionExplainsProgressiveServerRemux() {
        val request = VideoPlaybackBackendRequest(
            delivery = PlaybackDelivery.SERVER_REMUX_PROGRESSIVE,
            hasHardContainer = true,
        )
        val line = PlaybackEngineDecision
            .from(request, selected = VideoPlaybackBackendKind.Mpv, actual = VideoPlaybackBackendKind.Mpv)
            .toLogLine()

        assertTrue(line.contains("reason=delivery=server_remux_progressive"))
    }
}
