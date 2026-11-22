package org.siloserver.silo.common.i18n

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.model.catalog.EpisodeCodeStyle
import org.siloserver.silo.model.catalog.EpisodeLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/** An episode's label: its air date for a date-labeled series (sports), else its code. */
@RunWith(RobolectricTestRunner::class)
class EpisodeLabelTest {
    private val english = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `a dated episode of a date-labeled series shows its air date`() {
        assertEquals("Nov 20, 2026", EpisodeLabel.text(2026, 296, "air_date", "2026-11-20").text(english))
        // English data (a Watch Party item, a download's stored title) says the same.
        assertEquals("Nov 20, 2026", EpisodeLabel.englishText(2026, 296, "air_date", "2026-11-20"))
    }

    @Test
    fun `a numbered series shows the code in the surface's style`() {
        assertEquals("S01E02", EpisodeLabel.text(1, 2, null, "2026-11-20").text(english))
        assertEquals("S1 · E2", EpisodeLabel.text(1, 2, null, "2026-11-20", EpisodeCodeStyle.Spaced).text(english))
    }

    @Test
    fun `an undated episode of a date-labeled series keeps its code`() {
        assertEquals("S2026E296", EpisodeLabel.text(2026, 296, "air_date", null).text(english))
        // A date the month doesn't have can't be drawn, so the code stands in.
        assertEquals("S2026E296", EpisodeLabel.text(2026, 296, "air_date", "2026-02-30").text(english))
    }
}
