package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat

/**
 * Secondary control row beneath the transport: speed chip, sleep chip, and a
 * chapters button that opens [ChaptersSheet]. Bookmarks stays top-right on the
 * screen, so it is intentionally not duplicated here.
 */
@Composable
fun AudiobookSecondaryBar(
    speedLabel: String,
    sleepLabel: String,
    skipLabel: String,
    onSpeedClick: () -> Unit,
    onSleepClick: () -> Unit,
    onSkipClick: () -> Unit,
    onChaptersClick: () -> Unit,
    showChapters: Boolean,
    /** Speed differs from 1×: the chip takes the accent, as on Apple. */
    speedActive: Boolean,
    /** A sleep timer is running. */
    sleepActive: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    // iOS optionsRow: HStack(spacing: 12), centered group of translucent
    // capsule chips. (The skip chip is an Android-only extra kept inline.)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipButton(icon = Icons.Filled.Speed, label = speedLabel, onClick = onSpeedClick, isActive = speedActive, accent = accent)
        ChipButton(icon = Icons.Filled.Replay, label = skipLabel, onClick = onSkipClick)
        ChipButton(
            icon = if (sleepActive) Icons.Filled.Bedtime else Icons.Outlined.Bedtime,
            label = sleepLabel,
            onClick = onSleepClick,
            isActive = sleepActive,
            accent = accent,
        )
        if (showChapters) {
            ChipButton(icon = Icons.AutoMirrored.Filled.List, label = stringResource(CommonR.string.audiobook_chapters_open), onClick = onChaptersClick)
        }
    }
}

/**
 * iOS AudioOptionChip: translucent white capsule (8% fill), 1dp outline
 * border, footnote-semibold label, 14×9 padding. An active chip (a speed
 * other than 1×, a running sleep timer) draws its icon, label and border in
 * the cover [accent].
 */
@Composable
internal fun ChipButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    isActive: Boolean = false,
    accent: Color = Color.Unspecified,
) {
    val tint = if (isActive && accent.isSpecified) accent else MaterialTheme.colorScheme.onSurface
    val border = if (isActive && accent.isSpecified) accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TabularNumbers),
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}

internal fun formatSpeed(speed: Float): String = LocaleFormat.decimal(speed.toDouble(), maxFractionDigits = 2)
