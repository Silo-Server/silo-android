package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.model.catalog.CastMember
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.section.SectionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §9 marquee detail-enrichment port of tvOS `TVMarqueeEnrichment` /
 * `TVFocusMarqueeModel.backdropURL`: the aired/cast line applies to every item;
 * the series-backdrop upgrade applies to episodes only.
 */
class TvFocusMarqueeEnrichmentTest {

    @Test
    fun `focus rest delay matches tvOS`() {
        assertEquals(150L, TvMarqueeFocusRestMillis)
    }

    private fun detail(
        contentId: String = "c1",
        type: String = "movie",
        airDate: String? = null,
        cast: List<CastMember> = emptyList(),
        backdropUrl: String? = "https://art/series.jpg",
        backdropThumbhash: String? = "hash",
    ): ItemDetail = ItemDetail(
        contentId = contentId,
        type = type,
        title = "Title",
        airDate = airDate,
        cast = cast,
        backdropUrl = backdropUrl,
        backdropThumbhash = backdropThumbhash,
    )

    private fun episodeContent(
        backdropUrl: String? = "https://still/episode.jpg",
    ): TvMarqueeContent = TvMarqueeContent.from(
        item = SectionItem(
            contentId = "c1",
            type = "episode",
            title = "Ep Title",
            seriesTitle = "The Series",
            backdropUrl = backdropUrl,
        ),
        rowTitle = "Row",
    )

    private fun movieContent(): TvMarqueeContent = TvMarqueeContent.from(
        item = SectionItem(
            contentId = "c1",
            type = "movie",
            title = "Movie",
            backdropUrl = "https://still/movie.jpg",
        ),
        rowTitle = "Row",
    )

    @Test
    fun `detail line joins aired date and top-3 cast sorted by order`() {
        val enrichment = TvMarqueeEnrichment.from(
            detail(
                airDate = "2013-01-05",
                cast = listOf(
                    CastMember(name = "Third", order = 2),
                    CastMember(name = "First", order = 0),
                    CastMember(name = "Second", order = 1),
                    CastMember(name = "Fourth", order = 3),
                ),
            ),
        )
        assertEquals("Aired Jan 5, 2013 · First, Second, Third", enrichment.detailLine)
    }

    @Test
    fun `detail line omits aired part when air date is unparseable`() {
        val enrichment = TvMarqueeEnrichment.from(
            detail(airDate = "not-a-date", cast = listOf(CastMember(name = "Only", order = 0))),
        )
        assertEquals("Only", enrichment.detailLine)
    }

    @Test
    fun `detail line is null when both aired and cast are empty`() {
        val enrichment = TvMarqueeEnrichment.from(detail(airDate = null, cast = emptyList()))
        assertNull(enrichment.detailLine)
    }

    @Test
    fun `episode upgrades hero backdrop to series backdrop and gains detail line`() {
        val content = episodeContent()
        val enriched = content.withEnrichment(
            TvMarqueeEnrichment.from(
                detail(
                    type = "series",
                    airDate = "2013-01-05",
                    cast = listOf(CastMember(name = "Star", order = 0)),
                    backdropUrl = "https://art/series.jpg",
                    backdropThumbhash = "seriesHash",
                ),
            ),
        )
        assertEquals("https://art/series.jpg", enriched.heroBackdropUrl)
        assertEquals("seriesHash", enriched.heroBackdropThumbhash)
        assertEquals("Aired Jan 5, 2013 · Star", enriched.detailLine)
    }

    @Test
    fun `non-episode keeps its own backdrop but still gains detail line`() {
        val content = movieContent()
        val enriched = content.withEnrichment(
            TvMarqueeEnrichment.from(
                detail(
                    airDate = "2013-01-05",
                    backdropUrl = "https://art/other.jpg",
                ),
            ),
        )
        assertEquals("https://still/movie.jpg", enriched.heroBackdropUrl)
        assertEquals("Aired Jan 5, 2013", enriched.detailLine)
    }

