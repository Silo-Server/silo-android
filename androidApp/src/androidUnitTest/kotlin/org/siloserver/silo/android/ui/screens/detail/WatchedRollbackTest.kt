package org.siloserver.silo.android.ui.screens.detail

import org.siloserver.silo.model.catalog.LeafItemUserData
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchedRollbackTest {
    private val started = LeafItemUserData(played = false, isInProgress = true, positionSeconds = 300.0, durationSeconds = 3000.0)

    @Test
    fun anUntouchedToggleRestoresTheSnapshot() {
        assertEquals(started, started.withPlayed(true).withoutPlayed(true, started))
    }

    @Test
    fun progressRecordedSinceTheToggleStaysWhileWatchedIsUndone() {
        val sampled = started.withPlayed(true).copy(isInProgress = true, positionSeconds = 900.0)

        assertEquals(sampled.copy(played = false), sampled.withoutPlayed(true, started))
    }

    @Test
    fun aReloadThatNoLongerShowsTheToggleIsKept() {
        val reloaded = LeafItemUserData(played = false, isInProgress = true, positionSeconds = 1200.0)

        assertEquals(reloaded, reloaded.withoutPlayed(true, started))
    }
}
