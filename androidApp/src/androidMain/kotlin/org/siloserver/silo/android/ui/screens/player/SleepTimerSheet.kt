package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
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
            title = stringResource(CommonR.string.player_sleepTimer_title),
            onClose = { controller.dismiss() },
            onBack = onBack?.let { back -> { controller.dismiss(then = back) } },
            backDescription = stringResource(CommonR.string.player_menu_backToSettings),
        )
        PlayerMenuScrollColumn(horizontalPadding = 12.dp) {
            if (activeState is SleepTimerState.Active) {
                PlayerFootnote(
                    stringResource(
                        CommonR.string.player_sleepTimer_pausingIn,
                        formatRemainingDetailed(activeState.remainingSeconds),
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            PlayerGroup {
                PRESET_MINUTES.forEachIndexed { index, minutes ->
                    PlayerCheckRow(
                        label = formatDuration(minutes * 60),
                        selected = minutes == defaultMinutes,
                        onClick = {
                            onStart(minutes)
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
                        label = stringResource(CommonR.string.player_sleepTimer_cancel),
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

private val PRESET_MINUTES = listOf(15, 30, 45, 60, 90)

/** A preset's length in words: "45 minutes", "1 hour", "1 hour 30 minutes". */
@Composable
private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    return listOfNotNull(
        hours.takeIf { it > 0 }?.let { pluralStringResource(CommonR.plurals.player_sleepTimer_hours, it, it) },
        minutes.takeIf { it > 0 }?.let { pluralStringResource(CommonR.plurals.player_sleepTimer_minutes, it, it) },
    ).joinToString(" ")
}

/**
 * Compact remaining-time format for chips: "3m", "45s", "1m 12s".
 */
@Composable
internal fun formatRemaining(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val m = LocaleFormat.integer(safe / 60)
    val s = LocaleFormat.integer(safe % 60)
    return when {
        safe < 60 -> stringResource(CommonR.string.catalog_runtimeSeconds, s)
        safe % 60 == 0 -> stringResource(CommonR.string.catalog_runtimeMinutesShort, m)
        else -> stringResource(CommonR.string.catalog_runtimeMinutesSeconds, m, s)
    }
}

/**
 * Verbose form used for the "Pausing in …" line while the menu is open.
 */
@Composable
private fun formatRemainingDetailed(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val m = safe / 60
    val s = safe % 60
    val minutes = pluralStringResource(CommonR.plurals.player_sleepTimer_minutes, m, m)
    val secondsLabel = pluralStringResource(CommonR.plurals.player_sleepTimer_seconds, s, s)
    return when {
        m == 0 -> secondsLabel
        s == 0 -> minutes
        else -> "$minutes $secondsLabel"
    }
}
