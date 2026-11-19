package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.section.SectionItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * tvOS parity (silo-apple#1198): an item without a backdrop shows its cover
 * framed in the hero, using the detail's sharper poster.
 */
class TvHeroCoverTest {
    private fun item(type: String, backdropUrl: String? = null, posterUrl: String? = "https://art/row-poster.jpg") =
        SectionItem(contentId = "c-$type", type = type, title = "Title", backdropUrl = backdropUrl, posterUrl = posterUrl)

    @Test
    fun anAudiobookWithoutABackdropFramesASquareCover() {
        val content = TvMarqueeContent.from(item("audiobook"), rowTitle = "Row")
        assertEquals(TvSquareCoverAspect, content.coverAspect)
        assertEquals("https://art/row-poster.jpg", content.heroBackdropUrl)
    }

    @Test
    fun aMovieWithoutABackdropFramesAPosterAndOneWithABackdropDoesNot() {
        assertEquals(TvPosterCoverAspect, TvMarqueeContent.from(item("movie"), rowTitle = "Row").coverAspect)
        assertNull(TvMarqueeContent.from(item("movie", backdropUrl = "https://art/backdrop.jpg"), rowTitle = "Row").coverAspect)
    }

    @Test
    fun anEpisodePosterIsNeverFramedBecauseItCanBeALandscapeStill() {
        assertNull(TvMarqueeContent.from(item("episode"), rowTitle = "Row").coverAspect)
    }

    @Test
    fun aFramedCoverUpgradesToTheDetailPoster() {
        val content = TvMarqueeContent.from(item("audiobook"), rowTitle = "Row")
        val enrichment = TvMarqueeEnrichment.from(
            ItemDetail(contentId = "c-audiobook", type = "audiobook", title = "Title", posterUrl = "https://art/detail-poster.jpg"),
        )
        val enriched = content.withEnrichment(enrichment)
        assertEquals("https://art/detail-poster.jpg", enriched.heroBackdropUrl)
        assertEquals(TvSquareCoverAspect, enriched.coverAspect)
    }

    @Test
    fun theShownCoverStaysUntilTheDetailPosterHasLoaded() {
        val book = item("audiobook")
        val state = TvFocusMarqueeState()
        state.preview(book, rowTitle = "Row")
        state.commit(state.candidate)
        state.applyEnrichment(
            book.contentId,
            TvMarqueeEnrichment.from(
                ItemDetail(contentId = book.contentId, type = "audiobook", title = "Title", posterUrl = "https://art/detail-poster.jpg"),
            ),
        )

        // A slow or failed load leaves the row's cover on screen.
        assertEquals("https://art/row-poster.jpg", state.backdropContent?.heroBackdropUrl)
        assertEquals(TvPendingCover("https://art/detail-poster.jpg", TvSquareCoverAspect), state.pendingCover)

        state.markCoverLoaded("https://art/detail-poster.jpg")
        assertEquals("https://art/detail-poster.jpg", state.backdropContent?.heroBackdropUrl)
        assertNull(state.pendingCover)
    }

    @Test
    fun aBackdropIsNotReplacedByTheDetailPoster() {
        val content = TvMarqueeContent.from(item("movie", backdropUrl = "https://art/backdrop.jpg"), rowTitle = "Row")
        val enrichment = TvMarqueeEnrichment.from(
            ItemDetail(contentId = "c-movie", type = "movie", title = "Title", posterUrl = "https://art/detail-poster.jpg"),
        )
        assertEquals("https://art/backdrop.jpg", content.withEnrichment(enrichment).heroBackdropUrl)
    }
}
