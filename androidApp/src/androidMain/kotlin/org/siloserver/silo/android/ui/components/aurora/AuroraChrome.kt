package org.siloserver.silo.android.ui.components.aurora

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Aurora first-run chrome — palette, eyebrow, glass panel, cream primary
 * button, ghost button, text field, segment, field/error labels — ported from
 * silo-apple's `AuroraStyle.swift` + `AuroraTextField.swift`, scaled for the
 * phone (iOS points ≈ Android dp 1:1). A warm, cinematic skin (gold accent,
 * plum-night) layered over the onboarding screens.
 */

// MARK: palette
val AuroraInk = Color(0xFFF3EFE9)
val AuroraAccent = Color(0xFFF3D3A0)
val AuroraInkSecondary = AuroraInk.copy(alpha = 0.62f)
val AuroraInkTertiary = AuroraInk.copy(alpha = 0.40f)
private val AuroraGlassTint = Color(0xFF171019)
private val AuroraCreamTop = Color(0xFFFDF7EC)
private val AuroraCreamBottom = Color(0xFFF1E3CD)
private val AuroraCreamInk = Color(0xFF20160A)
private val AuroraError = Color(0xFFEF5350)

// MARK: shared control metrics (iOS AuroraControl)
internal val AuroraControlHeight = 52.dp
internal val AuroraControlCorner = 14.dp
private val AuroraActiveFill = Color(0xFFF4EEE2)
private val AuroraActiveInk = Color(0xFF1A1206)
private val AuroraActivePlaceholder = Color(0xFF4A4035)

/** Gold-hairline + mono-caps step label, e.g. "STEP 01 — CONNECT". */
@Composable
fun AuroraEyebrow(text: String, modifier: Modifier = Modifier, centered: Boolean = false) {
    Row(
        modifier = if (centered) modifier.fillMaxWidth() else modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(listOf(AuroraAccent, AuroraAccent.copy(alpha = 0f))),
                ),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = text.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            letterSpacing = 3.5.sp,
            color = AuroraAccent,
        )
    }
}

/** Mono-caps field label (e.g. "SERVER ADDRESS"). */
@Composable
fun AuroraFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier,
        text = text.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 1.6.sp,
        color = AuroraInkTertiary,
    )
}

/** Inline error row. */
@Composable
fun AuroraErrorLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.fillMaxWidth(),
        text = text,
        fontSize = 13.sp,
        color = AuroraError,
    )
}

/**
 * Liquid-glass panel chrome (translucent plum tint + gradient hairline + top
 * sheen + soft drop shadow; optional gold halo). Compose has no backdrop blur,
 * so the tint is kept translucent enough for the aurora to glow through.
 */
fun Modifier.auroraGlass(
    cornerRadius: Dp = 28.dp,
    emphasized: Boolean = false,
    /**
     * The drop shadow reads as depth under a small panel floating on a static
     * screen. Pass 0.dp for a large or moving panel: the fill is translucent,
     * so at full size the shadow's own outline shows *through* the glass as a
     * faint hard-edged box rather than sitting behind it.
     */
    elevation: Dp = 60.dp,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
        .clip(shape)
        .background(AuroraGlassTint.copy(alpha = 0.62f))
        .background(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
            ),
        )
        .border(
            width = 1.dp,
            brush = if (emphasized) {
                Brush.verticalGradient(
                    listOf(AuroraAccent.copy(alpha = 0.6f), AuroraAccent.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f)),
                )
            } else {
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f)),
                )
            },
            shape = shape,
        )
}

/** Warm cream pill — the Aurora primary action. Shows a spinner when loading. */
@Composable
fun AuroraPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isLoading: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(AuroraControlCorner)
    val effectiveEnabled = enabled && !isLoading
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 16.dp, shape = shape, clip = false)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(AuroraCreamTop, AuroraCreamBottom)))
            .clickable(
                enabled = effectiveEnabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 30.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = AuroraCreamInk,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            } else if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = AuroraCreamInk, modifier = Modifier.size(22.dp))
            }
            Text(text = label, color = AuroraCreamInk, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        }
    }
}

/** Tertiary ghost button (e.g. "Use a password instead"). */
@Composable
fun AuroraGhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.14f), shape = shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(text = label, color = AuroraInkSecondary, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            if (trailing != null) trailing()
        }
    }
}

/**
 * Editable Aurora field — controlled input with the Aurora chrome. The
 * placeholder is a controlled overlay so we own its contrast on the plum
 * background. When focused it flips to the warm cream fill + gold ring used
 * across the flow.
 */
@Composable
fun AuroraTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(AuroraControlCorner)

    Column(modifier = modifier.fillMaxWidth()) {
        AuroraFieldLabel(label)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (isFocused) 16.dp else 0.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = AuroraAccent,
                    spotColor = AuroraAccent,
                )
                .clip(shape)
                .background(if (isFocused) AuroraActiveFill else Color.White.copy(alpha = 0.06f))
                .border(
                    width = if (isFocused) 2.dp else 1.5.dp,
                    color = if (isFocused) AuroraAccent else Color.White.copy(alpha = 0.16f),
                    shape = shape,
                )
                .heightIn(min = AuroraControlHeight)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 17.sp,
                            color = if (isFocused) AuroraActivePlaceholder else AuroraInkTertiary,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.merge(
                            TextStyle(
                                color = if (isFocused) AuroraActiveInk else AuroraInk,
                                fontSize = 17.sp,
                            ),
                        ),
                        cursorBrush = SolidColor(if (isFocused) AuroraActiveInk else AuroraAccent),
                        visualTransformation = visualTransformation,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
                        interactionSource = interactionSource,
                    )
                }
                if (trailing != null) {
                    Spacer(Modifier.width(10.dp))
                    trailing()
                }
            }
        }
    }
}

/** Equal-width, single-line segmented option chip (Aurora). */
@Composable
fun AuroraSegment(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(AuroraControlCorner)
    val fill = if (isSelected) AuroraAccent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f)
    val stroke = if (isSelected) AuroraAccent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.14f)
    val textColor = if (isSelected) AuroraInk else AuroraInkSecondary
    Box(
        modifier = modifier
            .height(AuroraControlHeight)
            .clip(shape)
            .background(fill)
            .border(1.5.dp, stroke, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            maxLines = 1,
        )
    }
}
