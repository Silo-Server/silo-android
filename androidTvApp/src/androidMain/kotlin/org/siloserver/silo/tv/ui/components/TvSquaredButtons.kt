package org.siloserver.silo.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.TvControlCorner

// ---------------------------------------------------------------------------
// Squared control kit — Compose-for-TV port of the silo-apple tvOS
// `TVPillButtonStyle` / `TVCircleButtonStyle` (TVDetailActions.swift).
//
// These controls FULLY OWN their focus appearance. We do NOT use a TV Material3
// `Surface` (which paints its own focus halo/scale) — instead the visuals are
// driven from an `onFocusChanged` flag + an `interactionSource` press flag,
// mirroring how the Apple custom `ButtonStyle` reads `@Environment(\.isFocused)`
// and disables the system focus effect via `.focusEffectDisabled()`.
//
// Apple values port as follows (see design spec §2): unitless scales/opacities
// 1:1; radii at face value (8pt → 8.dp = TvControlCorner). Fonts translate to
// the existing TV token scale.
// ---------------------------------------------------------------------------

/** Shared spring for focus transitions — matches the project's focus grammar. */
private fun <T> focusSpring() = spring<T>(dampingRatio = 0.72f, stiffness = 380f)

/** Fast easeOut for the press transition (Apple `fastDuration`). */
private fun <T> pressTween() = tween<T>(durationMillis = 120)

/**
 * Draws an OUTWARD focus ring [inset] dp beyond the control's bounds, stroked
 * on top of the content. Replaces the (illegal) negative-padding approach —
 * Compose `padding` must be non-negative, and a negative value crashes at
 * focus time. The ring is drawn via [drawWithContent] so it extends past the
 * node's bounds (the Box is unclipped), mirroring Apple's `.padding(-inset)`
 * outline.
 */
private fun Modifier.focusRing(
    visible: Boolean,
    color: Color,
    width: Dp,
    inset: Dp,
    corner: Dp,
): Modifier = drawWithContent {
    drawContent()
    if (!visible) return@drawWithContent
    val insetPx = inset.toPx()
    val strokePx = width.toPx()
    val cornerPx = corner.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(-insetPx, -insetPx),
        size = Size(size.width + insetPx * 2f, size.height + insetPx * 2f),
        cornerRadius = CornerRadius(cornerPx, cornerPx),
        style = Stroke(width = strokePx),
    )
}

internal enum class PillKind { Primary, Secondary }

/**
 * Solid white primary play button — the dominant control in the hero action row.
 * Mirrors `TVPrimaryPillButton` + `TVPillButtonStyle(kind: .primary,
 * focusTreatment: .compact)`.
 *
 * Padding 54h/26v, radius [TvControlCorner], black foreground, fill white@0.76 →
 * focus white, inner border white@0.12/0.8dp → black@0.18/1.8dp.
 */
@Composable
fun TvPrimaryPillButton(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    stableHero: Boolean = false,
) {
    SquaredPill(
        kind = PillKind.Primary,
        icon = icon,
        title = title,
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
        stableHero = stableHero,
    )
}

/**
 * Dark secondary pill that sits next to the primary (e.g. "Start Over").
 * Mirrors `TVSecondaryPillButton` + `TVPillButtonStyle(kind: .secondary,
 * focusTreatment: .compact)`.
 *
 * Padding 40h/22v, foreground white → focus black, fill black@0.52 → focus white,
 * inner border white@0.24/1.2dp → black@0.12/1.5dp.
 */
@Composable
fun TvSecondaryPillButton(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    SquaredPill(
        kind = PillKind.Secondary,
        icon = icon,
        title = title,
        onClick = onClick,
        modifier = modifier,
        focusRequester = focusRequester,
    )
}

@Composable
private fun SquaredPill(
    kind: PillKind,
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier,
    focusRequester: FocusRequester?,
    stableHero: Boolean = false,
) {
    val primary = kind == PillKind.Primary
    SquaredPillSurface(
        kind = kind,
        onClick = onClick,
        modifier = if (stableHero) modifier.height(38.dp) else modifier,
        focusRequester = focusRequester,
        capsule = true,
        stableHero = stableHero,
        contentPadding = PaddingValues(
            horizontal = if (primary) 27.dp else 20.dp,
            vertical = if (stableHero) 0.dp else if (primary) 13.dp else 11.dp,
        ),
    ) { foreground ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // Enlarged glyphs (design review: play 30dp, start-over 20dp) that
            // must NOT grow the pill: the Box reserves the full glyph WIDTH but
            // only the original icon HEIGHT, and the Icon escapes the slot
            // vertically via requiredSize — so the row still measures at the
            // pre-enlargement button height.
            Box(
                modifier = Modifier
                    .width(if (stableHero) 18.dp else if (primary) 30.dp else 20.dp)
                    .height(if (primary) 16.dp else 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier.requiredSize(if (stableHero) 18.dp else if (primary) 30.dp else 20.dp),
                )
            }
            Spacer(Modifier.width(if (primary) 9.dp else 8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = if (stableHero) 14.sp else if (primary) 17.sp else 15.sp,
                ),
                color = foreground,
                maxLines = 1,
            )
        }
    }
}

