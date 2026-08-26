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

    private fun item(type: String, positionSeconds: Double? = null) = SectionItem(
        contentId = "featured-item",
        type = type,
        title = "Featured Item",
        positionSeconds = positionSeconds,
    )
}
