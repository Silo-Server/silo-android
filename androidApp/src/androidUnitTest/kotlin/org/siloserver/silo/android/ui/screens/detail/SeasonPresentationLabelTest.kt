package org.siloserver.silo.android.ui.screens.detail

import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.Season
import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonPresentationLabelTest {
    @Test
    fun seasonZeroWithoutSpecialsFlagUsesSpecialsLabel() {
        assertEquals(
            "Specials",
            phoneSeasonLabel(Season(contentId = "specials", seasonNumber = 0)),
        )
    }

    @Test
    fun nonzeroSeasonWithSpecialsFlagUsesSpecialsLabel() {
        assertEquals(
            "Specials",
            phoneSeasonLabel(
                Season(contentId = "bonus", seasonNumber = 99, isSpecials = true),
            ),
        )
    }

    @Test
    fun specialsOnlySelectionUsesSpecialsSectionHeader() {
        assertEquals(
            "Specials",
            seriesSeasonSectionLabel(Season(contentId = "specials", seasonNumber = 0)),
        )
        assertEquals(
            "Specials Episodes",
            seriesSeasonSectionTitle(Season(contentId = "specials", seasonNumber = 0)),
        )
    }

    @Test
    fun regularSeasonUsesIosCombinedEpisodeHeading() {
        assertEquals(
            "Season 1 Episodes",
            seriesSeasonSectionTitle(Season(contentId = "season-1", seasonNumber = 1)),
        )
    }

    @Test
    fun specialsDownloadAccessibilityCopyNeverUsesSeasonZero() {
        val specials = Season(contentId = "specials", seasonNumber = 0)

        assertEquals("Specials downloaded", seasonDownloadContentDescription(specials, true))
        assertEquals("Download specials", seasonDownloadContentDescription(specials, false))
    }

    @Test
    fun specialsEpisodeEyebrowNeverUsesSeasonZero() {
        val detail = ItemDetail(
            contentId = "episode-special",
            type = "episode",
            title = "Bonus",
            seasonNumber = 0,
            episodeNumber = 3,
        )

        assertEquals("Specials · Episode 3", HeroMetadata.episodeEyebrow(detail))
    }
}
