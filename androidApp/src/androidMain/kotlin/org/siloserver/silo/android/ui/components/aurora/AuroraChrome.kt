package org.siloserver.silo.android.ui.components.aurora

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Aurora chrome — palette, glass panel, cream primary button and ghost
 * button — used by the onboarding tour, ported from silo-apple's Aurora
 * styles and scaled for the phone (iOS points ≈ Android dp 1:1). The other
 * first-run screens use the Marquee components instead.
 */

// MARK: palette
val AuroraInk = Color(0xFFF3EFE9)
val AuroraAccent = Color(0xFFF3D3A0)
val AuroraInkSecondary = AuroraInk.copy(alpha = 0.62f)
private val AuroraGlassTint = Color(0xFF171019)
private val AuroraCreamTop = Color(0xFFFDF7EC)
private val AuroraCreamBottom = Color(0xFFF1E3CD)
private val AuroraCreamInk = Color(0xFF20160A)

// MARK: shared control metrics (iOS AuroraControl)
internal val AuroraControlHeight = 52.dp
internal val AuroraControlCorner = 14.dp

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
