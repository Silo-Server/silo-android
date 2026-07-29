package org.siloserver.silo.tv.ui.screens.recommendations

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvRecommendationsFocusBridgeTest {

    @Test
    fun handoffCrossesRowRestorerBeforeTargetingFirstCard() = runTest {
        val events = mutableListOf<String>()

        val handled = requestRecommendationRowFocus(
            requestRowContainer = { events += "row"; true },
            awaitFrame = { events += "frame" },
            requestFirstCard = { events += "card"; true },
        )

        assertTrue(handled)
        assertEquals(listOf("row", "frame", "card"), events)
    }

    @Test
    fun rejectedRowHopDoesNotTargetCard() = runTest {
        val events = mutableListOf<String>()

        val handled = requestRecommendationRowFocus(
            requestRowContainer = { events += "row"; false },
            awaitFrame = { events += "frame" },
            requestFirstCard = { events += "card"; true },
        )

        assertFalse(handled)
        assertEquals(listOf("row"), events)
    }

    @Test
    fun forYouWithVisibleRowsUsesTheBridge() {
        assertTrue(
            shouldBridgeRecommendationsDown(
                showingRecommendations = true,
                hasVisibleRecommendations = true,
            ),
        )
    }

    @Test
    fun savedListsKeepTheirExistingGridNavigation() {
        assertFalse(
            shouldBridgeRecommendationsDown(
                showingRecommendations = false,
                hasVisibleRecommendations = true,
            ),
        )
    }

    @Test
    fun loadingOrEmptyForYouDoesNotTargetAnAbsentRow() {
        assertFalse(
            shouldBridgeRecommendationsDown(
                showingRecommendations = true,
                hasVisibleRecommendations = false,
            ),
        )
    }
}
