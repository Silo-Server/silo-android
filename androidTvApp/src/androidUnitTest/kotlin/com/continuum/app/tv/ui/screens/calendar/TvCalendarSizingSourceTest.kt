package com.continuum.app.tv.ui.screens.calendar

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvCalendarSizingSourceTest {
    private val source = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/calendar/TvCalendarScreen.kt",
    ).readText()

    @Test
    fun calendarCardsUseSharedTvosPosterTokens() {
        assertTrue(source.contains("import com.continuum.app.tv.ui.theme.RowDimens"))
        assertTrue(source.contains("private val cardWidth = RowDimens.PosterWidth"))
        assertTrue(source.contains("private val cardHeight = RowDimens.PosterHeight"))
        assertFalse(source.contains("private val cardWidth = 200.dp"))
    }

    @Test
    fun calendarShelvesUseHalfScaleTvosCardSpacing() {
        assertTrue(source.contains("private val CalendarCardSpacing = 20.dp"))
        assertTrue(source.contains("horizontalArrangement = Arrangement.spacedBy(CalendarCardSpacing)"))
    }
}
