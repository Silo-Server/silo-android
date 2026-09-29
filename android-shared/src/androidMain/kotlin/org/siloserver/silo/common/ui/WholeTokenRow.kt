package org.siloserver.silo.common.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * A single-line row that shows as many whole children as fit, in order, and
 * drops the rest. A child is never clipped partway or moved to a second line.
 * The row is as wide as the children it shows, so a centering parent centers
 * them. Children are vertically centered.
 */
@Composable
fun WholeTokenRow(
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map {
            it.measure(Constraints(maxHeight = constraints.maxHeight))
        }
        val shown = mutableListOf<Placeable>()
        var width = 0
        for (placeable in placeables) {
            val next = width + (if (shown.isEmpty()) 0 else gap) + placeable.width
            if (next > constraints.maxWidth) break
            shown += placeable
            width = next
        }
        val height = (shown.maxOfOrNull { it.height } ?: 0)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width.coerceAtLeast(constraints.minWidth), height) {
            var x = 0
            shown.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }
}
