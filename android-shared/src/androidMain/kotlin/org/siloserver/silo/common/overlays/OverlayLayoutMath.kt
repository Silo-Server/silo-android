package org.siloserver.silo.common.overlays

import kotlin.math.max

/**
 * The card-overlay layout rules that decide placement from measured sizes,
 * kept free of Compose so they can be checked against the web's behaviour
 * (`web/src/components/overlays/CardOverlays.tsx`). All lengths are pixels.
 */
internal object OverlayLayoutMath {

    /**
     * Where the badges of the two bottom corners go (web `BottomRows`). One
     * row per level, counted from the bottom, so the shorter corner lines up
     * with the bottom and a badge shares its width only with the badge at the
     * same height in the other corner.
     *
     * Each row is a CSS `flex flex-wrap` line holding the left badge and the
     * right badge (pushed to the end by `ml-auto`), `column-gap` [gap],
     * `row-gap` [stackGap], `align-items: end`. Each badge's widths here are
     * as measured against [available] (`max-w-full` / `min-w-0`), so a
     * badge alone on a line is never wider than the line. Flex wrapping puts
     * an item on the current line while its hypothetical width plus the gap
     * still fits, and the first item of a line always stays: so a pair that
     * fits shares one line, and a pair that does not puts the right badge on
     * a line of its own below the left one. Sizes never change, only
     * positions.
     *
     * [leftWidths]/[leftHeights] and [rightWidths]/[rightHeights] list each
     * corner's badges top to bottom. Writes each badge's top-left offset,
     * relative to the block's top-left, into [leftX]/[leftY] and
     * [rightX]/[rightY] (same sizes as the inputs) and returns the block's
     * height.
     */
    fun bottomRows(
        leftWidths: IntArray,
        leftHeights: IntArray,
        rightWidths: IntArray,
        rightHeights: IntArray,
        available: Int,
        gap: Int,
        stackGap: Int,
        leftX: IntArray,
        leftY: IntArray,
        rightX: IntArray,
        rightY: IntArray,
    ): Int {
        val leftCount = leftWidths.size
        val rightCount = rightWidths.size
        val levels = max(leftCount, rightCount)
        var y = 0
        for (level in 0 until levels) {
            if (level > 0) y += stackGap
            // Index 0 is the top row, so the shorter corner lines up with the bottom.
            val l = leftCount - levels + level
            val r = rightCount - levels + level
            val hasLeft = l >= 0
            val hasRight = r >= 0
            if (hasLeft && hasRight && !fitsOnOneLine(leftWidths[l], rightWidths[r], available, gap)) {
                leftX[l] = 0
                leftY[l] = y
                y += leftHeights[l] + stackGap
                rightX[r] = available - rightWidths[r]
                rightY[r] = y
                y += rightHeights[r]
                continue
            }
            val lineHeight = max(if (hasLeft) leftHeights[l] else 0, if (hasRight) rightHeights[r] else 0)
            if (hasLeft) {
                leftX[l] = 0
                leftY[l] = y + lineHeight - leftHeights[l]
            }
            if (hasRight) {
                rightX[r] = available - rightWidths[r]
                rightY[r] = y + lineHeight - rightHeights[r]
            }
            y += lineHeight
        }
        return y
    }

    /** Whether a left and a right badge share one bottom row (see [bottomRows]). */
    fun fitsOnOneLine(leftWidth: Int, rightWidth: Int, available: Int, gap: Int): Boolean =
        leftWidth + gap + rightWidth <= available

    /**
     * How many of an edge's band marks show (web `BandMarks`): the marks
     * that fit whole, in order; the rest are dropped. A mark after the first
     * is preceded by a [divider]-wide rule with [gap] on both sides, and
     * [gap] separates it from the mark before. [markWidths] are each mark's
     * width; measure the first against [available] so a first mark too wide
     * on its own comes out at [available] and is truncated, as on web. At
     * least one mark always shows when there is any.
     */
    fun bandVisibleCount(markWidths: IntArray, available: Int, gap: Int, divider: Int): Int {
        if (markWidths.isEmpty()) return 0
        var right = markWidths[0]
        for (index in 1 until markWidths.size) {
            right += gap + divider + gap + markWidths[index]
            if (right > available) return index
        }
        return markWidths.size
    }
}
