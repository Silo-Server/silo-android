package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.player.SleepTimerState

/**
 * Glass-style bottom sheet for arming the sleep timer. Mirrors iOS
 * `SleepTimerSheet` semantics:
 *  - preset rows (15/30/45/60/90) call [onStart] and dismiss the sheet,
 *  - "Cancel Timer" appears when the timer is currently [SleepTimerState.Active],
 *  - the row matching [defaultMinutes] is highlighted (white-on-black) so the
 *    user's last choice is the obvious next pick.
 *
 * Layout matches `PlayerSettingsSheet` and `SubtitleStyleSheet` for visual parity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    isVisible: Boolean,
    activeState: SleepTimerState,
    defaultMinutes: Int,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    // Gear-submenu back affordance: dismisses this sheet and reopens the
    // parent settings sheet (wired in PlayerOverlay).
    onBack: (() -> Unit)? = null,
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissSheet = { scope.dismissPlayerSheet(sheetState, onDismiss) }

    LaunchedEffect(isVisible) {
        if (isVisible) sheetState.show()
    }

    PlayerModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
        tabletopPaneHeight = tabletopPaneHeight,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Keep the sheet handle below the top screen edge — see
                // PlayerSheetSupport.
                .playerSheetContent(tabletopPaneHeight)
                .nestedScroll(PlayerSheetFlingGuard)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1F2937).copy(alpha = 0.95f),
                            Color.Black.copy(alpha = 0.92f),
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                PlayerSheetHeader(
                    title = "Sleep Timer",
                    onBack = onBack?.let { back ->
                        { scope.dismissPlayerSheet(sheetState, back) }
                    },
                    onDismiss = dismissSheet,
                )

                if (activeState is SleepTimerState.Active) {
                    Text(
                        text = "Pausing in ${formatRemainingDetailed(activeState.remainingSeconds)}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                PRESETS.forEach { preset ->
                    PresetRow(
                        label = preset.label,
                        isSelected = preset.minutes == defaultMinutes,
                        onClick = {
                            onStart(preset.minutes)
                            dismissSheet()
                        },
                    )
                }

                if (activeState is SleepTimerState.Active) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PresetRow(
                        label = "Cancel Timer",
                        isSelected = false,
                        isDestructive = true,
                        onClick = {
                            onCancel()
                            dismissSheet()
                        },
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

private data class Preset(val minutes: Int, val label: String)

private val PRESETS = listOf(
    Preset(15, "15 minutes"),
    Preset(30, "30 minutes"),
    Preset(45, "45 minutes"),
    Preset(60, "1 hour"),
    Preset(90, "1 hour 30 minutes"),
)

@Composable
private fun PresetRow(
    label: String,
    isSelected: Boolean,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
        .clickable(onClick = onClick)
        .height(60.dp)
    val styledModifier = when {
        isSelected -> rowModifier.background(color = Color.White, shape = shape)
        isDestructive -> rowModifier
            .background(color = Color.Transparent, shape = shape)
            .border(width = 1.dp, color = Color(0xFFEF4444), shape = shape)
        else -> rowModifier
            .background(color = Color.Transparent, shape = shape)
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.6f), shape = shape)
    }
    Row(
        modifier = styledModifier.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = when {
                isSelected -> Color.Black
                isDestructive -> Color(0xFFEF4444)
                else -> Color.White
            },
            fontSize = 16.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Default",
                color = Color.Black.copy(alpha = 0.5f),
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Compact remaining-time format for chips: "3m", "45s", "1m 12s".
 */
internal fun formatRemaining(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val m = safe / 60
    val s = safe % 60
    return when {
        m == 0 -> "${s}s"
        s == 0 -> "${m}m"
        else -> "${m}m ${s}s"
    }
}

/**
 * Verbose form used for the "Pausing in …" subtitle when the sheet is open.
 */
private fun formatRemainingDetailed(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val m = safe / 60
    val s = safe % 60
    return when {
        m == 0 -> "$s seconds"
        s == 0 && m == 1 -> "1 minute"
        s == 0 -> "$m minutes"
        m == 1 -> "1 minute $s seconds"
        else -> "$m minutes $s seconds"
    }
}
