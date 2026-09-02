package org.siloserver.silo.tv.ui.screens.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.model.catalog.ItemDetail

class TvSeriesDetailRedirectTest {
    @Test
    fun `episode redirects to its parent series with exact selection`() {
        assertEquals(
            TvSeriesDetailRedirect(
                seriesContentId = "series-a",
                seasonNumber = 3,
                episodeContentId = "episode-7",
            ),
            tvSeriesDetailRedirect(
                detail(
                    contentId = "episode-7",
                    type = "episode",
                    seriesId = "series-a",
                    seasonNumber = 3,
                ),
            ),
        )
    }

    @Test
    fun `season redirects to its parent series without selecting an episode`() {
        assertEquals(
            TvSeriesDetailRedirect(
                seriesContentId = "series-a",
                seasonNumber = 2,
                episodeContentId = null,
            ),
            tvSeriesDetailRedirect(
                detail(
                    contentId = "season-2",
                    type = "season",
                    seriesId = "series-a",
                    seasonNumber = 2,
                ),
            ),
        )
    }

    @Test
    fun `specials season zero remains a valid selection`() {
        assertEquals(
            0,
            tvSeriesDetailRedirect(
                detail(
                    contentId = "specials",
                    type = "SEASON",
                    seriesId = "series-a",
                    seasonNumber = 0,
                ),
            )?.seasonNumber,
        )
    }

    @Test
    fun `incomplete or self-referential hierarchy keeps standalone fallback`() {
        assertNull(
            tvSeriesDetailRedirect(
                detail(contentId = "episode-a", type = "episode", seasonNumber = 1),
            ),
        )
        assertNull(
            tvSeriesDetailRedirect(
                detail(contentId = "episode-a", type = "episode", seriesId = "series-a"),
            ),
        )
        assertNull(
            tvSeriesDetailRedirect(
                detail(
                    contentId = "series-a",
                    type = "episode",
                    seriesId = "series-a",
                    seasonNumber = 1,
                ),
            ),
        )
        assertNull(
            tvSeriesDetailRedirect(
                detail(contentId = "movie-a", type = "movie"),
            ),
        )
    }

    @Test
    fun `redirect parent must resolve as the requested series`() {
        assertTrue(
            detail(contentId = "series-a", type = "Series")
                .isMatchingSeriesDetail("series-a"),
        )
        assertFalse(
            detail(contentId = "series-b", type = "series")
                .isMatchingSeriesDetail("series-a"),
        )
        assertFalse(
            detail(contentId = "series-a", type = "season")
                .isMatchingSeriesDetail("series-a"),
        )
        assertFalse((null as ItemDetail?).isMatchingSeriesDetail("series-a"))
    }

    private fun detail(
        contentId: String,
        type: String,
        seriesId: String? = null,
        seasonNumber: Int? = null,
    ) = ItemDetail(
        contentId = contentId,
        type = type,
        title = contentId,
        seriesId = seriesId,
        seasonNumber = seasonNumber,
    )
}
