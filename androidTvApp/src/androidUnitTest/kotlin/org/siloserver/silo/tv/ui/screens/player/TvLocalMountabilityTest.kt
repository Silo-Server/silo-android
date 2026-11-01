package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.player.MountedSubtitleTrack
import org.siloserver.silo.common.player.subtitleArtifactTrackId
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.SelectedPlaybackTracks
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvLocalMountabilityTest {
    private fun plan(subtitleIndex: Int?) = PlaybackExecutionPlan(
        planId = "plan",
        delivery = PlaybackDelivery.ORIGINAL_HTTP,
        routeFamily = PlaybackRouteFamily.PLATFORM_NATIVE,
        selectedTracks = SelectedPlaybackTracks(subtitleIndex = subtitleIndex),
    )

    private fun track(index: Int, id: String) =
        MountedSubtitleTrack(index, id, "English", "en", "text/vtt", false, false)

    // An in-place switch to a native track left sidecar 3 in the MediaItem.
    private val mounted = listOf(track(0, subtitleArtifactTrackId(3)), track(1, "5"))

    @Test fun aSidecarCountsOnlyWhileThePlanSelectsIt() {
        val sidecar = SubtitleIdentity.ServerSidecar(3)
        assertTrue(isTvLocallyMountable(sidecar, plan(subtitleIndex = 3), emptyList(), mounted))
        assertFalse(isTvLocallyMountable(sidecar, plan(subtitleIndex = 4), emptyList(), mounted))
        assertFalse(isTvLocallyMountable(sidecar, plan(subtitleIndex = null), emptyList(), mounted))
    }

    @Test fun aNativeTrackTheMediaListsIsMountable() {
        val native = SubtitleIdentity.Embedded(4, SubtitleMediaIdentity(language = "en"), "5")
        assertTrue(isTvLocallyMountable(native, plan(subtitleIndex = 3), emptyList(), mounted))
        val missing = SubtitleIdentity.Embedded(5, SubtitleMediaIdentity(language = "en"), "6")
        assertFalse(isTvLocallyMountable(missing, plan(subtitleIndex = 3), emptyList(), mounted))
    }
}

class TvReopenSubtitleRestoreTest {
    private val native = SubtitleIdentity.Embedded(9, SubtitleMediaIdentity(language = "en"), "5")

    @Test fun aReopenKeepsThePickTheAdapterIsStillMounting() {
        // The stalled transport shows nothing (-1), but the pick is still being mounted.
        assertEquals(
            TvReopenSubtitleRestore.AdapterMount(native, 7L),
            tvReopenSubtitleRestore(native, 7L, playerSelectedServerIndex = -1),
        )
    }

    @Test fun withNothingInFlightAReopenRestoresWhatThePlayerShows() {
        assertEquals(TvReopenSubtitleRestore.PlayerSelection(-1), tvReopenSubtitleRestore(null, null, -1))
        assertEquals(TvReopenSubtitleRestore.PlayerSelection(9), tvReopenSubtitleRestore(null, null, 9))
    }
}
