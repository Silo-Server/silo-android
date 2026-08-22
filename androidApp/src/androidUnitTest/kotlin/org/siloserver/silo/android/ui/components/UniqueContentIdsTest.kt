package org.siloserver.silo.android.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.section.SectionItem

class UniqueContentIdsTest {

    @Test
    fun `keeps the first occurrence of a repeated content id`() {
        val items = listOf(
            section("series-tvdb-280619", "The 100"),
            section("movie-tt0111161", "The Shawshank Redemption"),
            section("series-tvdb-280619", "The 100 (duplicate)"),
        )

        val unique = items.uniqueByContentId { it.contentId }

        assertEquals(listOf("series-tvdb-280619", "movie-tt0111161"), unique.map { it.contentId })
        assertEquals("The 100", unique.first().title)
    }

    @Test
    fun `browse paging duplicates collapse the same way`() {
        val page = listOf(
            browse("a"),
            browse("b"),
            browse("a"),
            browse("c"),
            browse("b"),
        )

        assertEquals(
            listOf("a", "b", "c"),
            page.uniqueByContentId { it.contentId }.map { it.contentId },
        )
    }

    @Test
    fun `an already unique list is unchanged`() {
        val items = listOf(section("a", "A"), section("b", "B"))
        assertEquals(items, items.uniqueByContentId { it.contentId })
    }

    private fun section(contentId: String, title: String) = SectionItem(
        contentId = contentId,
        type = "series",
        title = title,
    )

    private fun browse(contentId: String) = BrowseItem(
        contentId = contentId,
        type = "movie",
        title = contentId,
    )
}
