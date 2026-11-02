package org.siloserver.silo.tv.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.CardScale
import org.siloserver.silo.common.ui.marquee.rememberReduceMotion

/**
 * Signature focus grammar for non-Card focusables (rail items, chips, pills,
 * action rows). Composes:
 *
 *  - Small scale-on-focus
 *  - Neutral white outline
 *  - Soft shadow lift
 *
 * Every focusable surface in the TV UI uses either this modifier or
 * [siloCardDefaults] (for Cards) — pick the right one for the surface
 * type, not both.
 */
fun Modifier.siloFocus(
    interactionSource: MutableInteractionSource,
    shape: Shape = RoundedCornerShape(6.dp),
    focusedScale: Float = 1.06f,
    borderColor: Color = AccentLavender,
    borderWidth: androidx.compose.ui.unit.Dp = 2.dp,
): Modifier = composed {
    val focused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 380f,
        ),
        label = "siloFocusScale",
    )
    val shadowElevation by animateFloatAsState(
        targetValue = if (focused) 14f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "siloFocusShadow",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.shadowElevation = shadowElevation
            this.shape = shape
            clip = false
            ambientShadowColor = CardShadowColor
            spotShadowColor = CardShadowColor
        }
        .border(
            border = BorderStroke(
                width = if (focused) borderWidth else 0.dp,
                color = if (focused) borderColor else Color.Transparent,
            ),
            shape = shape,
        )
}

/**
 * The white outline that marks a focused card, and the Series row's current
 * episode. Mirrors tvOS `TVCardOutline` and `TVCardOutlineButtonStyle`.
 */
object CardOutline {
    val width = 2.dp
    val color = Color.White.copy(alpha = 0.70f)
    /** A small lift; tvOS uses the same 5% on every card. */
    const val FocusedScale = 1.05f
    /** The focused card's drop shadow, in place of a light glow. */
    val shadowColor = Color.Black.copy(alpha = 0.45f)
    val shadowElevation = 12.dp
}

/** The [CardOutline] lift, or none when the system asks for reduced motion. */
@Composable
fun cardFocusedScale(focusedScale: Float = CardOutline.FocusedScale): Float =
    if (rememberReduceMotion()) 1f else focusedScale

/**
 * Signature Card focus behavior for TV Material3 [androidx.tv.material3.Card]s
 * (the poster/episode/cast cards): a small lift, the [CardOutline] white
 * outline and a soft drop shadow, matching tvOS's outlined cards. Under
 * reduced motion cards keep their size; the outline and shadow mark focus.
 *
 * Usage:
 * ```
 * val defaults = siloCardDefaults(cardShape)
 * Card(
 *     scale = defaults.scale,
 *     border = defaults.border,
 *     glow = defaults.glow,
 *     …
 * )
 * ```
 */
@OptIn(ExperimentalTvMaterial3Api::class)
data class SiloCardFocus(
    val scale: CardScale,
    val border: androidx.tv.material3.CardBorder,
    val glow: androidx.tv.material3.CardGlow,
    /** [scale]'s focused value, which CardScale keeps internal; at rest and pressed it is 1. */
    val focusedScale: Float = 1f,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun siloCardDefaults(
    shape: Shape,
    focusedScale: Float = CardOutline.FocusedScale,
    /** Outlines the card at rest too, such as the item being viewed in its row. */
    outlined: Boolean = false,
): SiloCardFocus {
    val scale = cardFocusedScale(focusedScale)
    val outline = Border(
        border = BorderStroke(CardOutline.width, CardOutline.color),
        shape = shape,
    )
    return SiloCardFocus(
        scale = CardDefaults.scale(focusedScale = scale),
        focusedScale = scale,
        border = if (outlined) {
            CardDefaults.border(border = outline, focusedBorder = outline)
        } else {
            CardDefaults.border(focusedBorder = outline)
        },
        glow = CardDefaults.glow(
            focusedGlow = Glow(
                elevationColor = CardOutline.shadowColor,
                elevation = CardOutline.shadowElevation,
            ),
        ),
    )
}
