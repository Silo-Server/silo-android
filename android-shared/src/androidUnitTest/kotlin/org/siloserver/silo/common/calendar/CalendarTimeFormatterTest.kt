package org.siloserver.silo.common.calendar

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.calendar.CalendarItem
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarTimeFormatterTest {
    private val item = CalendarItem(
        contentId = "c",
        type = "episode",
        title = "Show",
        airDate = "2026-07-08",
        airAt = "2026-07-08T20:05:00Z",
        localAirDate = "2026-07-08",
    )

    private fun at(locale: Locale) = item.localDisplayAirTime(zoneId = ZoneOffset.UTC, locale = locale)

    @Test fun airTimeUsesTheLanguagesClock() {
        assertEquals("8:05 PM", at(Locale.US))
        assertEquals("20:05", at(Locale.GERMAN))
        assertEquals("20:05", at(Locale.JAPANESE))
        assertEquals("٨:٠٥ م", at(Locale.forLanguageTag("ar")))
    }
}
