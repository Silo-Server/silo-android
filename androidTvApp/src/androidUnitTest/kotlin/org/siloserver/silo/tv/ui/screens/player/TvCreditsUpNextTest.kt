package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.model.catalog.MarkerSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvCreditsUpNextTest {
    private val next = NextEpisodeState(contentId = "ep-2", seasonNumber = 1, episodeNumber = 2, title = "Two", stillUrl = null)

    // Credits, a mid-credits scene, then the credits Up Next follows.
    private val state = TvPlayerViewModel.UiState(
        nextEpisode = next,
        markerSegments = listOf(
            MarkerSegment("credits", 2_400.0, 2_460.0),
            MarkerSegment("credits", 2_520.0, 2_580.0),
        ),
    )

    @Test
    fun earlierCreditsRangeKeepsSkipCreditsSoTheSceneAfterItPlays() {
        assertNull(state.copy(position = 2_430.0).creditsUpNext())
    }

    @Test
    fun lastCreditsRangeNamesTheNextEpisodeUntilNotNow() {
        val inLastCredits = state.copy(position = 2_550.0)

        assertEquals(next, inLastCredits.creditsUpNext())
        assertNull(inLastCredits.copy(creditsUpNextDeclined = true).creditsUpNext())
    }
}
