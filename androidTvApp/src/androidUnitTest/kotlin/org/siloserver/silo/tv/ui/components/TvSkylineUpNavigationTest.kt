package org.siloserver.silo.tv.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class TvSkylineUpNavigationTest {

    @Test
    fun heldUpStopsOnFirstContentRow() {
        assertEquals(
            TvSkylineUpAction.StayInContent,
            tvSkylineUpAction(
                currentRow = 0,
                rowCount = 6,
                isRepeat = true,
                relocationInFlight = false,
            ),
        )
    }

    @Test
    fun freshUpFromFirstContentRowMayEnterMenu() {
        assertEquals(
            TvSkylineUpAction.EnterMenu,
            tvSkylineUpAction(
                currentRow = 0,
                rowCount = 6,
                isRepeat = false,
                relocationInFlight = false,
            ),
        )
    }

    @Test
    fun repeatedInputDuringOffscreenRelocationIsConsumed() {
        assertEquals(
            TvSkylineUpAction.StayInContent,
            tvSkylineUpAction(
                currentRow = 4,
                rowCount = 6,
                isRepeat = true,
                relocationInFlight = true,
            ),
        )
    }

    @Test
    fun ordinaryUpWithinRowsTriesExactlyOnePreviousRow() {
        assertEquals(
            TvSkylineUpAction.TryPreviousRow,
            tvSkylineUpAction(
                currentRow = 4,
                rowCount = 6,
                isRepeat = false,
                relocationInFlight = false,
            ),
        )
    }

    @Test
    fun staleFirstRowIndexWhileBandIsScrolledDownStepsToPreviousRow() {
        // The card focus callback lagged (or was clamped by a row refresh) and
        // still says row 0, but the band shows row 3 at its top: a fast Up must
        // step up, not leave for the menu.
        assertEquals(
            TvSkylineUpAction.TryPreviousRow,
            tvSkylineUpAction(
                currentRow = 0,
                rowCount = 6,
                isRepeat = false,
                relocationInFlight = false,
                bandTopRow = 3,
            ),
        )
        assertEquals(3, tvSkylineEffectiveRow(focusedRow = 0, bandTopRow = 3, rowCount = 6))
    }

    @Test
    fun unknownFocusedRowFallsBackToBandTopRow() {
        assertEquals(
            TvSkylineUpAction.TryPreviousRow,
            tvSkylineUpAction(
                currentRow = -1,
                rowCount = 6,
                isRepeat = false,
                relocationInFlight = false,
                bandTopRow = 2,
            ),
        )
        assertEquals(
            TvSkylineUpAction.EnterMenu,
            tvSkylineUpAction(
                currentRow = -1,
                rowCount = 6,
                isRepeat = false,
                relocationInFlight = false,
                bandTopRow = 0,
            ),
        )
    }
}
