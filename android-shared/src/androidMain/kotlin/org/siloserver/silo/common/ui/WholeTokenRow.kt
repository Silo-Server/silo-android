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
        fun widthOf(indices: List<Int>) = indices.sumOf { widths[it] } + gap * (indices.size - 1).coerceAtLeast(0)

        // Too wide: drop the tokens marked dropFirst, last first, before any other.
        val kept = measurables.indices.toMutableList()
        while (widthOf(kept) > constraints.maxWidth) {
            val drop = kept.lastOrNull { flags[it].dropFirst } ?: break
            kept.remove(drop)
        }

        // Then keep the leading tokens that fit. A shrinkable token takes the
        // width left and handles its own overflow; any other stops the row.
        val shown = mutableListOf<Pair<Int, Int>>()
        var width = 0
        for (index in kept) {
            val start = width + if (shown.isEmpty()) 0 else gap
            val left = constraints.maxWidth - start
            if (widths[index] <= left) {
                shown += index to widths[index]
                width = start + widths[index]
                continue
            }
            if (flags[index].shrinkable && left > 0) {
                shown += index to left
                width = constraints.maxWidth
            }
            break
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
    /**
     * When the row is too wide, drop this token before any unmarked one,
     * starting from the end. Do not mark the first token: later tokens may
     * carry a leading divider that assumes something precedes them.
     */
    fun Modifier.dropFirst(): Modifier = then(TokenFlags(dropFirst = true))

    /**
     * When this token does not fit whole, give it the width left instead of
     * dropping it. The token must end its own overflow, usually with an
     * ellipsis.
     */
    fun Modifier.shrinkable(): Modifier = then(TokenFlags(shrinkable = true))
}

private data class TokenFlags(
    val dropFirst: Boolean = false,
    val shrinkable: Boolean = false,
) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any {
        val current = parentData as? TokenFlags ?: TokenFlags()
        return TokenFlags(
            dropFirst = current.dropFirst || dropFirst,
            shrinkable = current.shrinkable || shrinkable,
        )
    }
}
