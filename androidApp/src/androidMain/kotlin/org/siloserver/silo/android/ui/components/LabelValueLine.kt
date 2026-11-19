package org.siloserver.silo.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A label with a trailing value on one line, measured like a `Row` whose value
 * comes first: the value gets the width it asks for, up to [valueMaxWidth], and
 * the label takes the rest and wraps.
 *
 * The one difference is that the value never takes so much width that the
 * label's longest word stops fitting. A longer translation then wraps the label
 * between words and ellipsizes the value, instead of breaking the label inside
 * a word. When label and value fit, the layout is the same as the `Row`'s.
 *
 * With [labelFirst] it is measured like a `Row` whose label comes first: the
 * label keeps its one-line width and a long value ellipsizes. The label gives
 * way, and wraps, only so that a value of up to half the line shows whole.
 *
 * Needs a bounded width. [label] and [value] each emit one layout node.
 */
@Composable
fun LabelValueLine(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    valueMaxWidth: Dp = Dp.Infinity,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    labelFirst: Boolean = false,
) {
    Layout(contents = listOf(label, value), modifier = modifier) { (labels, values), constraints ->
        val labelNode = labels.first()
        val valueNode = values.first()
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val valueLimit = if (valueMaxWidth == Dp.Infinity) width else minOf(width, valueMaxWidth.roundToPx())
        // A Text's min intrinsic width is its longest word. A word wider than
        // two thirds of the line breaks anyway; the value keeps a third.
        val longestWord = labelNode.minIntrinsicWidth(Constraints.Infinity)
        val labelKeeps = if (labelFirst) {
            val labelLine = labelNode.maxIntrinsicWidth(Constraints.Infinity)
            val valueNeeds = minOf(valueNode.maxIntrinsicWidth(Constraints.Infinity), width / 2)
            maxOf(longestWord, minOf(labelLine, width - gapPx - valueNeeds))
        } else {
            longestWord
        }
        val valueRoom = maxOf(width - gapPx - labelKeeps, width / 3)
        val valuePlaceable = valueNode.measure(Constraints(maxWidth = minOf(valueLimit, valueRoom)))
        val usedGap = if (valuePlaceable.width > 0) gapPx else 0
        val labelWidth = maxOf(0, width - usedGap - valuePlaceable.width)
        val labelPlaceable = labelNode.measure(Constraints.fixedWidth(labelWidth))
        val height = maxOf(labelPlaceable.height, valuePlaceable.height)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            labelPlaceable.placeRelative(0, verticalAlignment.align(labelPlaceable.height, height))
            valuePlaceable.placeRelative(
                width - valuePlaceable.width,
                verticalAlignment.align(valuePlaceable.height, height),
            )
        }
    }
}
