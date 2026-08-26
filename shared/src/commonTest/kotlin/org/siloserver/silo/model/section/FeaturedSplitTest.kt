package org.siloserver.silo.model.section

import kotlin.test.Test
import kotlin.test.assertEquals

class FeaturedSplitTest {
    @Test
    fun topFeaturedSectionBecomesHeroAndLaterFeaturedSectionsRemainRows() {
        val sections = listOf(
            section(id = "featured-first", featured = true),
            section(id = "featured-later", featured = true),
        )

        val split = sections.splitTopFeatured()

        assertEquals("featured-first", split.featured?.id)
        assertEquals(listOf("featured-later"), split.rest.map { it.id })
    }

    @Test
    fun featuredSectionBelowTopRowDoesNotBecomeHero() {
        val sections = listOf(
            section(id = "regular", featured = false),
            section(id = "featured-later", featured = true),
        )

        val split = sections.splitTopFeatured()

        assertEquals(null, split.featured)
        assertEquals(listOf("regular", "featured-later"), split.rest.map { it.id })
    }

    private fun section(id: String, featured: Boolean) = ResolvedSection(
        id = id,
        sectionType = "custom",
        title = id,
        featured = featured,
        items = listOf(SectionItem(contentId = "$id-item", type = "movie", title = id)),
    )
}
