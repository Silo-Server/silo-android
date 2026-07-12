package org.siloserver.silo.tv.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSkylineSectionFeedSourceTest {
    private val sourceFile = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvSkylineSectionFeed.kt",
    )

    @Test
    fun sharedSkylineFeedOwnsClippedViewAlignedRowBandAndMarquee() {
        assertTrue(sourceFile.exists(), "Shared Skyline feed component must exist")
        val source = sourceFile.readText()

        assertTrue(source.contains("rememberLazyListState()"))
        assertTrue(source.contains("state = rowBandState"))
        assertTrue(source.contains("itemsIndexed("))
        assertTrue(source.contains("val onItemFocused: (SectionItem, String, Int, Int) -> Unit"))
        // Vertical row-band motion is owned once by the feed, matching tvOS'
        // view-aligned row stack, while the Skyline bring-into-view spec keeps
        // card focus from issuing competing vertical relocation requests.
        assertTrue(source.contains("var focusedRowIndex by remember { mutableIntStateOf(-1) }"))
        // Must NOT re-key on rows — that reset focus on every realtime refetch.
        assertFalse(source.contains("var focusedRowIndex by remember(rows)"))
        assertTrue(source.contains("rowBandState.animateScrollToItem(focusedRowIndex)"))
        assertTrue(source.contains("LocalBringIntoViewSpec provides TvSkylineBringIntoViewSpec"))
        assertTrue(source.contains("private const val TvSkylineVerticalContainerRatio = 3f"))
        assertTrue(source.contains("TvSmoothBringIntoViewSpec.calculateScrollDistance"))
        assertTrue(source.contains("onContentUpFallbackChanged?.invoke"))
        assertTrue(source.contains("rowBandState.animateScrollToItem(currentRow - 1)"))
        assertTrue(source.contains("focusManager.moveFocus(FocusDirection.Up)"))
        assertTrue(source.contains("TvRootHeroBackdrop("))
        assertTrue(source.contains("TvFocusMarquee("))
        assertTrue(source.contains("fun ResolvedSection.isTvProgressRow()"))
    }
}