/**
 * Shared squared-pill surface: paints the §2 fills / inner border / compact focus
 * treatment (ring + glow + lift shadow + focus/press scale) for [kind], and hands
 * the animated [foreground] colour to its [content] slot. Reused by
 * [TvPrimaryPillButton] / [TvSecondaryPillButton] and by `TvAnchoredSelectorMenu`'s
 * trigger so the selector pill matches the secondary `.compact` look exactly.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SquaredPillSurface(
    kind: PillKind,
    onClick: () -> Unit,
    modifier: Modifier,
    focusRequester: FocusRequester?,
    enabled: Boolean = true,
    /** Hero Play/Start Over use tvOS Capsule; selector triggers keep their approved squared shape. */
    capsule: Boolean = false,
    stableHero: Boolean = false,
    contentPadding: PaddingValues,
    content: @Composable (foreground: Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var isFocused by remember { mutableStateOf(false) }

    val shape = if (capsule) RoundedCornerShape(percent = 50) else RoundedCornerShape(TvControlCorner)
    val primary = kind == PillKind.Primary

    // --- Body visuals (per kind) ---
    val foreground by animateColorAsState(
        targetValue = if (stableHero) {
            if (isFocused) Color.Black else Color.White
        } else when (kind) {
            // The Play pill remains the bright primary action at rest. Making
            // it use the same translucent treatment as the circular utility
            // buttons erased the hierarchy and made the whole action cluster
            // look malformed whenever focus moved down to the selector.
            PillKind.Primary -> Color.Black
            PillKind.Secondary -> if (isFocused) Color.Black else Color.White
        },
        animationSpec = focusSpring(),
        label = "pillForeground",
    )
    val fill by animateColorAsState(
        targetValue = if (stableHero) {
            if (isFocused) Color.White else Color.White.copy(alpha = 0.10f)
        } else when (kind) {
            PillKind.Primary -> if (isFocused) Color.White else Color.White.copy(alpha = 0.76f)
            PillKind.Secondary -> if (isFocused) Color.White else Color.Black.copy(alpha = 0.52f)
        },
        animationSpec = focusSpring(),
        label = "pillFill",
    )
    val innerBorderColor by animateColorAsState(
        targetValue = when {
            isFocused && primary -> Color.Black.copy(alpha = 0.18f)
            isFocused -> Color.Black.copy(alpha = 0.12f)
            primary -> Color.White.copy(alpha = 0.12f)
            else -> Color.White.copy(alpha = 0.24f)
        },
        animationSpec = focusSpring(),
        label = "pillInnerBorder",
    )
    val innerBorderWidth by animateDpAsState(
        targetValue = when {
            isFocused && primary -> 0.9.dp
            isFocused -> 0.75.dp
            primary -> 0.8.dp
            else -> 1.2.dp
        },
        animationSpec = focusSpring(),
        label = "pillInnerBorderWidth",
    )

    // --- Compact focus treatment (shared by both pills) ---
    val focusOutlineColor = if (primary) Color.White.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.98f)
    val focusOutlineShape = if (capsule) RoundedCornerShape(percent = 50) else RoundedCornerShape(TvControlCorner + 2.dp)
    val shadowOpacity by animateFloatAsState(
        targetValue = if (isFocused) 0.24f else 0.14f,
        animationSpec = focusSpring(),
        label = "pillShadowOpacity",
    )
    val shadowRadius by animateDpAsState(
        targetValue = if (isFocused) 10.dp else 4.dp,
        animationSpec = focusSpring(),
        label = "pillShadowRadius",
    )
    val focusGlowAlpha by animateFloatAsState(
        targetValue = if (isFocused) 0.08f else 0f,
        animationSpec = focusSpring(),
        label = "pillFocusGlow",
    )

    val focusScale by animateFloatAsState(
        targetValue = if (isFocused && !stableHero) 1.025f else 1f,
        animationSpec = focusSpring(),
        label = "pillFocusScale",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = pressTween(),
        label = "pillPressScale",
    )
    val scale = focusScale * pressScale

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Focus glow (onSurface@0.08, r6) — drawn outermost so it haloes the control.
            .shadow(
                elevation = if (focusGlowAlpha > 0f) 6.dp else 0.dp,
                shape = focusOutlineShape,
                clip = false,
                ambientColor = SiloOnSurface.copy(alpha = focusGlowAlpha),
                spotColor = SiloOnSurface.copy(alpha = focusGlowAlpha),
            )
            // Body lift shadow (black, opacity/radius animated).
            .shadow(
                elevation = shadowRadius,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = shadowOpacity),
                spotColor = Color.Black.copy(alpha = shadowOpacity),
            )
            // Compact focus outline, half-scale port of tvOS width 2.5 / inset 3.
            .focusRing(
                visible = isFocused && !stableHero,
                color = focusOutlineColor,
                width = 1.25.dp,
                inset = 1.5.dp,
                corner = if (capsule) 50.dp else TvControlCorner + 2.dp,
            )
            .background(fill, shape)
            .then(if (stableHero) Modifier else Modifier.border(innerBorderWidth, innerBorderColor, shape))
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content(foreground)
    }
}

