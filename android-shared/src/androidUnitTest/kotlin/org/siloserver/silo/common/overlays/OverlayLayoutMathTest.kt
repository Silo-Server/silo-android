package org.siloserver.silo.common.overlays

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Hand-worked cases from the web's CSS: `BottomRows` (flex-wrap rows, one
 * per level from the bottom) and `BandMarks` (marks that fit whole).
 */
class OverlayLayoutMathTest {

    private class Rows(left: List<Pair<Int, Int>>, right: List<Pair<Int, Int>>, available: Int, gap: Int = 8, stackGap: Int = 4) {
        val leftX = IntArray(left.size)
        val leftY = IntArray(left.size)
        val rightX = IntArray(right.size)
        val rightY = IntArray(right.size)
        val height = OverlayLayoutMath.bottomRows(
            leftWidths = left.map { it.first }.toIntArray(),
            leftHeights = left.map { it.second }.toIntArray(),
            rightWidths = right.map { it.first }.toIntArray(),
            rightHeights = right.map { it.second }.toIntArray(),
            available = available,
            gap = gap,
            stackGap = stackGap,
            leftX = leftX, leftY = leftY, rightX = rightX, rightY = rightY,
        )
    }

    @Test
    fun aPairThatFitsSharesOneRowWithTheRightBadgeAtTheEnd() {
        val rows = Rows(left = listOf(60 to 14), right = listOf(40 to 14), available = 169)
        assertEquals(14, rows.height)
        assertContentEquals(intArrayOf(0), rows.leftX)
        assertContentEquals(intArrayOf(129), rows.rightX)
        assertContentEquals(intArrayOf(0), rows.rightY)
    }

    @Test
    fun aPairThatFitsExactlyStaysOnOneRow() {
        val rows = Rows(left = listOf(100 to 14), right = listOf(61 to 14), available = 169)
        assertEquals(14, rows.height)
        assertContentEquals(intArrayOf(0), rows.rightY)
    }

    @Test
    fun aPairOnePixelTooWideWrapsTheRightBadgeOntoItsOwnLine() {
        val rows = Rows(left = listOf(100 to 14), right = listOf(62 to 14), available = 169)
        // Left on top, right below it, still at the end, rowGap = stackGap.
        assertContentEquals(intArrayOf(0), rows.leftY)
        assertContentEquals(intArrayOf(18), rows.rightY)
        assertContentEquals(intArrayOf(107), rows.rightX)
        assertEquals(32, rows.height)
    }

    @Test
    fun aBadgeAsWideAsTheCardTakesTheWholeLineAndPushesItsPartnerDown() {
        // Measured against the line, a too-long badge comes back at the line width.
        val rows = Rows(left = listOf(169 to 14), right = listOf(30 to 14), available = 169)
        assertContentEquals(intArrayOf(0), rows.leftX)
        assertContentEquals(intArrayOf(139), rows.rightX)
        assertContentEquals(intArrayOf(18), rows.rightY)
    }

    @Test
    fun levelsCountFromTheBottomSoTheShorterCornerLinesUpWithTheBottom() {
        // Left has three badges, right one: the right badge pairs with the
        // lowest left badge only.
        val rows = Rows(
            left = listOf(50 to 10, 50 to 10, 50 to 10),
            right = listOf(40 to 10),
            available = 169,
        )
        assertContentEquals(intArrayOf(0, 14, 28), rows.leftY)
        assertContentEquals(intArrayOf(28), rows.rightY)
        assertEquals(38, rows.height)
    }

    @Test
    fun aLongBadgeInOneCornerDoesNotWrapTheOtherCornerAtOtherLevels() {
        // A long top-left-of-bottom badge wraps only its own level's pair.
        val rows = Rows(
            left = listOf(150 to 10, 40 to 10),
            right = listOf(40 to 10, 40 to 10),
            available = 169,
        )
        // Level 0: 150 + 8 + 40 > 169, so it wraps; level 1 fits.
        assertContentEquals(intArrayOf(0, 28), rows.leftY)
        assertContentEquals(intArrayOf(14, 28), rows.rightY)
        assertEquals(38, rows.height)
    }

    @Test
    fun badgesOnOneLineAlignToTheBottomOfTheLine() {
        val rows = Rows(left = listOf(40 to 20), right = listOf(40 to 12), available = 169)
        assertContentEquals(intArrayOf(0), rows.leftY)
        assertContentEquals(intArrayOf(8), rows.rightY)
        assertEquals(20, rows.height)
    }

    @Test
    fun aRightCornerAloneSitsAtTheEnd() {
        val rows = Rows(left = emptyList(), right = listOf(30 to 10, 50 to 10), available = 169)
        assertContentEquals(intArrayOf(139, 119), rows.rightX)
        assertContentEquals(intArrayOf(0, 14), rows.rightY)
    }

    @Test
    fun bandShowsEveryMarkThatFits() {
        // 40 + (7 + 1 + 7 + 30) + (7 + 1 + 7 + 30) = 130
        assertEquals(3, OverlayLayoutMath.bandVisibleCount(intArrayOf(40, 30, 30), available = 130, gap = 7, divider = 1))
    }

    @Test
    fun bandDropsTheFirstMarkThatOverflowsAndEveryMarkAfterIt() {
        // The third mark ends at 131; the fourth, small enough to fit in the
        // space left, still goes, since the row keeps the viewer's order.
        assertEquals(
            2,
            OverlayLayoutMath.bandVisibleCount(intArrayOf(40, 30, 31, 1), available = 130, gap = 7, divider = 1),
        )
    }

    @Test
    fun bandKeepsAFirstMarkTooWideOnItsOwn() {
        // Measured against the row, the first mark comes back at the row
        // width (truncated); every later mark is dropped.
        assertEquals(1, OverlayLayoutMath.bandVisibleCount(intArrayOf(130, 10), available = 130, gap = 7, divider = 1))
        assertEquals(1, OverlayLayoutMath.bandVisibleCount(intArrayOf(130), available = 130, gap = 7, divider = 1))
        assertEquals(0, OverlayLayoutMath.bandVisibleCount(intArrayOf(), available = 130, gap = 7, divider = 1))
    }
}
