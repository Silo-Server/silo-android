package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.model.section.SectionCollectionCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A focused card of a row of collections drives the Skyline hero (silo-server#4415). */
class TvCollectionMarqueeTest {

    @Test
    fun aCollectionBackdropIsTheHeroWithItsThumbhash() {
        val card = SectionCollectionCard(
            id = "c1",
            source = SectionCollectionCard.SOURCE_LIBRARY,
            title = "Marvel",
            posterUrl = "https://s3.example.test/poster.jpg",
            posterThumbhash = "poster-hash",
            backdropUrl = "https://s3.example.test/backdrop-1280.jpg",
            backdropThumbhash = "backdrop-hash",
            libraryId = "1",
        )

        val content = TvMarqueeContent.from(card.tvMarqueeItem(), "Franchises", "row")

        assertEquals("https://s3.example.test/backdrop-1280.jpg", content.heroBackdropUrl)
        assertEquals("backdrop-hash", content.heroBackdropThumbhash)
        assertTrue(isTvCollectionMarqueeId(content.contentId))
    }

    @Test
    fun withoutABackdropTheHeroFallsBackToThePoster() {
        val card = SectionCollectionCard(
            id = "c2",
            source = SectionCollectionCard.SOURCE_USER,
            title = "Ghibli nights",
            posterUrl = "https://s3.example.test/collage.jpg",
            posterThumbhash = "collage-hash",
            backdropUrl = "",
        )

        val content = TvMarqueeContent.from(card.tvMarqueeItem(), "Mine", "row")

        assertEquals("https://s3.example.test/collage.jpg", content.heroBackdropUrl)
        assertEquals("collage-hash", content.heroBackdropThumbhash)
    }
}
