package org.siloserver.silo.tv.ui.theme

import org.siloserver.silo.network.ArtworkWidths
import org.siloserver.silo.repository.ArtworkSurface
import kotlin.test.Test
import kotlin.test.assertEquals

class TvArtworkLayoutTest {
    @Test
    fun widthsFollowTheDisplayDensity() {
        // 1080p UI (density 2.0): the server answers w300 posters, w1920 backdrops and w1280 logos.
        val hd = TvArtworkLayout({ 2f })
        assertEquals(ArtworkWidths(poster = 176, backdrop = 1344, logo = 880), hd.widths(ArtworkSurface.HOME, 1f))
        assertEquals(hd.widths(ArtworkSurface.HOME, 1f), hd.widths(ArtworkSurface.DISCOVER, 1f))
        assertEquals(ArtworkWidths(backdrop = 1344, logo = 650, photo = 200), hd.widths(ArtworkSurface.DETAIL, 1f))
        assertEquals(ArtworkWidths(still = 360), hd.widths(ArtworkSurface.EPISODES, 1f))
        assertEquals(ArtworkWidths(still = 460), hd.widths(ArtworkSurface.SEASON_EPISODES, 1f))
        assertEquals(ArtworkWidths(poster = 260), hd.widths(ArtworkSurface.SIMILAR, 1f))
        assertEquals(ArtworkWidths(poster = 276), hd.widths(ArtworkSurface.BROWSE, 1f))

        // 720p UI (tvdpi): the hero gets the 1280px backdrop.
        assertEquals(ArtworkWidths(poster = 117, backdrop = 895, logo = 586), TvArtworkLayout({ 213f / 160f }).widths(ArtworkSurface.HOME, 1f))
        // 4K UI (density 4.0): the hero gets the original.
        assertEquals(ArtworkWidths(poster = 352, backdrop = 2688, logo = 1760), TvArtworkLayout({ 4f }).widths(ArtworkSurface.HOME, 1f))
    }

    @Test
    fun cardsGrowWithTheCardSizePreference() {
        // Large posters (1.2×) at a 1080p UI: cards draw wider, heroes and logos don't.
        val large = TvArtworkLayout({ 2f }, { 1.2f })
        assertEquals(1.2f, large.cardScale())
        assertEquals(ArtworkWidths(poster = 211, backdrop = 1344, logo = 880), large.widths(ArtworkSurface.HOME, large.cardScale()))
        assertEquals(ArtworkWidths(poster = 312), large.widths(ArtworkSurface.SIMILAR, large.cardScale()))
        assertEquals(ArtworkWidths(still = 432), large.widths(ArtworkSurface.EPISODES, large.cardScale()))
        assertEquals(ArtworkWidths(still = 552), large.widths(ArtworkSurface.SEASON_EPISODES, large.cardScale()))
        assertEquals(ArtworkWidths(poster = 331), large.widths(ArtworkSurface.BROWSE, large.cardScale()))
        assertEquals(ArtworkWidths(backdrop = 1344, logo = 650, photo = 200), large.widths(ArtworkSurface.DETAIL, large.cardScale()))
    }

    @Test
    fun homeForYouAndDetailShareTheHeroBackdrop() {
        val layout = TvArtworkLayout({ 2f })
        val hero = layout.widths(ArtworkSurface.HOME, 1f).backdrop
        assertEquals(hero, layout.widths(ArtworkSurface.DISCOVER, 1f).backdrop)
        assertEquals(hero, layout.widths(ArtworkSurface.DETAIL, 1f).backdrop)
    }
}
