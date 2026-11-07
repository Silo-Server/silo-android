package org.siloserver.silo.android.ui.util

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.android.ui.screens.player.formatPlaybackSpeed
import java.util.Locale
import kotlin.test.assertEquals

/** The shared formatters in the app language: US English as before, others in their own form. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FormattersLocaleTest {
    private val defaultBefore = Locale.getDefault()

    @After fun restoreDefault() = Locale.setDefault(defaultBefore)

    private fun <T> inLocale(tag: String, block: () -> T): T {
        Locale.setDefault(Locale.forLanguageTag(tag))
        return block()
    }

    @Test fun cardDates() {
        assertEquals("Jul 8, 2026", inLocale("en-US") { formatCardDate("2026-07-08") })
        assertEquals("8. Juli 2026", inLocale("de") { formatCardDate("2026-07-08") })
        assertEquals("2026年7月8日", inLocale("ja") { formatCardDate("2026-07-08T10:00:00Z") })
        assertEquals("٨ يوليو ٢٠٢٦", inLocale("ar") { formatCardDate("2026-07-08") })
    }

    @Test fun byteSizes() {
        assertEquals("1.5 KB", inLocale("en-US") { formatBytes(1_536) })
        assertEquals("1,5 KB", inLocale("de") { formatBytes(1_536) })
        assertEquals("١٫٥ KB", inLocale("ar") { formatBytes(1_536) })
    }

    @Test fun playbackSpeeds() {
        assertEquals("1.25", inLocale("en-US") { formatPlaybackSpeed(1.25) })
        assertEquals("2", inLocale("en-US") { formatPlaybackSpeed(2.0) })
        assertEquals("1,25", inLocale("de") { formatPlaybackSpeed(1.25) })
        assertEquals("١٫٢٥", inLocale("ar") { formatPlaybackSpeed(1.25) })
    }

    @Test fun clockTimes() {
        assertEquals("1:02:03", inLocale("en-US") { formatClockTime(3723.0) })
        assertEquals("١:٠٢:٠٣", inLocale("ar") { formatClockTime(3723.0) })
    }
}
