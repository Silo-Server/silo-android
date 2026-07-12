package org.siloserver.silo.tv.ui.screens.calendar

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvCalendarSizingSourceTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/calendar/TvCalendarScreen.kt",
    ).readText()

    @Test
    fun calendarCellsMatchTvosPortraitCardAtReducedScale() {
        // tvOS CalendarEventCard parity (QA 2026-07-08): portrait poster with
        // the caption below, sized so a full day shelf fits above the fold.
        assertTrue(source.contains("private val posterWidth = 124.dp"))
        assertTrue(source.contains("private val posterHeight = 186.dp"))
        // Meaningless midnight timestamps stay hidden.
        assertTrue(source.contains("item.localDisplayAirTime()?.let { airTime ->"))
    }

    @Test
    fun calendarShelvesUseHalfScaleTvosCardSpacing() {
        assertTrue(source.contains("private val CalendarCardSpacing = 18.dp"))
        assertTrue(source.contains("horizontalArrangement = Arrangement.spacedBy(CalendarCardSpacing)"))
    }
}
