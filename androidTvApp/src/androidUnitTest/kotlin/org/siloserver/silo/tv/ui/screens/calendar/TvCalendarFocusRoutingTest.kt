package org.siloserver.silo.tv.ui.screens.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvCalendarFocusRoutingTest {
    @Test
    fun firstFocusableShelfReturnsToControls() {
        assertTrue(shouldReturnCalendarFocusToControls(2, 2, false))
    }

    @Test
    fun laterShelfUsesNormalUpMovement() {
        assertFalse(shouldReturnCalendarFocusToControls(4, 2, false))
    }

    @Test
    fun controlsUseNormalUpMovement() {
        assertFalse(shouldReturnCalendarFocusToControls(null, 2, false))
    }

    @Test
    fun controlsReturnToShellMenuTarget() {
        assertEquals(
            CalendarUpFallbackAction.EnterMenu,
            calendarUpFallbackAction(
                focusedShelfIndex = null,
                firstFocusableShelfIndex = 2,
                isReturningToControls = false,
            ),
        )
    }

    @Test
    fun returnInFlightDoesNotRestartChoreography() {
        assertFalse(shouldReturnCalendarFocusToControls(2, 2, true))
    }

    @Test
    fun heldUpOnCalendarControlsStaysInContent() {
        assertEquals(
            CalendarUpFallbackAction.StayInContent,
            calendarUpFallbackAction(
                focusedShelfIndex = null,
                firstFocusableShelfIndex = 2,
                isReturningToControls = false,
                isRepeat = true,
            ),
        )
    }
}
