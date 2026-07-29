package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.model.section.SectionItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvSkylinePrefetchPolicyTest {
    private val items = listOf("a", "b", "c", "d", "e").map(::item)

    @Test
    fun rapidFocusBeforeMarqueeSettlementStartsNoNeighborWork() {
        assertTrue(
            settledPrefetchItems(
                items = items,
                rawFocusedContentId = "d",
                settledContentId = "b",
            ).isEmpty(),
        )
    }

    @Test
    fun settledFocusReturnsOnlyTwoNeighborsPerSide() {
        assertEquals(
            listOf("a", "b", "d", "e"),
            settledPrefetchItems(
                items = items,
                rawFocusedContentId = "c",
                settledContentId = "c",
            ).map { it.contentId },
        )
    }

    @Test
    fun firstCardReturnsOnlyFollowingNeighbors() {
        assertEquals(
            listOf("b", "c"),
            settledPrefetchItems(
                items = items,
                rawFocusedContentId = "a",
                settledContentId = "a",
            ).map { it.contentId },
        )
    }

    @Test
    fun missingSettledIdentityStartsNoNeighborWork() {
        assertTrue(
            settledPrefetchItems(
                items = items,
                rawFocusedContentId = "missing",
                settledContentId = "missing",
            ).isEmpty(),
        )
    }

    @Test
    fun sameContentInDifferentRowWaitsForRowQualifiedSettlement() {
        assertEquals(
            null,
            settledFocusIdentity(
                rawRowIndex = 1,
                rawFocusedContentId = "a",
                rawFocusedMarqueeId = "row-1#a",
                settledMarqueeId = "row-0#a",
            ),
        )
        assertEquals(
            TvSkylineSettledFocus(rowIndex = 1, contentId = "a"),
            settledFocusIdentity(
                rawRowIndex = 1,
                rawFocusedContentId = "a",
                rawFocusedMarqueeId = "row-1#a",
                settledMarqueeId = "row-1#a",
            ),
        )
    }

    @Test
    fun unsettledFocusHasNoPrefetchIdentity() {
        assertEquals(
            null,
            settledFocusIdentity(
                rawRowIndex = 1,
                rawFocusedContentId = "b",
                rawFocusedMarqueeId = "row-1#b",
                settledMarqueeId = "row-0#a",
            ),
        )
    }

    private fun item(id: String) = SectionItem(
        contentId = id,
        type = "movie",
        title = id,
    )
}
