package org.siloserver.silo.model.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeDescriptionTranslatingTest {
    private data class Case(
        val name: String,
        val pendingLanguage: String?,
        val shownSeasonId: String?,
        val translatingId: String?,
        val expected: Boolean,
    )

    @Test fun showsProgressOnlyWhileTheShownSeasonsJobFillsThisEpisode() {
        val cases = listOf(
            Case("shown season job, episode pending", "de", "season-1", "season-1", true),
            Case("episode already translated", null, "season-1", "season-1", false),
            Case("another season's job", "de", "season-2", "season-1", false),
            Case("no job, no shown season", "de", null, null, false),
        )
        for (case in cases) {
            assertEquals(
                case.expected,
                isEpisodeDescriptionTranslating(
                    pendingLanguage = case.pendingLanguage,
                    shownSeasonContentId = case.shownSeasonId,
                    translatingContentId = case.translatingId,
                ),
                case.name,
            )
        }
    }
}
