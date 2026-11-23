package org.siloserver.silo.common.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.siloserver.silo.common.data.db.entity.UserItemStateEntity

class LatestProgressTest {

    private fun row(fileId: Int, position: Double, updatedAtMs: Long) = UserItemStateEntity(
        serverId = "s1",
        accountId = "a1",
        profileId = "p1",
        contentId = "c1",
        fileId = fileId,
        positionSeconds = position,
        durationSeconds = 7200.0,
        audioFingerprint = null,
        subtitleFingerprint = null,
        cfi = null,
        readProgress = null,
        clientUpdatedAtMs = updatedAtMs,
        serverUpdatedAtMs = null,
    )

    @Test
    fun newestWriteWinsEvenWhenPositionMovedBackward() {
        // The regression: watch to 2400s, later seek back to 600s. The newer,
        // lower position is the real resume point.
        val progress = listOf(
            row(fileId = 1, position = 2400.0, updatedAtMs = 1_000),
            row(fileId = 1, position = 600.0, updatedAtMs = 2_000),
        ).latestProgress()

        assertEquals(600.0, progress?.positionSeconds)
    }

    @Test
    fun newestWriteWinsAcrossFileVersions() {
        val progress = listOf(
            row(fileId = 1, position = 2400.0, updatedAtMs = 1_000),
            row(fileId = 2, position = 300.0, updatedAtMs = 5_000),
        ).latestProgress()

        assertEquals(2, progress?.fileId)
        assertEquals(300.0, progress?.positionSeconds)
    }

    @Test
    fun zeroAndInvalidPositionsAreIgnored() {
        assertNull(
            listOf(
                row(fileId = 1, position = 0.0, updatedAtMs = 3_000),
                row(fileId = 1, position = Double.NaN, updatedAtMs = 4_000),
            ).latestProgress(),
        )
    }
}
