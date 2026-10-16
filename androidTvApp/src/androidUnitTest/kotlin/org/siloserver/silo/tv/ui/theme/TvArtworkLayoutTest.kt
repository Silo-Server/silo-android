package org.siloserver.silo.tv.ui.theme

import org.siloserver.silo.network.ArtworkWidths
import org.siloserver.silo.repository.ArtworkSurface
import kotlin.test.Test
import kotlin.test.assertEquals

class TvArtworkLayoutTest {
    @Test
    fun widthsFollowTheDisplayDensity() {
        // 1080p UI (density 2.0): the server answers w300 posters, w1920 backdrops and w1280 logos.
        val hd = TvArtworkLayout { 2f }
        assertEquals(ArtworkWidths(poster = 176, backdrop = 1344, logo = 880), hd.widths(ArtworkSurface.HOME))
        assertEquals(hd.widths(ArtworkSurface.HOME), hd.widths(ArtworkSurface.DISCOVER))
        assertEquals(ArtworkWidths(backdrop = 1344, logo = 650, photo = 200), hd.widths(ArtworkSurface.DETAIL))
        assertEquals(ArtworkWidths(still = 360), hd.widths(ArtworkSurface.EPISODES))
        assertEquals(ArtworkWidths(still = 460), hd.widths(ArtworkSurface.SEASON_EPISODES))
        assertEquals(ArtworkWidths(poster = 260), hd.widths(ArtworkSurface.SIMILAR))
        assertEquals(ArtworkWidths(poster = 276), hd.widths(ArtworkSurface.BROWSE))

        // 720p UI (tvdpi): the hero gets the 1280px backdrop.
        assertEquals(ArtworkWidths(poster = 117, backdrop = 895, logo = 586), TvArtworkLayout { 213f / 160f }.widths(ArtworkSurface.HOME))
        // 4K UI (density 4.0): the hero gets the original.
        assertEquals(ArtworkWidths(poster = 352, backdrop = 2688, logo = 1760), TvArtworkLayout { 4f }.widths(ArtworkSurface.HOME))
    }

    @Test
    fun homeForYouAndDetailShareTheHeroBackdrop() {
        val layout = TvArtworkLayout { 2f }
        val hero = layout.widths(ArtworkSurface.HOME).backdrop
        assertEquals(hero, layout.widths(ArtworkSurface.DISCOVER).backdrop)
        assertEquals(hero, layout.widths(ArtworkSurface.DETAIL).backdrop)
    }
}
