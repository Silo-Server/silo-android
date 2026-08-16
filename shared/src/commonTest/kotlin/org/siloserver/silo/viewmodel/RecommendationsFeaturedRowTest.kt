package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.recommendation.DiscoverRow
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.section.splitFeatured
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Discover rows have no `featured` field on the wire, so the hero the phone
 * renders comes entirely from the conversion marking "for-you-main". If that
 * mark moved to another row — or spread to several — [splitFeatured] would
 * promote the wrong section into the hero carousel.
 */
class RecommendationsFeaturedRowTest {
    @Test
    fun onlyTheForYouMainRowIsFeatured() {
        val sections = listOf(
            row("popular", "Popular on This Server", "popular", "movie-popular"),
            row("for_you", "For You", "for-you-main", "movie-personal"),
            row("recently_added", "Recently Added", "recently-added", "movie-new"),
        ).toResolvedSections()

        val featured = sections.splitFeatured().featured

        assertEquals("For You", featured?.title)
        assertEquals(1, sections.count { it.featured })
    }

    /** A server that sends no personalised row must leave the flag clear, so
     *  the client falls back to its own hero choice instead of guessing here. */
    @Test
    fun feedsWithoutAForYouMainRowHaveNoFeaturedSection() {
        val sections = listOf(
            row("popular", "Popular on This Server", "popular", "movie-popular"),
            row("cluster", "Because you enjoy Drama", "cluster", "movie-drama", sectionKey = "2"),
        ).toResolvedSections()

        assertTrue(sections.none { it.featured })
        assertEquals(null, sections.splitFeatured().featured)
    }

    private fun row(
        type: String,
        label: String,
        sectionKind: String,
        contentId: String,
        sectionKey: String? = null,
    ) = DiscoverRow(
        type = type,
        label = label,
        sectionKind = sectionKind,
        sectionKey = sectionKey,
        items = listOf(SectionItem(contentId = contentId, type = "movie", title = contentId)),
    )
}
