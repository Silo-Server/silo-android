package org.siloserver.silo.catalog

import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import kotlin.test.*

class LibraryMediaScopeTest {
    private val movies = (1..20).map { SectionItem("film-$it", "movie", "Film $it") }

    /** Recently Added/Released report `total_count = len(items)` after `LIMIT item_limit`; a full slice proves nothing. */
    @Test fun fullInlineSliceRefillsEvenWhenTotalCountEqualsItsSize() = runTest {
        val row = ResolvedSection("recent", "recently_added", "Recent", itemLimit = 20, totalCount = 20, items = movies)
        var loads = 0
        val result = scopeLibrarySection(row, "series") {
            loads++
            ApiResult.Success(CatalogResponse(items = movies.map { BrowseItem(it.contentId, it.type, it.title) } +
                BrowseItem("show", "series", "Show")))
        }
        assertEquals(1, loads)
        assertEquals(listOf("show"), result.section.items.map { it.contentId })
        assertFalse(result.incomplete)
    }

    /** A Random shelf pages a fresh sample, which need not include the series already shown inline. */
    @Test fun completedRefillKeepsMatchingInlineItems() = runTest {
        val row = ResolvedSection("random", "random", "Random", itemLimit = 3, totalCount = 3,
            items = listOf(SectionItem("film", "movie", "Film"), SectionItem("film-2", "movie", "Film 2"), SectionItem("inline-show", "series", "Inline")))
        val result = scopeLibrarySection(row, "series") {
            ApiResult.Success(CatalogResponse(items = listOf(BrowseItem("other-show", "series", "Other"), BrowseItem("film-3", "movie", "Film 3"))))
        }
        assertEquals(listOf("inline-show", "other-show"), result.section.items.map { it.contentId })
        assertFalse(result.incomplete)
    }

    /** A fresh sample that alone fills the shelf must not evict the series already shown. */
    @Test fun refillThatFillsTheShelfKeepsMatchingInlineItems() = runTest {
        val row = ResolvedSection("random", "random", "Random", itemLimit = 2, totalCount = 2,
            items = listOf(SectionItem("film", "movie", "Film"), SectionItem("inline-show", "series", "Inline")))
        val result = scopeLibrarySection(row, "series") {
            ApiResult.Success(CatalogResponse(items = listOf(BrowseItem("show-a", "series", "A"), BrowseItem("show-b", "series", "B")), hasMore = true))
        }
        assertEquals(listOf("inline-show", "show-a"), result.section.items.map { it.contentId })
        assertFalse(result.incomplete)
    }

    /** Inline items count toward the shelf, so a refill page that completes it ends the paging. */
    @Test fun refillStopsOnceInlineAndRefilledItemsFillTheShelf() = runTest {
        val row = ResolvedSection("random", "random", "Random", itemLimit = 3, totalCount = 3,
            items = listOf(SectionItem("film", "movie", "Film"), SectionItem("show-1", "series", "One"), SectionItem("show-2", "series", "Two")))
        var loads = 0
        val result = scopeLibrarySection(row, "series") {
            loads++
            ApiResult.Success(CatalogResponse(items = listOf(BrowseItem("show-$loads-new", "series", "New"), BrowseItem("film-$loads", "movie", "Film")),
                hasMore = true, continuation = CatalogContinuationV2("random", "page-$loads", null, emptySet())))
        }
        assertEquals(1, loads)
        assertEquals(listOf("show-1", "show-2", "show-1-new"), result.section.items.map { it.contentId })
        assertFalse(result.incomplete)
    }

    @Test fun underfilledInlineSliceIsExhaustedWithoutRefill() = runTest {
        val row = ResolvedSection("recent", "recently_added", "Recent", itemLimit = 20, totalCount = 2,
            items = listOf(SectionItem("film", "movie", "Film"), SectionItem("show", "series", "Show")))
        val result = scopeLibrarySection(row, "series") { error("An under-filled slice is the whole shelf") }
        assertEquals(listOf("show"), result.section.items.map { it.contentId })
        assertFalse(result.incomplete)
    }

    /** The catalog section source reads the stored admin definition, so a profile override would be dropped. */
    @Test fun profileCustomizedShelfNeverRefillsFromTheStoredDefinition() = runTest {
        val row = ResolvedSection("random", "random", "Random", itemLimit = 20, totalCount = 21, customized = true, items = movies)
        val result = scopeLibrarySection(row, "series") {
            ApiResult.Success(CatalogResponse(items = listOf(BrowseItem("show", "series", "Show"))))
        }
        assertEquals(emptyList(), result.section.items)
        assertTrue(result.incomplete, "The scoped slice is unverified, so say so rather than hide it silently")
    }

    @Test fun profileAddedShelfNeverRefillsFromTheCatalogSource() = runTest {
        val episode = SectionItem("episode", "episode", "Episode")
        val row = ResolvedSection("mine", "random", "Mine", itemLimit = 20, totalCount = 21, isCustom = true, items = movies.take(19) + episode)
        val result = scopeLibrarySection(row, "series") { error("Profile-added sections have no stored catalog definition") }
        assertEquals(listOf(episode), result.section.items)
        assertTrue(result.incomplete)
    }

    @Test fun customizedShelfAlreadyFilledByItsScopeIsComplete() = runTest {
        val row = ResolvedSection("random", "random", "Random", itemLimit = 20, totalCount = 21, customized = true, items = movies)
        val result = scopeLibrarySection(row, "movie") { error("A filled scoped shelf needs no refill") }
        assertEquals(movies, result.section.items)
        assertFalse(result.incomplete)
    }
}
