package org.siloserver.silo.android.ui.screens.detail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.Season
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SeasonPresentationLabelTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun seasonZeroWithoutSpecialsFlagUsesSpecialsLabel() {
        assertEquals(
            "Specials",
            phoneSeasonLabel(resources, Season(contentId = "specials", seasonNumber = 0)),
        )
    }

    @Test
    fun nonzeroSeasonWithSpecialsFlagUsesSpecialsLabel() {
        assertEquals(
            "Specials",
            phoneSeasonLabel(
                resources,
                Season(contentId = "bonus", seasonNumber = 99, isSpecials = true),
            ),
        )
    }

    @Test
    fun specialsOnlySelectionUsesSpecialsSectionHeader() {
        assertEquals(
            "Specials",
            seriesSeasonSectionLabel(resources, Season(contentId = "specials", seasonNumber = 0)),
        )
        assertEquals(
            "Specials Episodes",
            seriesSeasonSectionTitle(resources, Season(contentId = "specials", seasonNumber = 0)),
        )
    }

    @Test
    fun regularSeasonUsesIosCombinedEpisodeHeading() {
        assertEquals(
            "Season 1 Episodes",
            seriesSeasonSectionTitle(resources, Season(contentId = "season-1", seasonNumber = 1)),
        )
    }

    @Test
    fun specialsDownloadAccessibilityCopyNeverUsesSeasonZero() {
        val specials = Season(contentId = "specials", seasonNumber = 0)

        assertEquals("Specials downloaded", seasonDownloadContentDescription(resources, specials, true))
        assertEquals("Download specials", seasonDownloadContentDescription(resources, specials, false))
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

        assertEquals("Specials · Episode 3", HeroMetadata.episodeEyebrow(resources, detail))
    }
}
