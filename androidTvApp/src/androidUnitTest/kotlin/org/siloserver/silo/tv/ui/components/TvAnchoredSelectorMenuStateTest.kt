package org.siloserver.silo.tv.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun option(
    key: String,
    selected: Boolean = false,
    enabled: Boolean = true,
) = TvSelectorOption(
    key = key,
    title = key,
    detail = "",
    selected = selected,
    onSelect = {},
    enabled = enabled,
)

class TvAnchoredSelectorMenuStateTest {
    @Test
    fun expandedSelectorStaysClosedAfterInteractivityReturns() {
        var expanded = true

        expanded = selectorExpansionAfterInteractivityChange(
            expanded = expanded,
            interactive = true,
        )
        assertTrue(expanded)

        expanded = selectorExpansionAfterInteractivityChange(
            expanded = expanded,
            interactive = false,
        )
        assertFalse(expanded)

        expanded = selectorExpansionAfterInteractivityChange(
            expanded = expanded,
            interactive = true,
        )
        assertFalse(expanded)
    }

    // A subtitle list longer than the screen is the case that broke: the walk
    // has to keep producing indices past the last on-screen row, because those
    // are precisely the rows Compose's own focus search would not reach.
    @Test
    fun walkContinuesPastTheRowsThatFitOnScreen() {
        val options = List(30) { option("sub$it") }

        var index = initialSelectorMenuIndex(options)
        val visited = mutableListOf(index)
        while (true) {
            val next = nextSelectorMenuIndex(options, index, forward = true) ?: break
            index = next
            visited += index
        }

        assertEquals(30, visited.size)
        assertEquals(29, visited.last())
    }

    @Test
    fun walkStopsAtBothEndsInsteadOfLeavingTheMenu() {
        val options = listOf(option("a"), option("b"))

        assertNull(nextSelectorMenuIndex(options, from = 1, forward = true))
        assertNull(nextSelectorMenuIndex(options, from = 0, forward = false))
    }

    @Test
    fun walkStepsOverDisabledRows() {
        val options = listOf(
            option("auto"),
            option("unknown", enabled = false),
            option("english"),
        )

        assertEquals(2, nextSelectorMenuIndex(options, from = 0, forward = true))
        assertEquals(0, nextSelectorMenuIndex(options, from = 2, forward = false))
    }

    @Test
    fun menuOpensOnTheSelectedRow() {
        val options = listOf(option("auto"), option("off"), option("dutch", selected = true))

        assertEquals(2, initialSelectorMenuIndex(options))
    }

    // 10 rows of 100 fit a 1000 viewport; row 10 begins exactly past the fold.
    @Test
    fun scrollFollowsFocusPastTheFold() {
        val target = selectorMenuScrollTarget(
            scroll = 0,
            rowTop = 1000,
            rowHeight = 100,
            viewport = 1000,
            maxValue = 700,
        )

        assertEquals(100, target)
    }

    @Test
    fun scrollStaysPutForARowAlreadyOnScreen() {
        val target = selectorMenuScrollTarget(
            scroll = 300,
            rowTop = 400,
            rowHeight = 100,
            viewport = 1000,
            maxValue = 700,
        )

        assertEquals(300, target)
    }

    @Test
    fun scrollFollowsFocusBackUpwards() {
        val target = selectorMenuScrollTarget(
            scroll = 500,
            rowTop = 200,
            rowHeight = 100,
            viewport = 1000,
            maxValue = 700,
        )

        assertEquals(200, target)
    }

    @Test
    fun scrollNeverRunsPastTheEndOfTheList() {
        val target = selectorMenuScrollTarget(
            scroll = 0,
            rowTop = 5000,
            rowHeight = 100,
            viewport = 1000,
            maxValue = 700,
        )

        assertEquals(700, target)
    }

    // Before the first layout pass there is nothing measured to scroll against;
    // holding position beats guessing and yanking the list.
    @Test
    fun scrollHoldsWhenNothingHasBeenMeasuredYet() {
        assertEquals(250, selectorMenuScrollTarget(250, 900, 100, viewport = 0, maxValue = 700))
        assertEquals(250, selectorMenuScrollTarget(250, 900, 0, viewport = 1000, maxValue = 700))
    }

    // Aiming initial focus at a disabled row loses it silently: the row cannot
    // take focus, so the menu opens with focus nowhere and the d-pad dead.
    @Test
    fun menuReportsNoInitialRowWhenNothingIsSelectable() {
        val allDisabled = listOf(option("unknown", enabled = false), option("also-unknown", enabled = false))

        assertEquals(-1, initialSelectorMenuIndex(allDisabled))
        assertEquals(-1, initialSelectorMenuIndex(emptyList()))
    }

    // From that state a d-pad press must still reach the first selectable row.
    @Test
    fun walkFromNoInitialRowStillReachesTheFirstSelectableOne() {
        val options = listOf(option("unknown", enabled = false), option("english"))

        assertEquals(1, nextSelectorMenuIndex(options, from = -1, forward = true))
    }

    @Test
    fun menuOpensOnTheFirstSelectableRowWhenNothingIsSelected() {
        val options = listOf(option("unknown", enabled = false), option("english"))

        assertEquals(1, initialSelectorMenuIndex(options))
    }
}
