package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogAvailability
import kotlin.test.*

class TvSimilarCardsTest {
    @Test fun preservesRankAndPosterFieldsWithoutSelfOrReadingCards() {
        val cards=listOf(
            BrowseItem(contentId="second",type="movie",title="Second",year=2024,posterUrl="/poster",posterThumbhash="hash",availability=CatalogAvailability(have=2,total=3)),
            BrowseItem(contentId="self",type="movie",title="Self"),
            BrowseItem(contentId="ebook",type="ebook",title="Reading"),
            BrowseItem(contentId="comic",type="comic",title="Comic"),
            BrowseItem(contentId="first",type="series",title="First"),
        )
        val result=similarCardsForTv(cards,"self")
        assertEquals(listOf("second","first"),result.map {it.contentId})
        assertEquals("/poster",result.first().posterUrl);assertEquals("hash",result.first().posterThumbhash)
        assertEquals(2024,result.first().year);assertEquals("Second",result.first().title)
        assertEquals(2,result.first().availability?.have);assertEquals(3,result.first().availability?.total)
        assertTrue(similarCardsForTv(emptyList(),"self").isEmpty())
    }
}
