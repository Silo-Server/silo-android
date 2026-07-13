package org.siloserver.silo.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * The Aurora first-run chrome — eyebrow, glass panel, cream primary button and
 * ghost button — ported from silo-apple's `AuroraStyle.swift`. A warm,
 * cinematic skin (gold accent, plum-night) layered over the onboarding screens.
 */

val AuroraInk = Color(0xFFF3EFE9)
val AuroraAccent = Color(0xFFF3D3A0)
private val AuroraJourneyAccent = Color(0xFFFF7900)
private val AuroraGlassTint = Color(0xFF171019)
private val AuroraCreamTop = Color(0xFFFDF7EC)
private val AuroraCreamBottom = Color(0xFFF1E3CD)
private val AuroraCreamInk = Color(0xFF20160A)
private val AuroraNightBottom = Color(0xFF070509)

/** Compact Server → Account → Profile progress used across TV onboarding. */
@Composable
fun AuroraJourneyProgress(
    currentStep: Int,
    modifier: Modifier = Modifier,
) {
    val labels = listOf("SERVER", "ACCOUNT", "PROFILE")
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { index, _ ->
                val step = index + 1
                val reached = step <= currentStep
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (reached) AuroraJourneyAccent else Color.White.copy(alpha = 0.10f))
                        .border(
                            1.dp,
                            if (reached) AuroraJourneyAccent else Color.White.copy(alpha = 0.20f),
                            RoundedCornerShape(11.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (step < currentStep) "✓" else step.toString(),
                        color = if (reached) Color.Black else Color.White.copy(alpha = 0.48f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (index < labels.lastIndex) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 7.dp)
                            .height(1.dp)
                            .background(
                                if (step < currentStep) AuroraJourneyAccent
                                else Color.White.copy(alpha = 0.16f),
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                Text(
                    text = label,
                    color = if (index + 1 == currentStep) Color.White else Color.White.copy(alpha = 0.42f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (index + 1 == currentStep) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 1.2.sp,
                    ),
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        labels.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Gold-hairline + mono-caps step label, e.g. "STEP 01 — CONNECT". */
@Composable
fun AuroraEyebrow(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
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

/**
 * Liquid-glass panel chrome (translucent plum tint + gradient hairline + top
 * sheen + soft drop shadow; optional gold halo). Compose has no backdrop blur,
 * so the tint is kept translucent enough for the aurora to glow through.
 */
fun Modifier.auroraGlass(cornerRadius: Dp = 28.dp, emphasized: Boolean = false): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .shadow(elevation = 60.dp, shape = shape, clip = false)
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

fun Modifier.auroraPanel(cornerRadius: Dp = 20.dp): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .clip(shape)
        .background(AuroraGlassTint.copy(alpha = 0.42f))
        .border(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.22f),
            shape = shape,
        )
}

/** Warm cream pill with a gold focus glow — the Aurora primary action. */
@Composable
fun AuroraPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
    focusHalo: Boolean = true,
    filledAtRest: Boolean = true,
    neutralFocusFill: Boolean = false,
    enabled: Boolean = true,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val scale by animateFloatAsState(
        if (isFocused && focusHalo) 1.05f else 1f,
        label = "auroraPrimaryScale",
    )
    val glow by animateDpAsState(
        if (!focusHalo) 0.dp else if (isFocused) 26.dp else 14.dp,
        label = "auroraPrimaryGlow",
    )
    val enabledAlpha = if (enabled) 1f else 0.48f
    val fillBrush = if (isFocused && neutralFocusFill) {
        Brush.verticalGradient(listOf(Color.White, Color.White))
    } else if (isFocused || filledAtRest) {
        Brush.verticalGradient(listOf(AuroraCreamTop, AuroraCreamBottom))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.06f)))
    }
    val contentColor = if (isFocused && neutralFocusFill) {
        Color.Black.copy(alpha = 0.88f)
    } else if (isFocused || filledAtRest) {
        AuroraCreamInk
    } else {
        AuroraInk.copy(alpha = 0.68f)
    }

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(
                elevation = glow,
                shape = shape,
                clip = false,
                ambientColor = if (isFocused && focusHalo) AuroraAccent else Color.Black,
                spotColor = if (isFocused && focusHalo) AuroraAccent else Color.Black,
            )
            .clip(shape)
            .background(fillBrush)
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.14f),
                shape = shape,
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = true,
                onClick = { if (enabled) onClick() },
            )
            .padding(horizontal = 30.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = enabledAlpha),
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = label,
                color = contentColor.copy(alpha = enabledAlpha),
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
            )
        }
    }
}

/**
 * Numbered onboarding step — a gold-outlined disc with the step number beside a
 * line of ink copy. Ported from silo-apple's `AuroraStepRow`; used by the
 * phone-first sign-in hero ("1 Scan with your phone's camera", …).
 */
@Composable
fun AuroraStepRow(number: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AuroraAccent.copy(alpha = 0.14f))
                .border(1.dp, AuroraAccent.copy(alpha = 0.55f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                color = AuroraAccent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
            )
        }
        Spacer(Modifier.width(20.dp))
        Text(
            text = text,
            color = AuroraInk.copy(alpha = 0.92f),
            fontWeight = FontWeight.Medium,
            fontSize = 21.sp,
        )
    }
}

/** Tertiary ghost button (e.g. "Use a password instead", "Change server"). */
@Composable
fun AuroraGhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp,
    horizontalPadding: Dp = 22.dp,
    verticalPadding: Dp = 12.dp,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val fill by animateColorAsState(if (isFocused) AuroraInk else Color.White.copy(alpha = 0.06f), label = "auroraGhostFill")
    val content by animateColorAsState(
        if (isFocused) AuroraNightBottom else AuroraInk.copy(alpha = 0.62f),
        label = "auroraGhostContent",
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.14f),
                shape = shape,
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = content, fontWeight = FontWeight.Medium, fontSize = fontSize)
    }
}
