package org.siloserver.silo.tv.ui.screens.detail

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.Season
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TvSeasonPresentationLabelTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun pickerLabelsSeasonZeroWithoutSpecialsFlagAsSpecials() {
        assertEquals(
            "Specials",
            tvSeasonPickerLabel(resources, Season(contentId = "specials", seasonNumber = 0)),
        )
    }

    @Test
    fun pickerLabelsNonzeroSeasonWithSpecialsFlagAsSpecials() {
        assertEquals(
            "Specials",
            tvSeasonPickerLabel(
                resources,
                Season(contentId = "bonus", seasonNumber = 99, isSpecials = true),
            ),
        )
    }

    @Test
    fun explicitSpecialsEpisodeUsesSpecialsHeader() {
        val detail = ItemDetail(
            contentId = "episode-special",
            type = "episode",
            title = "Bonus",
            seasonNumber = 0,
        )

        assertEquals("Specials", episodeEyebrowLabel(resources, detail, TvItemDetailUiState()))
    }

    @Test
    fun specialsOnlySeriesUsesSpecialsHeaderAndPickerLabel() {
        val specials = Season(contentId = "specials", seasonNumber = 0)
        val detail = ItemDetail(
            contentId = "series",
            type = "series",
            title = "Series",
        )
        val state = TvItemDetailUiState(
            seasons = listOf(specials),
            selectedSeason = 0,
        )

        assertEquals("Specials", episodeEyebrowLabel(resources, detail, state))
        assertEquals("Specials", tvSeasonPickerLabel(resources, specials))
    }
}
