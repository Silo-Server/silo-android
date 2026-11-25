package org.siloserver.silo.model.section

import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeCardLabelTest {

    private fun item(type: String, title: String, seriesTitle: String?) =
        SectionItem(contentId = "c", type = type, title = title, seriesTitle = seriesTitle)

    @Test
    fun episodeCardsLeadWithTheirSeriesAndKeepADistinctEpisodeTitle() {
        val cases = listOf(
            item("episode", "Pilot", "Running Show") to EpisodeCardLabel("Running Show", "Pilot"),
            // A title that only repeats the series name (some scanners fill unknown titles that way) is dropped,
            // so the caption keeps just the episode code, as on Apple.
            item("episode", "running show ", "Running Show") to EpisodeCardLabel("Running Show", null),
            item("episode", " ", "Running Show") to EpisodeCardLabel("Running Show", null),
            item("episode", "Pilot", null) to null,
            item("episode", "Pilot", "  ") to null,
            item("movie", "Heat", "Some Collection") to null,
            item("series", "Running Show", "Running Show") to null,
        )
        for ((item, expected) in cases) {
            assertEquals(expected, item.episodeCardLabel(), "${item.type} '${item.title}' / ${item.seriesTitle}")
        }
    }
}
