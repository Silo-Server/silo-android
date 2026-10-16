package org.siloserver.silo.android.ui.util

import org.siloserver.silo.network.ArtworkWidths
import org.siloserver.silo.repository.ArtworkSurface
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneArtworkLayoutTest {
    private fun layout(widthDp: Int, heightDp: Int, density: Float) =
        PhoneArtworkLayout { PhoneArtworkLayout.Screen(widthDp, heightDp, density) }

    @Test
    fun phoneWidthsCoverBothOrientations() {
        val phone = layout(411, 914, 3f)
        // Portrait crops the backdrop into a 485dp-tall hero (862dp of width);
        // landscape spans it over 914dp, which is wider, beside a 224dp poster.
        assertEquals(ArtworkWidths(poster = 672, backdrop = 2742, logo = 1223, photo = 228), phone.widths(ArtworkSurface.DETAIL))
        assertEquals(phone.widths(ArtworkSurface.DETAIL), layout(914, 411, 3f).widths(ArtworkSurface.DETAIL))
        assertEquals(ArtworkWidths(still = 720), phone.widths(ArtworkSurface.EPISODES))
        assertEquals(ArtworkWidths(still = 720), phone.widths(ArtworkSurface.SEASON_EPISODES))
        // Three 118dp columns in portrait.
        assertEquals(ArtworkWidths(poster = 355), phone.widths(ArtworkSurface.BROWSE))
        // Rail cards at the largest card size (1.2×).
        assertEquals(ArtworkWidths(poster = 432, backdrop = 1008), phone.widths(ArtworkSurface.HOME))
        assertEquals(ArtworkWidths(poster = 432), phone.widths(ArtworkSurface.SIMILAR))
    }

    @Test
    fun tabletUsesTheExpandedHero() {
        val tablet = layout(1280, 800, 2f)
        // The landscape hero spans 1280dp; the logo is 72% of the text column beside a 224dp poster.
        assertEquals(ArtworkWidths(poster = 448, backdrop = 2560, logo = 1342, photo = 152), tablet.widths(ArtworkSurface.DETAIL))
        assertEquals(ArtworkWidths(poster = 236), tablet.widths(ArtworkSurface.BROWSE))
    }
}
