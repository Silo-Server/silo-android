package org.siloserver.silo.common.player.seek

import org.siloserver.silo.model.playback.PlaybackTimeline
import org.siloserver.silo.model.playback.PlaybackTimelineV3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PlaybackTimelineSeekPolicyTest {
    @Test
    fun `replan remount restores current source position on reused transport timeline`() {
        val timeline = PlaybackTimelineV3(
            sourceStartSeconds = 4_946.708,
            playerStartSeconds = 0.001,
            timelineOffsetSeconds = 4_946.708,
        )

        val mount = timeline.replanMountPositionForSource(5_103.58)

        assertEquals(156.872, mount.playerPositionSeconds, absoluteTolerance = 0.000_001)
        assertEquals(5_103.58, mount.sourcePositionSeconds)
    }

    @Test
    fun `replan remount maps a newly anchored transport to its local start`() {
        val timeline = PlaybackTimeline(
            sourceStartSeconds = 321.25,
            playerStartSeconds = 0.0,
            timelineOffsetSeconds = 321.25,
        )

        val mount = timeline.replanMountPositionForSource(321.25)

        assertEquals(0.0, mount.playerPositionSeconds)
        assertEquals(321.25, mount.sourcePositionSeconds)
    }

    @Test
    fun `replan remount falls back to plan start for invalid source position`() {
        val timeline = PlaybackTimeline(
            sourceStartSeconds = 90.0,
            playerStartSeconds = 0.5,
            timelineOffsetSeconds = 89.5,
        )

        val mount = timeline.replanMountPositionForSource(Double.NaN)

        assertEquals(0.5, mount.playerPositionSeconds)
        assertEquals(90.0, mount.sourcePositionSeconds)
    }

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

    /** An open server window cannot override a proven mounted extent: targets inside it seek natively. */
    @Test
    fun mountedExtentHintAllowsNativeSeekInsideAnOpenWindow() {
        val decision = PlaybackTimeline(
            timelineOffsetSeconds = 120.0,
            canSeekAnywhere = false,
            seekWindowStartSeconds = 120.0,
            seekRestoration = "source_position",
        ).decideSeek(150.0, mountedSeekableSourceRange = 120.0..240.0)

        val native = assertIs<PlaybackSeekDecision.NativeSeek>(decision)
        assertEquals(30.0, native.targetPlayerPositionSeconds)
        assertEquals(PlaybackSeekRestoration.SourcePosition, native.restoration)
    }

    /** Beyond the mounted extent the hint proves nothing, so the conservative server reanchor applies. */
    @Test
    fun mountedExtentHintDoesNotCoverTargetsBeyondTheProvedExtent() {
        val decision = PlaybackTimeline(
            timelineOffsetSeconds = 120.0,
            canSeekAnywhere = false,
            seekWindowStartSeconds = 120.0,
            seekRestoration = "source_position",
        ).decideSeek(300.0, mountedSeekableSourceRange = 120.0..240.0)

        val reanchor = assertIs<PlaybackSeekDecision.ServerReanchor>(decision)
        assertEquals(ServerReanchorReason.UnknownSeekWindow, reanchor.reason)
    }

    /** The hint never overrides published window bounds: below the window start still reanchors. */
    @Test
    fun mountedExtentHintCannotRescueTargetsOutsideThePublishedWindow() {
        val decision = PlaybackTimeline(
            timelineOffsetSeconds = 120.0,
            canSeekAnywhere = false,
            seekWindowStartSeconds = 120.0,
            seekRestoration = "source_position",
        ).decideSeek(60.0, mountedSeekableSourceRange = 0.0..240.0)

        val reanchor = assertIs<PlaybackSeekDecision.ServerReanchor>(decision)
        assertEquals(ServerReanchorReason.OutsideSeekWindow, reanchor.reason)
    }
}
