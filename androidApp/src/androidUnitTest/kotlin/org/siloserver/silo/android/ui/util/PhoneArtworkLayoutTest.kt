package org.siloserver.silo.android.ui.util

import org.siloserver.silo.network.ArtworkWidths
import org.siloserver.silo.repository.ArtworkSurface
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneArtworkLayoutTest {
    private fun layout(widthDp: Int, heightDp: Int, density: Float, posterScale: Float = 1f) =
        PhoneArtworkLayout({ PhoneArtworkLayout.Screen(widthDp, heightDp, density) }, { posterScale })

    @Test
    fun phoneWidthsCoverBothOrientations() {
        val phone = layout(411, 914, 3f)
        // Portrait crops the backdrop into a 485dp-tall hero (862dp of width);
        // landscape spans it over 914dp, which is wider, beside a 224dp poster.
        assertEquals(ArtworkWidths(poster = 672, backdrop = 2742, logo = 1223, photo = 228), phone.widths(ArtworkSurface.DETAIL, 1f))
        assertEquals(phone.widths(ArtworkSurface.DETAIL, 1f), layout(914, 411, 3f).widths(ArtworkSurface.DETAIL, 1f))
        assertEquals(ArtworkWidths(still = 720), phone.widths(ArtworkSurface.EPISODES, 1f))
        assertEquals(ArtworkWidths(still = 720), phone.widths(ArtworkSurface.SEASON_EPISODES, 1f))
        // Three 118dp columns in portrait.
        assertEquals(ArtworkWidths(poster = 355), phone.widths(ArtworkSurface.BROWSE, 1f))
        // MediaCard and BackdropCard rails at the standard card size.
        assertEquals(ArtworkWidths(poster = 360, backdrop = 840), phone.widths(ArtworkSurface.HOME, 1f))
        assertEquals(ArtworkWidths(poster = 360), phone.widths(ArtworkSurface.SIMILAR, 1f))
    }

    @Test
    fun cardsAndGridCellsGrowWithTheCardSizePreference() = runTest {
        val large = layout(411, 914, 3f, posterScale = 1.2f)
        assertEquals(1.2f, large.cardScale())
        assertEquals(ArtworkWidths(poster = 432, backdrop = 1008), large.widths(ArtworkSurface.HOME, large.cardScale()))
        // Larger posters drop the grid to two 183.5dp columns in portrait.
        assertEquals(ArtworkWidths(poster = 551), large.widths(ArtworkSurface.BROWSE, large.cardScale()))
        // Episode cards and the detail page don't scale.
        assertEquals(large.widths(ArtworkSurface.DETAIL, 1f), large.widths(ArtworkSurface.DETAIL, large.cardScale()))
        assertEquals(ArtworkWidths(still = 720), large.widths(ArtworkSurface.EPISODES, large.cardScale()))
    }

    @Test
    fun tabletUsesTheExpandedHero() {
        val tablet = layout(1280, 800, 2f)
        // The landscape hero spans 1280dp; the logo is 72% of the text column beside a 224dp poster.
        assertEquals(ArtworkWidths(poster = 448, backdrop = 2560, logo = 1342, photo = 152), tablet.widths(ArtworkSurface.DETAIL, 1f))
        assertEquals(ArtworkWidths(poster = 236), tablet.widths(ArtworkSurface.BROWSE, 1f))
    }
}
