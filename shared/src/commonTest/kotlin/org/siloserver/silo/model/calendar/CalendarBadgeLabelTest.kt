package org.siloserver.silo.model.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarBadgeLabelTest {

    @Test
    fun `labels every badge the server sends in capitals`() {
        assertEquals(
            listOf("PREMIERE", "NEW SEASON", "FINALE", "DIGITAL", "PHYSICAL"),
            listOf("series_premiere", "season_premiere", "finale", "digital", "physical")
                .map(::calendarBadgeLabel),
        )
    }

    @Test
    fun `hides a badge it does not know`() {
        assertNull(calendarBadgeLabel("theatrical"))
    }
}
