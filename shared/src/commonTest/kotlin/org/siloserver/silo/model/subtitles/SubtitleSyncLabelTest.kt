package org.siloserver.silo.model.subtitles

import kotlin.test.Test
import org.siloserver.silo.common.i18n.english
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleSyncLabelTest {
    private val identity = SubtitleTiming()

    private fun job(status: String, result: SubtitleTiming? = null) = SubtitleSyncJob(status = status, result = result)

    @Test
    fun formatsOffsetsWithOneDecimal() {
        assertEquals("−3.2 s", formatSyncOffset(-3_200).english())
        assertEquals("+1.4 s", formatSyncOffset(1_400).english())
        assertEquals("+2.0 s", formatSyncOffset(2_000).english())
        assertEquals("−6.2 s", formatSyncOffset(-6_192).english())
        assertEquals("+0.0 s", formatSyncOffset(0).english())
    }

    @Test
    fun namesFrameRateConversionsAndSpeedFactors() {
        assertEquals("25→23.976 fps", describeSyncScale(25.0 / 23.976)?.english())
        assertEquals("×1.0008 speed", describeSyncScale(1.0008)?.english())
        assertEquals("×0.9991 speed", describeSyncScale(0.9991277785047734)?.english())
        assertEquals("×1.1 speed", describeSyncScale(1.1)?.english())
        assertEquals("×1 speed", describeSyncScale(1.00001)?.english())
        assertNull(describeSyncScale(1.0)?.english())
    }

    @Test
    fun matchesTheWebPlayerWording() {
        assertEquals("Syncing…", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.PENDING))?.english())
        assertEquals("Syncing…", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.RUNNING))?.english())
        assertEquals("Doesn't match this video", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.NO_MATCH))?.english())
        assertEquals("Sync failed", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.FAILED))?.english())
        assertEquals("Already in sync", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.ALREADY_SYNCED))?.english())

        val offset = SubtitleTiming(offsetMs = -3_200)
        assertEquals("Synced −3.2 s", subtitleSyncStatusLabel(offset, job(SubtitleSyncJob.SYNCED, offset))?.english())
        val drift = SubtitleTiming(offsetMs = -23_700, scale = 1.0008)
        assertEquals("Synced −23.7 s · ×1.0008 speed", subtitleSyncStatusLabel(drift, job(SubtitleSyncJob.SYNCED, drift))?.english())
        val frameRate = SubtitleTiming(offsetMs = 1_400, scale = 25.0 / 23.976)
        assertEquals(
            "Synced +1.4 s · 25→23.976 fps",
            subtitleSyncStatusLabel(frameRate, job(SubtitleSyncJob.SYNCED, frameRate))?.english(),
        )
    }

    @Test
    fun reportsResetsAndManualChanges() {
        // A synced subtitle that was reset afterwards plays its original timing.
        val found = SubtitleTiming(offsetMs = -3_200)
        assertEquals("Original timing", subtitleSyncStatusLabel(identity, job(SubtitleSyncJob.SYNCED, found))?.english())
        // Timing that differs from the job's result was set some other way.
        assertEquals(
            "Timing adjusted +0.5 s",
            subtitleSyncStatusLabel(SubtitleTiming(offsetMs = 500), job(SubtitleSyncJob.SYNCED, found))?.english(),
        )
        assertNull(subtitleSyncStatusLabel(identity, null)?.english())
    }
}
