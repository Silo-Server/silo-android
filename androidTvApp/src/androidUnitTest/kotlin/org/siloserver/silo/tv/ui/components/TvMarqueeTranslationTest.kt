package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvMarqueeTranslationTest {
    private val pendingCard = SectionItem(
        contentId = "movie-1",
        type = "movie",
        title = "Film",
        overview = "Original",
        pendingTranslationLanguage = "nl",
    )
    private val featured = ResolvedSection(
        id = "featured",
        sectionType = "featured",
        title = "Featured",
        featured = true,
        items = listOf(pendingCard),
    )
    private val ordinary = ResolvedSection(
        id = "recently_added",
        sectionType = "recently_added",
        title = "Recently Added",
        items = listOf(pendingCard),
    )
    private val sections = listOf(featured, ordinary)

    @Test
    fun aCardFocusedInAFeaturedSectionTranslatesOnView() {
        val content = TvMarqueeContent.from(pendingCard, featured.title, featured.id)

        assertTrue(isFeaturedMarqueeContent(content, sections))
    }

    @Test
    fun theSameCardFocusedInAnOrdinaryRowDoesNot() {
        val content = TvMarqueeContent.from(pendingCard, ordinary.title, ordinary.id)

        assertFalse(isFeaturedMarqueeContent(content, sections))
    }

    @Test
    fun noContentOrAnUnknownRowDoesNot() {
        assertFalse(isFeaturedMarqueeContent(null, sections))
        val content = TvMarqueeContent.from(pendingCard, "Gone", "gone")
        assertFalse(isFeaturedMarqueeContent(content, sections))
    }
}
