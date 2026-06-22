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
        assertTrue(!source.contains("animateScrollToItem"))
        assertTrue(source.contains("TvRootHeroBackdrop("))
        assertTrue(source.contains("TvFocusMarquee("))
        assertTrue(source.contains("fun ResolvedSection.isTvProgressRow()"))
    }
}
