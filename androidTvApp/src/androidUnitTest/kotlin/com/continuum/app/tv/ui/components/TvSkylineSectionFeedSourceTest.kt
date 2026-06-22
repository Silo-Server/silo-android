package com.continuum.app.tv.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvSkylineSectionFeedSourceTest {
    private val sourceFile = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/components/TvSkylineSectionFeed.kt",
    )

    @Test
    fun sharedSkylineFeedOwnsClippedViewAlignedRowBandAndMarquee() {
        assertTrue(sourceFile.exists(), "Shared Skyline feed component must exist")
        val source = sourceFile.readText()

        assertTrue(source.contains("rememberLazyListState()"))
        assertTrue(source.contains("state = rowBandState"))
        assertTrue(source.contains("itemsIndexed("))
        assertTrue(source.contains("val onItemFocused: (SectionItem, String, Int) -> Unit"))
        // Scroll must have a SINGLE authority: the focused card's bringIntoView,
        // governed by the cinematic spec. The old manual animateScrollToItem
        // fought it and caused the awkward double-target re-scroll — assert it's
        // gone and the spec is provided to the row band.
        assertTrue(source.contains("LocalBringIntoViewSpec provides TvSmoothBringIntoViewSpec"))
        assertTrue(!source.contains("animateScrollToItem"))
        assertTrue(source.contains("TvRootHeroBackdrop("))
        assertTrue(source.contains("TvFocusMarquee("))
        assertTrue(source.contains("fun ResolvedSection.isTvProgressRow()"))
    }
}
