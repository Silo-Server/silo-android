package org.siloserver.silo.common.player.seek

import org.siloserver.silo.model.playback.PlaybackTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PlaybackTimelineSeekPolicyTest {
    @Test
    fun offsetMapsBetweenPlayerAndSourceCoordinates() {
        val timeline = PlaybackTimeline(timelineOffsetSeconds = 120.0)

        assertEquals(125.5, timeline.sourcePositionForPlayer(5.5))
        assertEquals(5.5, timeline.playerPositionForSource(125.5))
        assertNull(timeline.playerPositionForSource(119.0))
    }

    @Test
    fun globalSeekClaimAllowsNativeSeekWithoutAWindow() {
        val decision = PlaybackTimeline(
            timelineOffsetSeconds = 0.0,
            canSeekAnywhere = true,
            seekRestoration = "player_position",
        ).decideSeek(75.0)

        val native = assertIs<PlaybackSeekDecision.NativeSeek>(decision)
        assertEquals(75.0, native.targetPlayerPositionSeconds)
        assertEquals(PlaybackSeekRestoration.PlayerPosition, native.restoration)
    }

    @Test
    fun completeWindowAllowsNativeSeekForSourceRestoredTransport() {
        val decision = PlaybackTimeline(
            timelineOffsetSeconds = 120.0,
            canSeekAnywhere = false,
            seekWindowStartSeconds = 120.0,
            seekWindowEndSeconds = 180.0,
            seekRestoration = "source_position",
        ).decideSeek(150.0)

        val native = assertIs<PlaybackSeekDecision.NativeSeek>(decision)
        assertEquals(30.0, native.targetPlayerPositionSeconds)
        assertEquals(PlaybackSeekRestoration.SourcePosition, native.restoration)
    }

    @Test
    fun targetOutsideKnownWindowRequiresServerReanchor() {
        val decision = PlaybackTimeline(
            timelineOffsetSeconds = 120.0,
            canSeekAnywhere = true,
            seekWindowStartSeconds = 120.0,
            seekWindowEndSeconds = 180.0,
            seekRestoration = "source_position",
        ).decideSeek(200.0)

        val reanchor = assertIs<PlaybackSeekDecision.ServerReanchor>(decision)
        assertEquals(200.0, reanchor.targetSourcePositionSeconds)
        assertEquals(ServerReanchorReason.OutsideSeekWindow, reanchor.reason)
    }

    @Test
    fun nonGlobalTransportWithUnknownWindowReanchorsConservatively() {
        val decision = PlaybackTimeline(
            timelineOffsetSeconds = 120.0,
            canSeekAnywhere = false,
            seekRestoration = "source_position",
        ).decideSeek(150.0)

        val reanchor = assertIs<PlaybackSeekDecision.ServerReanchor>(decision)
        assertEquals(ServerReanchorReason.UnknownSeekWindow, reanchor.reason)
    }

    @Test
    fun partialWindowDoesNotProveNativeSeekability() {
        val decision = PlaybackTimeline(
            canSeekAnywhere = false,
            seekWindowStartSeconds = 120.0,
            seekRestoration = "source_position",
        ).decideSeek(150.0)

        val reanchor = assertIs<PlaybackSeekDecision.ServerReanchor>(decision)
        assertEquals(ServerReanchorReason.UnknownSeekWindow, reanchor.reason)
    }

    @Test
    fun malformedWindowReanchorsEvenWithGlobalSeekClaim() {
        val decision = PlaybackTimeline(
            canSeekAnywhere = true,
            seekWindowStartSeconds = 180.0,
            seekWindowEndSeconds = 120.0,
        ).decideSeek(150.0)

        val reanchor = assertIs<PlaybackSeekDecision.ServerReanchor>(decision)
        assertEquals(ServerReanchorReason.InvalidSeekWindow, reanchor.reason)
    }

    @Test
    fun unknownRestorationNeverAuthorizesNativeSeek() {
        val decision = PlaybackTimeline(
            canSeekAnywhere = true,
            seekRestoration = "future_mode",
        ).decideSeek(150.0)

        val reanchor = assertIs<PlaybackSeekDecision.ServerReanchor>(decision)
        assertEquals(PlaybackSeekRestoration.Unknown, reanchor.restoration)
        assertEquals(ServerReanchorReason.UnknownRestoration, reanchor.reason)
    }

    @Test
    fun unrepresentablePlayerTargetRequiresServerReanchor() {
        val decision = PlaybackTimeline(
            timelineOffsetSeconds = 120.0,
            canSeekAnywhere = true,
        ).decideSeek(100.0)

        val reanchor = assertIs<PlaybackSeekDecision.ServerReanchor>(decision)
        assertEquals(ServerReanchorReason.InvalidTimelineMapping, reanchor.reason)
    }
}
