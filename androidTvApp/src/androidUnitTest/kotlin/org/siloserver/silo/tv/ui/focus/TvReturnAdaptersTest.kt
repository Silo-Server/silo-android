package org.siloserver.silo.tv.ui.focus

import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvReturnAdaptersTest {

    private fun item(contentId: String) = SectionItem(
        contentId = contentId,
        type = "movie",
        title = "Title $contentId",
    )

    private fun section(id: String, ids: List<String>, totalCount: Int = 0) = ResolvedSection(
        id = id,
        sectionType = "row",
        title = id,
        totalCount = totalCount,
        items = ids.map(::item),
    )

    @Test
    fun `rows project to their section id and card content ids in order`() {
        val feed = listOf(
            section("continue", listOf("a", "b")),
            section("recent", listOf("c")),
        )

        assertEquals(
            listOf(
                TvReturnSection("continue", listOf("a", "b")),
                TvReturnSection("recent", listOf("c")),
            ),
            feed.toTvReturnSections(),
        )
    }

    @Test
    fun `a capped row is complete even though the server says more exist`() {
        // The distinction that matters: totalCount above the card count means
        // the row was TRIMMED, not that a page is still coming. Nothing will
        // ever load the remainder, so reporting it incomplete would leave every
        // unresolved return waiting forever for a request nobody makes.
        val capped = listOf(
            section("recent", listOf("a", "b"), totalCount = 500).copy(itemLimit = 2),
        )

        assertTrue(capped.toTvReturnSections().single().isComplete)
    }

    @Test
    fun `an unhydrated placeholder row is incomplete, not empty`() {
        // The distinction this pins: hydrateHomeSections fetches rows that
        // arrive with no cards but a non-zero total, then fills them in.
        // Reading that as "nothing here" spends the return target on a
        // fallback moments before the real row appears.
        val placeholder = listOf(section("recent", emptyList(), totalCount = 20))

        assertTrue(!placeholder.toTvReturnSections().single().isComplete)
        assertEquals(
            TvReturnResolution.Pending,
            resolveTvReturnTarget(
                TvReturnTarget("recent", "e", 0, 1),
                placeholder.toTvReturnSections(),
            ),
        )
    }

    @Test
    fun `a genuinely empty row is complete`() {
        val empty = listOf(section("recent", emptyList(), totalCount = 0))
        assertTrue(empty.toTvReturnSections().single().isComplete)
    }

    @Test
    fun `an empty feed projects to no sections`() {
        assertEquals(emptyList(), emptyList<ResolvedSection>().toTvReturnSections())
    }

    @Test
    fun `a projected feed resolves a launch card by identity`() {
        // End to end through the contract, so the projection is exercised the
        // way the feed will use it rather than only compared field by field.
        val feed = listOf(
            section("continue", listOf("a", "b")),
            section("recent", listOf("c", "d")),
        )
        val launched = TvReturnTarget("recent", "d", sectionIndex = 1, itemIndex = 1)

        // The feed reorders and the launch row moves; identity still finds it.
        val reordered = listOf(
            section("recent", listOf("new", "c", "d")),
            section("continue", listOf("a", "b")),
        )

        assertEquals(
            TvReturnResolution.Exact(1, 1, "recent", "d"),
            resolveTvReturnTarget(launched, feed.toTvReturnSections()),
        )
        assertEquals(
            TvReturnResolution.Exact(0, 2, "recent", "d"),
            resolveTvReturnTarget(launched, reordered.toTvReturnSections()),
        )
    }

    @Test
    fun `a Continue Watching show that advanced an episode is found at its new slot`() {
        fun episode(contentId: String, seriesId: String) = SectionItem(
            contentId = contentId,
            type = "episode",
            title = contentId,
            seriesId = seriesId,
            seasonNumber = 1,
        )
        fun continueWatching(vararg items: SectionItem) = ResolvedSection(
            id = "continue",
            sectionType = "continue_watching",
            title = "Continue Watching",
            items = items.toList(),
        )
        val before = continueWatching(
            episode("x-e1", "show-x"),
            episode("y-e5", "show-y"),
            episode("z-e3", "show-z"),
        )
        val launched = TvReturnTarget(
            "continue",
            before.tvReturnItemId(before.items[2]),
            sectionIndex = 0,
            itemIndex = 2,
        )

        // Finishing Z's E3 replaces the card with E4 and moves it to the front.
        val after = continueWatching(
            episode("z-e4", "show-z"),
            episode("x-e1", "show-x"),
            episode("y-e5", "show-y"),
        )

        assertEquals(
            TvReturnResolution.Exact(0, 0, "continue", launched.itemId),
            resolveTvReturnTarget(launched, listOf(after).toTvReturnSections()),
        )

        // A show that appears twice can't be one target; its cards keep episode ids.
        val repeated = continueWatching(episode("z-e3", "show-z"), episode("z-e4", "show-z"))
        assertEquals(listOf("z-e3", "z-e4"), repeated.tvReturnItemIds())
    }

    @Test
    fun `episodes outside progress rows keep their own identity`() {
        val recent = ResolvedSection(
            id = "recent",
            sectionType = "recently_added",
            title = "Recently Added",
            items = listOf(
                SectionItem(contentId = "e1", type = "episode", title = "e1", seriesId = "show"),
                SectionItem(contentId = "e2", type = "episode", title = "e2", seriesId = "show"),
            ),
        )

        assertEquals(listOf("e1", "e2"), listOf(recent).toTvReturnSections().single().itemIds)
    }

    // ── Flat paginated surfaces ──────────────────────────────────────────

    @Test
    fun `a flat page still loading reports incomplete`() {
        val page = flatTvReturnSections(listOf("a", "b"), hasMore = true)

        assertTrue(!page.single().isComplete)
        assertEquals(TvFlatSectionId, page.single().id)
    }

    @Test
    fun `a fully loaded flat surface reports complete`() {
        assertTrue(flatTvReturnSections(listOf("a"), hasMore = false).single().isComplete)
    }

    @Test
    fun `a target beyond the loaded page waits rather than settling nearby`() {
        // The regression this pins for every paginated surface: an item on a
        // later page is missing exactly as a deleted one is, and settling here
        // strands focus on whatever card the first page happens to end with.
        val launched = TvReturnTarget(TvFlatSectionId, "z", sectionIndex = 0, itemIndex = 60)

        assertEquals(
            TvReturnResolution.Pending,
            resolveTvReturnTarget(launched, flatTvReturnSections(listOf("a", "b"), hasMore = true)),
        )
        // Once the page carrying it arrives, identity finds it wherever it sits.
        assertEquals(
            TvReturnResolution.Exact(0, 2, TvFlatSectionId, "z"),
            resolveTvReturnTarget(launched, flatTvReturnSections(listOf("a", "b", "z"), hasMore = true)),
        )
    }

    @Test
    fun `a caller out of patience settles on the nearest loaded card`() {
        val launched = TvReturnTarget(TvFlatSectionId, "z", sectionIndex = 0, itemIndex = 60)

        assertEquals(
            TvReturnResolution.Nearest(0, 1, TvFlatSectionId, "b"),
            resolveTvReturnTarget(
                launched,
                flatTvReturnSections(listOf("a", "b"), hasMore = true),
                treatAbsenceAsFinal = true,
            ),
        )
    }
}
