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
}
