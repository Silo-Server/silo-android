package org.siloserver.silo.tv.ui.screens.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.math.abs

private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
private const val SPEED_STEP = 0.05f
private const val MIN_SPEED = 0.5f
private const val MAX_SPEED = 3.0f

/**
 * Focusable speed overlay: a fine-adjust row (D-pad ◀/▶ nudge ±0.05, live),
 * a "set as default" row, and preset rows. Selecting a preset applies it and
 * closes (the host clears the panel). Replaces the phone SpeedSheet (spec §4.9).
 */
@Composable
fun TvAudiobookSpeedPanel(
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    onSetDefault: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onFocusAcquisitionFailed: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }

    TvAudiobookOverlayScaffold(
        title = "Speed",
        initialFocus = focusRequester,
        modifier = modifier,
        onAcquisitionFailed = onFocusAcquisitionFailed,
    ) {
        // Eleven rows are taller than a 1080p TV panel; scroll so the focused
        // row (down to 3×) is always brought into view.
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Fine adjust grabs initial focus so ◀/▶ work immediately.
            SpeedFineAdjustRow(
                speed = currentSpeed,
                onNudge = { delta ->
                    onSelectSpeed((currentSpeed + delta).coerceIn(MIN_SPEED, MAX_SPEED))
                },
                focusRequester = focusRequester,
            )
            TvAudiobookOverlayRow(
                label = "Set ${formatSpeedLabel(currentSpeed)} as default",
                isCurrent = false,
                onSelect = { onSetDefault(currentSpeed) },
                modifier = Modifier.fillMaxWidth(),
            )
            SPEED_PRESETS.forEach { speed ->
                TvAudiobookOverlayRow(
                    label = formatSpeedLabel(speed),
                    isCurrent = abs(speed - currentSpeed) < 0.001f,
                    onSelect = { onSelectSpeed(speed) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Single focusable row whose Left/Right nudge the speed by ±[SPEED_STEP]
 *  without closing the panel, for precise tuning between presets. */
@Composable
private fun SpeedFineAdjustRow(
    speed: Float,
    onNudge: (Float) -> Unit,
    focusRequester: FocusRequester,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bg = if (isFocused) Color.White else Color.White.copy(alpha = 0.12f)
    val fg = if (isFocused) Color.Black else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onNudge(-SPEED_STEP); true }
                    Key.DirectionRight -> { onNudge(SPEED_STEP); true }
                    else -> false
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Fine adjust", style = MaterialTheme.typography.bodyLarge, color = fg, modifier = Modifier.weight(1f))
        Text("◀", style = MaterialTheme.typography.bodyLarge, color = fg.copy(alpha = 0.7f))
        Spacer(Modifier.width(16.dp))
        Text(formatSpeedLabel(speed), style = MaterialTheme.typography.bodyLarge, color = fg)
        Spacer(Modifier.width(16.dp))
        Text("▶", style = MaterialTheme.typography.bodyLarge, color = fg.copy(alpha = 0.7f))
    }
}

private fun formatSpeedLabel(speed: Float): String {
    val s = if (speed % 1f == 0f) {
        speed.toInt().toString()
    } else {
        "%.2f".format(speed).trimEnd('0').trimEnd('.')
    }
    return "${s}×"
}