    @Test
    fun `episode shows section backdrop immediately when enrichment has no upgrade`() {
        // Match tvOS: first focus always has the section artwork available;
        // enrichment may later upgrade it but never gates the initial hero.
        val content = episodeContent()
        assertEquals("https://still/episode.jpg", content.heroBackdropUrl)
        val enriched = content.withEnrichment(
            TvMarqueeEnrichment.from(detail(backdropUrl = null, backdropThumbhash = null)),
        )
        assertEquals("https://still/episode.jpg", enriched.heroBackdropUrl)
    }

    @Test
    fun `page entry reseeds a stale candidate when its row identity changes`() {
        val state = TvFocusMarqueeState()
        val item = SectionItem(contentId = "item-1", type = "movie", title = "Item")

        state.seedInitialPreview(item, "Row", rowIdentity = "row-old")
        state.commit(state.candidate)
        state.seedInitialPreview(item, "Row", rowIdentity = "row-new")

        assertEquals("row-new#item-1", state.candidate?.id)
    }

    @Test
    fun `page entry seed never replaces settled real focus`() {
        val state = TvFocusMarqueeState()
        val focusedItem = SectionItem(
            contentId = "focused-item",
            type = "movie",
            title = "Focused",
        )
        val seedItem = SectionItem(
            contentId = "seed-item",
            type = "movie",
            title = "Replacement",
        )

        state.preview(focusedItem, "Focused", rowIdentity = "focused-row")
        state.commit(state.candidate)
        state.seedInitialPreview(seedItem, "Replacement", rowIdentity = "replacement-row")

        assertEquals("focused-row#focused-item", state.content?.id)
        assertEquals("focused-row#focused-item", state.candidate?.id)
    }

    @Test
    fun `page entry seed never replaces pending real focus`() {
        val state = TvFocusMarqueeState()
        val initialItem = SectionItem(
            contentId = "initial-item",
            type = "movie",
            title = "Initial",
        )
        val focusedItem = SectionItem(
            contentId = "focused-item",
            type = "movie",
            title = "Focused",
        )
        val seedItem = SectionItem(
            contentId = "seed-item",
            type = "movie",
            title = "Replacement",
        )

        state.seedInitialPreview(initialItem, "Initial", rowIdentity = "initial-row")
        state.commit(state.candidate)
        state.preview(focusedItem, "Focused", rowIdentity = "focused-row")
        state.seedInitialPreview(seedItem, "Replacement", rowIdentity = "replacement-row")

        assertEquals("initial-row#initial-item", state.content?.id)
        assertEquals("focused-row#focused-item", state.candidate?.id)
    }

    @Test
    fun `initial seed does not enable network enrichment until real focus`() {
        val state = TvFocusMarqueeState()
        val first = SectionItem(
            contentId = "first",
            type = "movie",
            title = "First Movie",
        )

        state.seedInitialPreview(first, "Continue Watching")
        state.commit(state.candidate)
        assertFalse(state.hasSettledRealFocus)

        state.preview(first, "Continue Watching")
        assertTrue(state.hasSettledRealFocus)
    }

    @Test
    fun `new raw focus does not enrich the old seed before settlement`() {
        val state = TvFocusMarqueeState()
        val first = SectionItem(contentId = "first", type = "movie", title = "First")
        val second = SectionItem(contentId = "second", type = "movie", title = "Second")

        state.seedInitialPreview(first, "First row", rowIdentity = "row-1")
        state.commit(state.candidate)
        state.preview(second, "Second row", rowIdentity = "row-2")

        assertFalse(state.hasSettledRealFocus)

        state.commit(state.candidate)
        assertTrue(state.hasSettledRealFocus)
    }

    @Test
    fun `active enrichment publishes immediately`() {
        val state = TvFocusMarqueeState()
        state.commit(episodeContent())

        state.applyEnrichment(
            "c1",
            TvMarqueeEnrichment("Aired today", "https://art/series.jpg", "seriesHash"),
        )

        assertEquals("Aired today", state.backdropContent?.detailLine)
        assertEquals("https://art/series.jpg", state.backdropContent?.heroBackdropUrl)
    }

    @Test
    fun `stale enrichment only warms cache`() {
        val state = TvFocusMarqueeState()
        state.commit(episodeContent())

        state.applyEnrichment("old", TvMarqueeEnrichment("Old", null, null))

        assertNull(state.backdropContent?.detailLine)
        assertEquals("Old", state.cachedEnrichment("old")?.detailLine)
    }
}
