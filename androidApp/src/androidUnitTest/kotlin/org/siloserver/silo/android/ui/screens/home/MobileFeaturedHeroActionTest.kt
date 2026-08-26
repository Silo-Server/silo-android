package org.siloserver.silo.android.ui.screens.home

import org.siloserver.silo.model.section.SectionItem
import kotlin.test.Test
import kotlin.test.assertEquals

class MobileFeaturedHeroActionTest {
    @Test
    fun audiobookPlayOpensDetailsInsteadOfVideoPlayer() {
        val events = mutableListOf<String>()

        dispatchFeaturedHeroPlay(
            item = item(type = "audiobook"),
            onPlayClick = { contentId, _ -> events += "play:$contentId" },
            onInfoClick = { contentId -> events += "info:$contentId" },
        )

        assertEquals(listOf("info:featured-item"), events)
    }

    @Test
    fun videoPlayKeepsContentIdAndResumePosition() {
        val events = mutableListOf<String>()

        dispatchFeaturedHeroPlay(
            item = item(type = "movie", positionSeconds = 123.0),
            onPlayClick = { contentId, position -> events += "play:$contentId:$position" },
            onInfoClick = { contentId -> events += "info:$contentId" },
        )

        assertEquals(listOf("play:featured-item:123.0"), events)
    }

    @Test
    fun quotePrefersTaglineAndKeepsItCompact() {
        val result = featuredQuote(
            item(
                type = "movie",
                tagline = "Every family has its demons, even when the fire has gone out.",
                overview = "This overview must not win.",
            ),
        )

        assertEquals("Every family has its demons", result)
    }

    @Test
    fun quoteFallsBackWhenEditorialCopyIsUnavailable() {
        assertEquals("Ready when you are.", featuredQuote(item(type = "movie")))
    }

    @Test
    fun quoteDoesNotCollapseToACharactersNameBeforeAnEarlyComma() {
        val result = featuredQuote(
            item(
                type = "series",
                overview = "Jack Reacher, a veteran military police investigator, has entered civilian life.",
            ),
        )

        assertEquals("Jack Reacher, a veteran military police", result)
    }

    private fun item(
        type: String,
        positionSeconds: Double? = null,
        tagline: String? = null,
        overview: String? = null,
    ) = SectionItem(
        contentId = "featured-item",
        type = type,
        title = "Featured Item",
        positionSeconds = positionSeconds,
        tagline = tagline,
        overview = overview,
    )
}
