package org.siloserver.silo.android.ui.screens.detail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DetailPlayLabelTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun episodeWithProgressShowsNeutralSeasonEpisodeLabel() {
        // iOS parity: the label stays neutral even with progress; the resume-vs-
        // restart choice is offered in the "Continue Watching?" dialog on tap.
        val detail = ItemDetail(
            contentId = "episode-1",
            type = "episode",
            title = "The Thief",
            seriesTitle = "The Rookie",
            seasonNumber = 6,
            episodeNumber = 4,
            userData = LeafItemUserData(
                played = false,
                positionSeconds = 318.0,
            ),
        )

        assertEquals("Play S6·E4", computePlayLabel(resources, detail))
    }

    @Test
    fun playedEpisodeWithRewatchProgressShowsNeutralLabel() {
        val detail = ItemDetail(
            contentId = "episode-1",
            type = "episode",
            title = "The Thief",
            seriesTitle = "The Rookie",
            seasonNumber = 6,
            episodeNumber = 4,
            userData = LeafItemUserData(
                played = true,
                positionSeconds = 318.0,
                durationSeconds = 3600.0,
            ),
        )

        assertEquals("Play S6·E4", computePlayLabel(resources, detail))
    }

    @Test
    fun episodeWithStaleCompletedPositionShowsSeasonEpisodeLabel() {
        val detail = ItemDetail(
            contentId = "episode-1",
            type = "episode",
            title = "The Thief",
            seriesTitle = "The Rookie",
            seasonNumber = 6,
            episodeNumber = 4,
            userData = LeafItemUserData(
                played = true,
                positionSeconds = 3600.0,
                durationSeconds = 3600.0,
            ),
        )

        assertEquals("Play S6·E4", computePlayLabel(resources, detail))
    }

    @Test
    fun episodeWithoutProgressShowsSeasonEpisodeLabel() {
        val detail = ItemDetail(
            contentId = "episode-1",
            type = "episode",
            title = "The Thief",
            seriesTitle = "The Rookie",
            seasonNumber = 6,
            episodeNumber = 4,
        )

        assertEquals("Play S6·E4", computePlayLabel(resources, detail))
    }
}
