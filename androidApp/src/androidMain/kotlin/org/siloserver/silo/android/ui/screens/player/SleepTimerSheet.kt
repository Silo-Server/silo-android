package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.player.SleepTimerState

/**
 * Arms the sleep timer. Mirrors iOS `SleepTimerSheet` semantics:
 *  - preset rows (15/30/45/60/90) call [onStart] and close the menu,
 *  - "Cancel timer" appears while the timer is [SleepTimerState.Active],
 *  - the preset matching [defaultMinutes] carries the check, so the user's
 *    last choice is the obvious next pick.
 */
@Composable
fun SleepTimerSheet(
    isVisible: Boolean,
    activeState: SleepTimerState,
    defaultMinutes: Int,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    // Back affordance: hands over to the settings menu (wired in PlayerOverlay).
    onBack: (() -> Unit)? = null,
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val controller = rememberPlayerMenuController(onDismiss)

    PlayerMenu(controller = controller, tabletopPaneHeight = tabletopPaneHeight) {
        PlayerPanelHeader(
            title = "Sleep timer",
            onClose = { controller.dismiss() },
            onBack = onBack?.let { back -> { controller.dismiss(then = back) } },
            backDescription = "Back to settings",
        )
        PlayerMenuScrollColumn(horizontalPadding = 12.dp) {
            if (activeState is SleepTimerState.Active) {
                PlayerFootnote("Pausing in ${formatRemainingDetailed(activeState.remainingSeconds)}")
            }
            Spacer(Modifier.height(8.dp))
            PlayerGroup {
                PRESETS.forEachIndexed { index, preset ->
                    PlayerCheckRow(
                        label = preset.label,
                        selected = preset.minutes == defaultMinutes,
                        onClick = {
                            onStart(preset.minutes)
                            controller.dismiss()
                        },
                        separator = index > 0,
                    )
                }
            }
            if (activeState is SleepTimerState.Active) {
                Spacer(Modifier.height(12.dp))
                PlayerGroup {
                    PlayerActionRow(
                        icon = Icons.Rounded.TimerOff,
                        label = "Cancel timer",
                        tint = PlayerChrome.Stop,
                        onClick = {
                            onCancel()
                            controller.dismiss()
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
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
 * Verbose form used for the "Pausing in …" line while the menu is open.
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
