package org.siloserver.silo.common.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiloLoadControlTest {
    @Test
    fun ordinaryPlaybackKeepsTheDeviceBufferBudget() {
        assertEquals(
            96 * 1024 * 1024,
            playbackBufferBudgetBytes(
                baseBudgetBytes = 96 * 1024 * 1024,
                hasDolbyVision = false,
                minimumBytes = SiloLoadControl.MIN_TARGET_BUFFER_BYTES,
            ),
        )
    }

    @Test
    fun dolbyVisionLeavesHalfOfTheOrdinaryAllocatorBudgetAsHeapHeadroom() {
        assertEquals(
            48 * 1024 * 1024,
            playbackBufferBudgetBytes(
                baseBudgetBytes = 96 * 1024 * 1024,
                hasDolbyVision = true,
                minimumBytes = SiloLoadControl.MIN_TARGET_BUFFER_BYTES,
            ),
        )
    }

    @Test
    fun dolbyVisionAdjustmentNeverRaisesOrUndercutsAConstrainedBudget() {
        val constrained = 8 * 1024 * 1024

        assertEquals(
            constrained,
            playbackBufferBudgetBytes(
                baseBudgetBytes = constrained,
                hasDolbyVision = true,
                minimumBytes = SiloLoadControl.MIN_TARGET_BUFFER_BYTES,
            ),
        )
    }

    @Test
    fun dolbyVisionBufferTrackRecognizesMimeAndCodecSignals() {
        assertTrue(isDolbyVisionBufferTrack("video/dolby-vision", null))
        listOf("dvhe.08.06", "dvh1.05.06", "dva1.09.01", "dvav.09.01").forEach { codec ->
            assertTrue(isDolbyVisionBufferTrack("video/hevc", codec))
        }
        assertFalse(isDolbyVisionBufferTrack("video/hevc", "hvc1.2.4.L153.B0"))
    }

    @Test
    fun `average bitrate takes precedence over peak bitrate`() {
        val selected =
            selectBufferSizingBitrateBps(
                listOf(
                    BufferSizingTrackBitrates(
                        averageBitrateBps = 4_000_000,
                        peakBitrateBps = 9_000_000,
                        latestNetworkEstimateBps = 100_000_000L,
                    ),
                ),
            )

        assertEquals(4_000_000L, selected)
    }

    @Test
    fun `peak bitrate is used when average bitrate is invalid`() {
        val selected =
            selectBufferSizingBitrateBps(
                listOf(
                    BufferSizingTrackBitrates(
                        averageBitrateBps = -1,
                        peakBitrateBps = 9_000_000,
                        latestNetworkEstimateBps = 100_000_000L,
                    ),
                ),
            )

        assertEquals(9_000_000L, selected)
    }

    @Test
    fun `known selected media bitrates are summed and network capacity is ignored`() {
        val selected =
            selectBufferSizingBitrateBps(
                listOf(
                    BufferSizingTrackBitrates(4_000_000, 8_000_000, 100_000_000L),
                    BufferSizingTrackBitrates(-1, 192_000, 100_000_000L),
                ),
            )

        assertEquals(4_192_000L, selected)
    }

    @Test
    fun `known audio cannot hide an unknown high bitrate video track`() {
        val selected =
            selectBufferSizingBitrateBps(
                listOf(
                    BufferSizingTrackBitrates(384_000, 384_000, 100_000_000L),
                    BufferSizingTrackBitrates(-1, -1, 100_000_000L),
                ),
            )

        assertEquals(100_384_000L, selected)
    }

    @Test
    fun `partial media metadata stays unknown until a network estimate exists`() {
        val selected =
            selectBufferSizingBitrateBps(
                listOf(
                    BufferSizingTrackBitrates(384_000, 384_000, 100_000_000L),
                    BufferSizingTrackBitrates(-1, -1, -1L),
                ),
            )

        assertNull(selected)
    }

    @Test
    fun `largest network estimate is the last resort when all media metadata is invalid`() {
        val selected =
            selectBufferSizingBitrateBps(
                listOf(
                    BufferSizingTrackBitrates(0, -1, 18_000_000L),
                    BufferSizingTrackBitrates(-1, 0, 25_000_000L),
                ),
            )

        assertEquals(25_000_000L, selected)
    }

    @Test
    fun `unknown bitrate remains unknown when metadata and network estimates are invalid`() {
        val selected =
            selectBufferSizingBitrateBps(
                listOf(
                    BufferSizingTrackBitrates(-1, 0, -1L),
                ),
            )

        assertNull(selected)
    }

    @Test
    fun `empty track selection remains unknown`() {
        assertNull(selectBufferSizingBitrateBps(emptyList()))
    }

    @Test
    fun `depth follows the budget honestly, even below the floor`() {
        // 60 Mbps against a 48 MiB budget: accounting for the same 15%
        // overhead margin calculateBitrateTargetBufferBytes applies when it
        // turns this depth back into bytes, the budget only really affords
        // ~5.8s. The requested 180s cannot be held, and neither can the 20s
        // floor — the budget wins over the floor because a false, rounded-up
        // report would be worse than an honest shortfall.
        val depth =
            affordableDepthMs(
                desiredDepthMs = 180_000,
                selectedBitrateBps = 60_000_000L,
                budgetBytes = 48 * 1024 * 1024,
                minimumDepthMs = 20_000,
            )

        assertTrue("expected reduction below the floor, got $depth", depth < 20_000)
        assertEquals("should report the honest budget-derived value", 5_835, depth)
    }

    @Test
    fun `depth is left alone when the budget can fund it`() {
        // 5 Mbps against 160 MiB: ~268s available, more than the 180s asked for.
        val depth =
            affordableDepthMs(
                desiredDepthMs = 180_000,
                selectedBitrateBps = 5_000_000L,
                budgetBytes = 160 * 1024 * 1024,
                minimumDepthMs = 20_000,
            )

        assertEquals(180_000, depth)
    }

    @Test
    fun `depth falls back to the request when the bitrate is unknown`() {
        val depth =
            affordableDepthMs(
                desiredDepthMs = 120_000,
                selectedBitrateBps = null,
                budgetBytes = 96 * 1024 * 1024,
                minimumDepthMs = 20_000,
            )

        assertEquals(120_000, depth)
    }

    @Test
    fun `composed sizing derives depth from the budget and sizes bytes just under the ceiling`() {
        // A 40 Mbps stream on a 48 MiB budget cannot hold the 180s the policy
        // asks for; the budget-derived depth (~8.75s, once the overhead
        // margin is accounted for) is neither the request nor the 20s floor.
        // The resulting byte target is sized from that depth and lands at or
        // just under the budget — not the (distinct) fallback — which is
        // exactly what proves the depth actually determines the bytes,
        // rather than both overshooting and clamping to the same ceiling
        // regardless of which depth was used.
        val budgetBytes = 48 * 1024 * 1024
        val fallbackBytes = 30 * 1024 * 1024 // distinct from budgetBytes: catches a maximumBytes mix-up
        val result =
            computeBufferSizing(
                selectedBitrateBps = 40_000_000L,
                desiredDepthMs = PlaybackBufferPolicy.MAX_DEPTH_MS,
                minimumDepthMs = PlaybackBufferPolicy.MIN_DEPTH_MS,
                budgetBytes = budgetBytes,
                minimumBytes = SiloLoadControl.MIN_TARGET_BUFFER_BYTES,
                unknownBitrateFallbackBytes = fallbackBytes,
            )

        assertEquals("depth should be the honest budget-derived value", 8_753, result.depth.ms)
        assertTrue(
            "byte target ${result.target.bytes} should not exceed the budget $budgetBytes",
            result.target.bytes <= budgetBytes,
        )
        assertTrue(
            "byte target ${result.target.bytes} should land just under the budget, not clamp to it",
            result.target.bytes > budgetBytes - (budgetBytes / 50),
        )
    }

    @Test
    fun `composed sizing reports the true budget-limited depth, not just a clamped byte target`() {
        // Both a correctly-routed depth and an un-routed, un-reduced one can
        // produce the same clamped byte target once the byte clamp is hit —
        // that erasure is exactly how a wiring bug that never routes the
        // affordable depth into the byte calculation went undetected. This
        // asserts the depth itself, which is the only place such a bug is
        // visible: 60 Mbps against a 48 MiB budget affords ~5.8s once the
        // overhead margin is accounted for.
        val result =
            computeBufferSizing(
                selectedBitrateBps = 60_000_000L,
                desiredDepthMs = PlaybackBufferPolicy.MAX_DEPTH_MS,
                minimumDepthMs = PlaybackBufferPolicy.MIN_DEPTH_MS,
                budgetBytes = 48 * 1024 * 1024,
                minimumBytes = SiloLoadControl.MIN_TARGET_BUFFER_BYTES,
                unknownBitrateFallbackBytes = 48 * 1024 * 1024,
            )

        assertEquals("depth should be the true budget-limited value", 5_835, result.depth.ms)
    }

    @Test
    fun `composed sizing routes the fallback bytes when the bitrate is unknown`() {
        // With no bitrate to size from, the requested depth passes through
        // untouched and the byte target must come from the caller-supplied
        // fallback (what the superclass computed) rather than the budget.
        val fallbackBytes = 40 * 1024 * 1024
        val result =
            computeBufferSizing(
                selectedBitrateBps = null,
                desiredDepthMs = 120_000,
                minimumDepthMs = PlaybackBufferPolicy.MIN_DEPTH_MS,
                budgetBytes = 160 * 1024 * 1024,
                minimumBytes = SiloLoadControl.MIN_TARGET_BUFFER_BYTES,
                unknownBitrateFallbackBytes = fallbackBytes,
            )

        assertEquals("depth should pass through unchanged", 120_000, result.depth.ms)
        assertEquals("byte target should route the fallback", fallbackBytes, result.target.bytes)
    }

    @Test
    fun `composed sizing never asks for a deeper buffer than the policy requested`() {
        // A reduction must only ever shrink the depth, never grow it —
        // growing it would widen the fixed idle window between min and max.
        val desiredDepthMs = PlaybackBufferPolicy.MAX_DEPTH_MS
        val result =
            computeBufferSizing(
                selectedBitrateBps = 80_000_000L,
                desiredDepthMs = desiredDepthMs,
                minimumDepthMs = PlaybackBufferPolicy.MIN_DEPTH_MS,
                budgetBytes = 48 * 1024 * 1024,
                minimumBytes = SiloLoadControl.MIN_TARGET_BUFFER_BYTES,
                unknownBitrateFallbackBytes = 48 * 1024 * 1024,
            )

        assertTrue(
            "depth ${result.depth.ms} exceeded requested $desiredDepthMs",
            result.depth.ms <= desiredDepthMs,
        )
    }

    @Test
    fun `composed sizing keeps the budget authoritative when it is below the nominal byte floor`() {
        // MIN_TARGET_BUFFER_BYTES and the policy's memory floor are both
        // 16 MiB today, so nothing on shipping hardware reaches this case.
        // A future change to either constant could separate them, and the
        // relation must then be restored by lowering the floor, not raising
        // the ceiling: a device allowed 8 MiB must get 8 MiB, not the 16 MiB
        // floor its heap cannot hold. Both a known and an unknown bitrate are
        // exercised, since the unknown path routes a caller-supplied fallback
        // that is itself larger than the budget here.
        val budgetBytes = 8 * 1024 * 1024

        for (bitrate in listOf(6_000_000L, null)) {
            val result =
                computeBufferSizing(
                    selectedBitrateBps = bitrate,
                    desiredDepthMs = PlaybackBufferPolicy.MAX_DEPTH_MS,
                    minimumDepthMs = PlaybackBufferPolicy.MIN_DEPTH_MS,
                    budgetBytes = budgetBytes,
                    minimumBytes = SiloLoadControl.MIN_TARGET_BUFFER_BYTES,
                    unknownBitrateFallbackBytes = 40 * 1024 * 1024,
                )

            assertTrue(
                "byte target ${result.target.bytes} exceeded the budget $budgetBytes at bitrate $bitrate",
                result.target.bytes <= budgetBytes,
            )
        }
    }
}
