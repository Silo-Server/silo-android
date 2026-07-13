package org.siloserver.silo.common.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackBufferPolicyTest {

    @Test
    fun profilesExposeExpectedStartupAndRebufferTargets() {
        val quick = PlaybackBufferPolicy.forMode(PlaybackBufferMode.QuickStart)
        val balanced = PlaybackBufferPolicy.forMode(PlaybackBufferMode.Balanced)
        val smooth = PlaybackBufferPolicy.forMode(PlaybackBufferMode.SmoothPlayback)

        assertEquals(2_000, quick.bufferForPlaybackMs)
        assertEquals(6_000, quick.bufferForPlaybackAfterRebufferMs)
        assertEquals(3_000, balanced.bufferForPlaybackMs)
        assertEquals(10_000, balanced.bufferForPlaybackAfterRebufferMs)
        assertEquals(5_000, smooth.bufferForPlaybackMs)
        assertEquals(15_000, smooth.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun profilesKeepBufferDurationsInValidOrder() {
        PlaybackBufferMode.entries.forEach { mode ->
            val policy = PlaybackBufferPolicy.forMode(mode)
            assertTrue(policy.bufferForPlaybackMs <= policy.minBufferMs, mode.name)
            assertTrue(policy.bufferForPlaybackAfterRebufferMs <= policy.minBufferMs, mode.name)
            assertTrue(policy.minBufferMs <= policy.maxBufferMs, mode.name)
        }
    }

    @Test
    fun quickStartUsesHeapBoundedByteCapForHighBitrate4kDirectPlay() {
        val policy = PlaybackBufferPolicy.forMode(PlaybackBufferMode.QuickStart, roomyDevice)

        assertEquals(128 * 1024 * 1024, policy.targetBufferBytes)
        assertFalse(policy.prioritizeTimeOverSizeThresholds)
        assertTrue(policy.maxBufferMs <= 60_000)
    }

    @Test
    fun smoothPlaybackUsesHeapBoundedByteCapForHighBitrateRemuxes() {
        val policy = PlaybackBufferPolicy.forMode(PlaybackBufferMode.SmoothPlayback, roomyDevice)

        assertEquals(192 * 1024 * 1024, policy.targetBufferBytes)
        assertFalse(policy.prioritizeTimeOverSizeThresholds)
        assertTrue(policy.maxBufferMs <= 180_000)
    }

    @Test
    fun smoothPlaybackPrioritizesDeepForwardBufferingOverQuickStartup() {
        val policy = PlaybackBufferPolicy.forMode(PlaybackBufferMode.SmoothPlayback)

        assertTrue(policy.bufferForPlaybackMs >= PlaybackBufferPolicy.forMode(PlaybackBufferMode.Balanced).bufferForPlaybackMs)
        assertTrue(policy.bufferForPlaybackAfterRebufferMs >= policy.bufferForPlaybackMs * 3)
        assertTrue(policy.minBufferMs >= policy.bufferForPlaybackAfterRebufferMs * 3)
        assertTrue(policy.maxBufferMs >= policy.minBufferMs * 2)
    }

    @Test
    fun allProfilesHaveFiniteTargetByteCaps() {
        assertEquals(128 * 1024 * 1024, PlaybackBufferPolicy.forMode(PlaybackBufferMode.QuickStart, roomyDevice).targetBufferBytes)
        assertEquals(160 * 1024 * 1024, PlaybackBufferPolicy.forMode(PlaybackBufferMode.Balanced, roomyDevice).targetBufferBytes)
        assertEquals(192 * 1024 * 1024, PlaybackBufferPolicy.forMode(PlaybackBufferMode.SmoothPlayback, roomyDevice).targetBufferBytes)
    }

    @Test
    fun lowMemoryDevicesUseSmallerByteCaps() {
        val lowMemory = PlaybackBufferDeviceProfile(memoryClassMb = 128, isLowRamDevice = true)

        assertEquals(32 * 1024 * 1024, PlaybackBufferPolicy.forMode(PlaybackBufferMode.QuickStart, lowMemory).targetBufferBytes)
        assertEquals(48 * 1024 * 1024, PlaybackBufferPolicy.forMode(PlaybackBufferMode.Balanced, lowMemory).targetBufferBytes)
        assertEquals(64 * 1024 * 1024, PlaybackBufferPolicy.forMode(PlaybackBufferMode.SmoothPlayback, lowMemory).targetBufferBytes)
    }

    @Test
    fun unknownDevicesUseConstrainedByteCapsUntilMemoryClassIsKnown() {
        assertEquals(
            32 * 1024 * 1024,
            PlaybackBufferPolicy.forMode(
                PlaybackBufferMode.QuickStart,
                PlaybackBufferDeviceProfile.Unknown,
            ).targetBufferBytes,
        )
        assertEquals(
            48 * 1024 * 1024,
            PlaybackBufferPolicy.forMode(
                PlaybackBufferMode.Balanced,
                PlaybackBufferDeviceProfile.Unknown,
            ).targetBufferBytes,
        )
        assertEquals(
            64 * 1024 * 1024,
            PlaybackBufferPolicy.forMode(
                PlaybackBufferMode.SmoothPlayback,
                PlaybackBufferDeviceProfile.Unknown,
            ).targetBufferBytes,
        )
    }

    @Test
    fun roomyDevicesKeepLargeByteCaps() {
        assertEquals(128 * 1024 * 1024, PlaybackBufferPolicy.forMode(PlaybackBufferMode.QuickStart, roomyDevice).targetBufferBytes)
        assertEquals(160 * 1024 * 1024, PlaybackBufferPolicy.forMode(PlaybackBufferMode.Balanced, roomyDevice).targetBufferBytes)
        assertEquals(192 * 1024 * 1024, PlaybackBufferPolicy.forMode(PlaybackBufferMode.SmoothPlayback, roomyDevice).targetBufferBytes)
    }

    @Test
    fun bitrateAwareTargetScalesLowBitrateStreamsBelowDeviceCap() {
        assertEquals(
            35_937_500,
            calculateBitrateTargetBufferBytes(
                selectedBitrateBps = 5_000_000,
                desiredForwardBufferMs = 50_000,
                minimumBytes = 16 * 1024 * 1024,
                maximumBytes = 160 * 1024 * 1024,
                unknownBitrateFallbackBytes = 96 * 1024 * 1024,
            ),
        )
    }

    @Test
    fun bitrateAwareTargetClampsHighBitrateRemuxesToDeviceCap() {
        assertEquals(
            160 * 1024 * 1024,
            calculateBitrateTargetBufferBytes(
                selectedBitrateBps = 100_000_000,
                desiredForwardBufferMs = 50_000,
                minimumBytes = 16 * 1024 * 1024,
                maximumBytes = 160 * 1024 * 1024,
                unknownBitrateFallbackBytes = 96 * 1024 * 1024,
            ),
        )
    }

    private companion object {
        val roomyDevice = PlaybackBufferDeviceProfile(memoryClassMb = 384, isLowRamDevice = false)
    }
}
