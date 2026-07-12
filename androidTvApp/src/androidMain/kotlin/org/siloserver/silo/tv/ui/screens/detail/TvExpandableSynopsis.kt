package org.siloserver.silo.tv.ui.screens.detail

import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * The hero's overview as an expand-in-place control. Mirrors tvOS
 * `TVExpandableSynopsis` 1:1.
 *
 * The overview is clamped to 3 lines at tvOS÷2 sizing (26pt regular → 13sp).
 * Pressing OK/Select toggles [expanded]: when expanded the line clamp is
 * removed AND the [tagline] is shown above the overview (only when non-blank).
 *
 * This is a **focusable leaf** — the hero's only text focus stop, reachable by
 * pressing Up from the action row, and actionable so it never feels "stuck".
 * It owns its own focus visuals: no chrome at rest, on focus a faint
 * `onSurface@0.55` fill (`RoundedRectangle(8.dp)`, no border). The system halo
 * is suppressed (`indication = null`), matching the squared-control idiom.
 *
 * `animateContentSize` (≈120ms easeOut) animates the expand/collapse; the
 * animation is disabled when the device has motion reduced (animator duration
 * scale 0).
 */
@Composable
internal fun TvExpandableSynopsis(
    overview: String,
    tagline: String?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(overview) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(4.dp)

    // Best-effort reduce-motion: animator duration scale of 0 means the user
    // (or the OS) has disabled animations.
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }

    val sizeModifier = if (reduceMotion) {
        Modifier
    } else {
        Modifier.animateContentSize(animationSpec = tween(durationMillis = 160))
    }

    Column(
        modifier = modifier
            .widthIn(max = 600.dp)
            .then(
                if (isFocused) {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { expanded = !expanded }
            // Keep the synopsis text on the same leading edge as the title,
            // episode hierarchy, and metadata rows. The old 10dp horizontal
            // inset made the description visibly drift right.
            .padding(vertical = 7.dp)
            .then(sizeModifier),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (expanded && !tagline.isNullOrBlank()) {
            // tvOS: 28pt serif italic → 14sp, +2 per design review.
            Text(
                text = tagline,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Start,
            )
        }
        // tvOS: 26pt regular → 13sp, +2 per design review (2026-07-11).
        Text(
            text = overview,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            color = Color.White.copy(alpha = 0.82f),
            textAlign = TextAlign.Start,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
