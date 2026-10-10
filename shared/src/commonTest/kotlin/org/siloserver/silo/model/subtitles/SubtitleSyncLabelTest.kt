package org.siloserver.silo.model.subtitles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleSyncLabelTest {
    private val identity = SubtitleTiming()

    private fun job(status: String, result: SubtitleTiming? = null) = SubtitleSyncJob(status = status, result = result)

    @Test
    fun formatsOffsetsWithOneDecimal() {
        assertEquals("−3.2 s", formatSyncOffset(-3_200))
        assertEquals("+1.4 s", formatSyncOffset(1_400))
        assertEquals("+2.0 s", formatSyncOffset(2_000))
        assertEquals("−6.2 s", formatSyncOffset(-6_192))
        assertEquals("+0.0 s", formatSyncOffset(0))
    }

    @Test
    fun namesFrameRateConversionsAndSpeedFactors() {
        assertEquals("25→23.976 fps", describeSyncScale(25.0 / 23.976))
        assertEquals("×1.0008 speed", describeSyncScale(1.0008))
        assertEquals("×0.9991 speed", describeSyncScale(0.9991277785047734))
        assertEquals("×1.1 speed", describeSyncScale(1.1))
        assertEquals("×1 speed", describeSyncScale(1.00001))
        assertNull(describeSyncScale(1.0))
    }

    @Test
    fun matchesTheWebPlayerWording() {
        assertEquals("Syncing…", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.PENDING)))
        assertEquals("Syncing…", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.RUNNING)))
        assertEquals("Doesn't match this video", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.NO_MATCH)))
        assertEquals("Sync failed", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.FAILED)))
        assertEquals("Already in sync", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.ALREADY_SYNCED)))

        val offset = SubtitleTiming(offsetMs = -3_200)
        assertEquals("Synced −3.2 s", subtitleSyncStatusLabel(offset, job(SubtitleSyncJob.SYNCED, offset)))
        val drift = SubtitleTiming(offsetMs = -23_700, scale = 1.0008)
        assertEquals("Synced −23.7 s · ×1.0008 speed", subtitleSyncStatusLabel(drift, job(SubtitleSyncJob.SYNCED, drift)))
        val frameRate = SubtitleTiming(offsetMs = 1_400, scale = 25.0 / 23.976)
        assertEquals(
            "Synced +1.4 s · 25→23.976 fps",
            subtitleSyncStatusLabel(frameRate, job(SubtitleSyncJob.SYNCED, frameRate)),
        )
    }

    @Test
    fun reportsResetsAndManualChanges() {
        // A synced subtitle that was reset afterwards plays its original timing.
        val found = SubtitleTiming(offsetMs = -3_200)
        assertEquals("Original timing", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.SYNCED, found)))
        // Timing that differs from the job's result was set some other way.
        assertEquals(
            "Timing adjusted +0.5 s",
            subtitleSyncStatusLabel(SubtitleTiming(offsetMs = 500), job(SubtitleSyncJob.SYNCED, found)),
        )
        assertNull(subtitleSyncStatusLabel(identity, null))
    }
}