/**
 * Circular action with optional tvOS-style label expansion on focus.
 * [titleOnFocus] uses a fixed-height capsule without a focus ring or scale;
 * icon-only callers retain their existing toggle treatment.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSquareToggleButton(
    icon: ImageVector,
    iconActive: ImageVector,
    isActive: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    titleOnFocus: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var isFocused by remember { mutableStateOf(false) }

    val titleStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val expandedWidth = remember(titleOnFocus, textMeasurer, density) {
        val textWidth = textMeasurer.measure(titleOnFocus.orEmpty(), titleStyle).size.width
        with(density) { textWidth.toDp() } + 54.dp
    }
    val capsuleWidth by animateDpAsState(
        targetValue = if (isFocused && titleOnFocus != null) expandedWidth else 38.dp,
        animationSpec = tween(durationMillis = 280),
        label = "toggleWidth",
    )
    val titleAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 180, delayMillis = if (isFocused) 60 else 0),
        label = "toggleTitleAlpha",
    )

    val shape = CircleShape

    val fill by animateColorAsState(
        targetValue = if (isFocused) Color.White else Color.White.copy(alpha = 0.10f),
        animationSpec = focusSpring(),
        label = "toggleFill",
    )
    val foreground by animateColorAsState(
        targetValue = if (isFocused) Color.Black else Color.White,
        animationSpec = focusSpring(),
        label = "toggleForeground",
    )
    val innerBorderColor by animateColorAsState(
        targetValue = if (isFocused) Color.Black.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.34f),
        animationSpec = focusSpring(),
        label = "toggleInnerBorder",
    )
    val innerBorderWidth by animateDpAsState(
        targetValue = if (isFocused) 0.8.dp else 1.4.dp,
        animationSpec = focusSpring(),
        label = "toggleInnerBorderWidth",
    )

    val focusScale by animateFloatAsState(
        targetValue = if (isFocused && titleOnFocus == null) 1.10f else 1f,
        animationSpec = focusSpring(),
        label = "toggleFocusScale",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = pressTween(),
        label = "togglePressScale",
    )
    val scale = focusScale * pressScale

    val focusOutlineShape = CircleShape

    Box(
        modifier = modifier
            .then(
                if (titleOnFocus != null) {
                    Modifier.width(capsuleWidth).height(38.dp)
                } else {
                    Modifier.size(40.dp)
                },
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Focus glow (onSurface@0.15, r10).
            .shadow(
                elevation = if (isFocused) 10.dp else 0.dp,
                shape = focusOutlineShape,
                clip = false,
                ambientColor = SiloOnSurface.copy(alpha = if (isFocused) 0.15f else 0f),
                spotColor = SiloOnSurface.copy(alpha = if (isFocused) 0.15f else 0f),
            )
            // Lift shadow (black@0.34, r16, y6).
            .shadow(
                elevation = if (isFocused) 16.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (isFocused) 0.34f else 0f),
                spotColor = Color.Black.copy(alpha = if (isFocused) 0.34f else 0f),
            )
            // Focus ring: white@0.96, 3dp, inset −2.5.
            .focusRing(
                visible = isFocused && titleOnFocus == null,
                color = Color.White.copy(alpha = 0.96f),
                width = 1.5.dp,
                inset = 2.5.dp,
                corner = 22.dp,
            )
            .background(fill, shape)
            .then(if (titleOnFocus != null) Modifier.clip(shape) else Modifier)
            .then(if (titleOnFocus == null) Modifier.border(innerBorderWidth, innerBorderColor, shape) else Modifier)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .then(if (titleOnFocus != null) Modifier.requiredWidth(if (isFocused) expandedWidth else 38.dp) else Modifier)
                .padding(horizontal = if (isFocused && titleOnFocus != null) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = if (isActive) iconActive else icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(if (titleOnFocus != null) 19.dp else 16.dp),
            )
            if (isFocused && titleOnFocus != null) {
                Text(
                    text = titleOnFocus,
                    color = foreground,
                    style = titleStyle,
                    modifier = Modifier.graphicsLayer { alpha = titleAlpha },
                    maxLines = 1,
                )
            }
        }
    }
}
