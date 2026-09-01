package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.ui.graphics.Color
import java.io.File
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TvDetailHeroArtworkTest {
    @Test
    fun episodicDetailFallsBackToLandscapeEpisodeStill() {
        val detail = ItemDetail(
            contentId = "series-1",
            type = "series",
            title = "Series",
            posterUrl = "portrait-poster",
        )
        val nextUp = EpisodeListItem(
            contentId = "episode-1",
            seasonNumber = 1,
            episodeNumber = 2,
            stillUrl = "landscape-still",
            stillThumbhash = "still-hash",
        )

        assertEquals(
            TvDetailHeroArtwork("landscape-still", "still-hash"),
            resolveTvDetailHeroArtwork(detail, nextUp),
        )
    }

    @Test
    fun explicitBackdropAlwaysWins() {
        val detail = ItemDetail(
            contentId = "series-1",
            type = "series",
            title = "Series",
            backdropUrl = "backdrop",
            backdropThumbhash = "backdrop-hash",
            posterUrl = "portrait-poster",
        )
        val nextUp = EpisodeListItem(
            contentId = "episode-1",
            seasonNumber = 1,
            episodeNumber = 1,
            stillUrl = "landscape-still",
        )

        assertEquals(
            TvDetailHeroArtwork("backdrop", "backdrop-hash"),
            resolveTvDetailHeroArtwork(detail, nextUp),
        )
    }

    @Test
    fun portraitPosterIsNotStretchedAcrossNonEpisodicHero() {
        val detail = ItemDetail(
            contentId = "movie-1",
            type = "movie",
            title = "Movie",
            posterUrl = "portrait-poster",
        )

        val artwork = resolveTvDetailHeroArtwork(detail, null)

        assertNull(artwork.url)
        assertNull(artwork.thumbhash)
    }

    @Test
    fun detailPageTintIsOpaqueAndCompositedOverBlack() {
        val surface = tvDetailPageSurfaceColor(Color(red = 1f, green = 0.5f, blue = 0f))

        assertEquals(0.42f, surface.red, absoluteTolerance = ColorChannelTolerance)
        assertEquals(0.21f, surface.green, absoluteTolerance = ColorChannelTolerance)
        assertEquals(0f, surface.blue, absoluteTolerance = ColorChannelTolerance)
        assertEquals(1f, surface.alpha, absoluteTolerance = ColorChannelTolerance)
    }

    @Test
    fun detailPageUsesApprovedDefaultTintBeforeArtworkSamplingCompletes() {
        val surface = tvDetailPageSurfaceColor(null)

        assertEquals(0.0168f, surface.red, absoluteTolerance = ColorChannelTolerance)
        assertEquals(0.0504f, surface.green, absoluteTolerance = ColorChannelTolerance)
        assertEquals(0.0588f, surface.blue, absoluteTolerance = ColorChannelTolerance)
        assertEquals(1f, surface.alpha, absoluteTolerance = ColorChannelTolerance)
    }

    @Test
    fun seriesArtworkDoesNotExposeATintedStripAboveTheImage() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvDetailHero.kt",
        ).readText()

        assertFalse(source.contains("SERIES_ARTWORK_TOP_OFFSET"))
        assertFalse(source.contains(".offset(y = if (compactSeries)"))
    }

    private companion object {
        // Compose packs sRGB Color channels to 8-bit precision.
        const val ColorChannelTolerance = 1f / 255f
    }
}
