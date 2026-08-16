package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the invariant the ordinal-auto-path bug violated: on TV exactly one
 * thing may enable or disable a text track, and it is the subtitle transaction
 * adapter.
 *
 * The old shape was a bare `SharedFlow<Int>` that the auto, persisted-restore
 * and detail-pick paths all emitted into without arming an owner. Playback then
 * obeyed those emissions while the HUD kept reporting the adapter's untouched
 * committed identity — subtitles on screen, "Off" in the HUD.
 *
 * Source-level because the failure is structural: a second emitter compiles and
 * passes every behavioural test right up until it races the adapter on a real
 * device.
 */
class TvSubtitleSingleOwnerSourceTest {
    private fun source(path: String): String {
        val moduleRelative = File("src/androidMain/kotlin/$path")
        val projectRelative = File("androidTvApp/src/androidMain/kotlin/$path")
        return (moduleRelative.takeIf(File::exists) ?: projectRelative).readText()
    }

    private val viewModel: String
        get() = source("org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt")

    private val screen: String
        get() = source("org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt")

    @Test
    fun theLegacyOrdinalSelectionChannelIsGone() {
        assertTrue(!viewModel.contains("_subtitleSelectRequests"))
        assertTrue(!screen.contains("subtitleSelectRequests"))
    }

    @Test
    fun onlyTheRemountLatchEmitsAMountRequest() {
        val emissions = Regex("_subtitleMountRequests\\.tryEmit").findAll(viewModel).count()
        assertEquals(1, emissions)

        val resolver = viewModel.substringAfter("private fun resolveSubtitleRemountReselection(")
            .substringBefore("\n    fun ")
        assertTrue(resolver.contains("_subtitleMountRequests.tryEmit"))
    }

    @Test
    fun theScreenIsTheOnlyPlayerFacingSubtitleSelector() {
        // Two calls, both inside the mount-request collector: the -1 disable
        // and the track selection.
        assertEquals(2, Regex("backend\\.selectSubtitle\\(").findAll(screen).count())
    }

    @Test
    fun anAppliedSelectionCarriesItsOwnerRatherThanLookingOneUp() {
        // The silent `pendingSubtitleMountAcknowledgement ?: return` bail-out is
        // what swallowed every app-originated selection. The owner now travels
        // with the request, so an ownerless mount is unrepresentable.
        assertTrue(!viewModel.contains("pendingSubtitleMountAcknowledgement"))
        assertTrue(
            viewModel.contains(
                "internal fun onSubtitleSelectionApplied(request: TvSubtitleMountRequest)",
            ),
        )
        assertTrue(
            viewModel.contains(
                "internal fun onSubtitleSelectionFailed(request: TvSubtitleMountRequest)",
            ),
        )
    }

    @Test
    fun appDerivedSelectionsGoThroughTheAdapter() {
        val auto = viewModel.substringAfter("private fun resolveAutoPreferredTextSubtitle(")
            .substringBefore("\n    /**")
        assertTrue(auto.contains("applyAutomaticSubtitleSelection"))

        val apply = viewModel.substringAfter("private fun applyAutomaticSubtitleSelection(")
            .substringBefore("\n    /**")
        assertTrue(apply.contains("subtitleTransactions.selectAuto"))
    }
}
