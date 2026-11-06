package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.round
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR

/**
 * Speed picker bottom sheet. Slider in 0.05 increments from 0.5–3.0×,
 * plus six preset chips for the common values. Live-previews onChange
 * so the user hears the change as they drag — drag-end isn't required
 * to commit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookSpeedSheet(
    currentSpeed: Float,
    onSpeedChanged: (Float) -> Unit,
    onSetDefault: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Local copy of the speed so the slider tracks user drags before
    // they release; we still emit each value upstream for live preview.
    var pending by remember(currentSpeed) { mutableFloatStateOf(currentSpeed) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
            Text(
                text = stringResource(CommonR.string.player_speed_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(CommonR.string.audiobook_speed_value, formatSpeedLabel(pending)),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 0.5x → 3.0x in 0.05 steps = 50 stops. Slider handles
            // intermediate snapping; we round on emit so floating-point
            // drift doesn't produce odd values.
            Slider(
                value = pending,
                onValueChange = { raw ->
                    val snapped = snapToStep(raw, step = 0.05f).coerceIn(0.5f, 3.0f)
                    if (snapped != pending) {
                        pending = snapped
                        onSpeedChanged(snapped)
                    }
                },
                valueRange = 0.5f..3.0f,
                steps = 49,  // 50 stops → 49 intermediate ticks
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(CommonR.string.audiobook_speed_value, "0.5"), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(CommonR.string.audiobook_speed_value, "1"), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(CommonR.string.audiobook_speed_value, "2"), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(CommonR.string.audiobook_speed_value, "3"), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(CommonR.string.audiobook_speed_presets),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            val presets = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                presets.forEach { preset ->
                    PresetChip(
                        label = stringResource(CommonR.string.audiobook_speed_value, formatSpeedLabel(preset)),
                        selected = pending == preset,
                        onClick = {
                            pending = preset
                            onSpeedChanged(preset)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Persist the current speed as the default applied to future
            // audiobooks (live preview already applied it to this session).
            TextButton(
                onClick = { onSetDefault(pending) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        CommonR.string.audiobook_speed_setDefault,
                        stringResource(CommonR.string.audiobook_speed_value, formatSpeedLabel(pending)),
                    ),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun snapToStep(value: Float, step: Float): Float =
    round(value / step) * step

internal fun formatSpeedLabel(speed: Float): String =
    when {
        speed % 1f == 0f -> speed.toInt().toString()
        speed * 10f % 1f == 0f -> "%.1f".format(speed)
        else -> "%.2f".format(speed).trimEnd('0').trimEnd('.')
    }
