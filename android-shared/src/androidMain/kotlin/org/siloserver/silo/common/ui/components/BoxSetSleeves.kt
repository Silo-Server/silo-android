package org.siloserver.silo.common.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Fills and outline of a box set card's sleeves (web `surface-raised`, `surface-hover`, `border`). */
data class BoxSetSleeveColors(
    val back: Color,
    val front: Color,
    val border: Color,
)

/**
 * Two sleeves peeking above a box set's poster so the card reads as a stack
 * of films, as on the web's BoxSetCard. Put it on the poster's frame before
 * any clip: it draws behind the poster and above its top edge without taking
 * any layout space, so the poster stays level with its neighbours. Whatever
 * holds the card must leave room above it, as grid spacing and padding do.
 */
fun Modifier.boxSetSleeves(colors: BoxSetSleeveColors): Modifier = drawBehind { drawBoxSetSleeves(colors) }

/**
 * The web draws the sleeves 3px and 6px above a poster with a 12px radius;
 * these scale with the card from a reference width, so a larger TV card gets
 * proportionally larger sleeves.
 */
private val SleeveReferenceWidth = 140.dp
private val FrontSleeveRise = 3.dp
private val BackSleeveRise = 6.dp
private val SleeveCornerRadius = 12.dp

private fun DrawScope.drawBoxSetSleeves(colors: BoxSetSleeveColors) {
    val scale = size.width / SleeveReferenceWidth.toPx()
    val radius = SleeveCornerRadius.toPx() * scale
    val stroke = 1.dp.toPx()
    // Back sleeve first, inset 10% a side; the front one over it, inset 5%.
    drawSleeve(inset = 0.10f, rise = BackSleeveRise.toPx() * scale, radius, stroke, colors.back, colors.border)
    drawSleeve(inset = 0.05f, rise = FrontSleeveRise.toPx() * scale, radius, stroke, colors.front, colors.border)
}

private fun DrawScope.drawSleeve(inset: Float, rise: Float, radius: Float, stroke: Float, fill: Color, border: Color) {
    val left = size.width * inset
    val right = size.width - left
    // Runs on below the poster's top edge, where the poster covers it, so only
    // the strip above the poster and its rounded top corners show.
    val sleeve = Path().apply {
        addRoundRect(
            RoundRect(
                left = left, top = -rise, right = right, bottom = radius,
                topLeftCornerRadius = CornerRadius(radius),
                topRightCornerRadius = CornerRadius(radius),
            ),
        )
    }
    drawPath(sleeve, fill)
    drawPath(sleeve, border, style = Stroke(width = stroke))
}
