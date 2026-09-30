package org.siloserver.silo.common.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/**
 * A single-line row that shows as many whole children as fit, in order, and
 * drops the rest. A child is never moved to a second line, and is never cut
 * partway unless it opts in with [WholeTokenRowScope.shrinkable]. The row is
 * as wide as the children it shows, so a centering parent centers them.
 * Children are vertically centered.
 *
 * Children marked [WholeTokenRowScope.separator] (a "·") are not tokens: the
 * row shows one only between two shown tokens, so dropping a token never
 * leaves a separator at either end or two in a row.
 */
@Composable
fun WholeTokenRow(
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable WholeTokenRowScope.() -> Unit,
) {
    Layout(content = { WholeTokenRowScope.content() }, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val flags = measurables.map { it.parentData as? TokenFlags ?: TokenFlags() }
        val widths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }

        // The separator written right after token [index], if any. A shown
        // token keeps it only when another token is shown after it.
        fun separatorAfter(index: Int): Int? = (index + 1).takeIf { it < measurables.size && flags[it].separator }

        // Width a token adds after [previous]: the gap, plus previous's separator and its gap.
        fun joinWidth(previous: Int?): Int = if (previous == null) {
            0
        } else {
            gap + (separatorAfter(previous)?.let { widths[it] + gap } ?: 0)
        }

        fun widthOf(tokens: List<Int>): Int =
            tokens.withIndex().sumOf { (k, index) -> joinWidth(tokens.getOrNull(k - 1)) + widths[index] }

        // Too wide: drop the tokens marked dropFirst, last first, before any other.
        val kept = measurables.indices.filterNot { flags[it].separator }.toMutableList()
        while (widthOf(kept) > constraints.maxWidth) {
            val drop = kept.lastOrNull { flags[it].dropFirst } ?: break
            kept.remove(drop)
        }

        // Then keep the leading tokens that fit. A shrinkable token takes the
        // width left and handles its own overflow; any other stops the row.
        val shown = mutableListOf<Pair<Int, Int>>()
        var width = 0
        var previous: Int? = null
        for (index in kept) {
            val start = width + joinWidth(previous)
            val left = constraints.maxWidth - start
            val fits = widths[index] <= left
            if (!fits && !(flags[index].shrinkable && left > 0)) break
            previous?.let(::separatorAfter)?.let { shown += it to widths[it] }
            shown += index to if (fits) widths[index] else left
            if (!fits) break
            width = start + widths[index]
            previous = index
        }

        val placeables = shown.map { (index, maxWidth) ->
            measurables[index].measure(Constraints(maxWidth = maxWidth, maxHeight = constraints.maxHeight))
        }
        val rowWidth = placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0)
        val height = (placeables.maxOfOrNull { it.height } ?: 0)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(rowWidth.coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }
}

object WholeTokenRowScope {
    /** When the row is too wide, drop this token before any unmarked one, starting from the end. */
    fun Modifier.dropFirst(): Modifier = then(TokenFlags(dropFirst = true))

    /**
     * When this token does not fit whole, give it the width left instead of
     * dropping it. The token must end its own overflow, usually with an
     * ellipsis.
     */
    fun Modifier.shrinkable(): Modifier = then(TokenFlags(shrinkable = true))

    /**
     * This child separates the tokens either side of it, and shows only when
     * both of them do.
     */
    fun Modifier.separator(): Modifier = then(TokenFlags(separator = true))
}

private data class TokenFlags(
    val dropFirst: Boolean = false,
    val shrinkable: Boolean = false,
    val separator: Boolean = false,
) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any {
        val current = parentData as? TokenFlags ?: TokenFlags()
        return TokenFlags(
            dropFirst = current.dropFirst || dropFirst,
            shrinkable = current.shrinkable || shrinkable,
            separator = current.separator || separator,
        )
    }
}
