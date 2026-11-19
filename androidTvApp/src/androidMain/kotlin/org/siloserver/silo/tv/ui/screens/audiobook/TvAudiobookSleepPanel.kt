package org.siloserver.silo.tv.ui.screens.audiobook

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import org.siloserver.silo.common.player.SleepTimerChoice
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import androidx.compose.ui.res.pluralStringResource

// Apple's set: Off / 15 / 30 / 60 minutes only — no end-of-chapter /
// end-of-book boundary (SleepTimerChoice was trimmed to Off / Minutes). Matches
// the phone AudiobookSleepTimerSheet.
private val SLEEP_OPTIONS = listOf(
    SleepTimerChoice.Off,
    SleepTimerChoice.Minutes(15),
    SleepTimerChoice.Minutes(30),
    SleepTimerChoice.Minutes(60),
)

@Composable
private fun SleepTimerChoice.label(): String = when (this) {
    SleepTimerChoice.Off -> stringResource(CommonR.string.audiobook_sleepTimer_off)
    is SleepTimerChoice.Minutes -> pluralStringResource(CommonR.plurals.player_sleepTimer_minutes, minutes, minutes)
}

/** Focusable sleep-timer overlay mapping rows to the shared VM's
 *  [SleepTimerChoice]. Replaces the phone SleepTimerSheet (spec §4.9). */
@Composable
fun TvAudiobookSleepPanel(
    currentChoice: SleepTimerChoice,
    onSelectSleep: (SleepTimerChoice) -> Unit,
    modifier: Modifier = Modifier,
    onFocusAcquisitionFailed: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val focusIndex = SLEEP_OPTIONS.indexOfFirst { it == currentChoice }.coerceAtLeast(0)
    // Scroll only; the scaffold retries until focus is observed on the row.
    LaunchedEffect(Unit) { listState.scrollToItem(focusIndex) }

    TvAudiobookOverlayScaffold(
        title = stringResource(CommonR.string.player_sleepTimer_title),
        initialFocus = focusRequester,
        modifier = modifier,
        onAcquisitionFailed = onFocusAcquisitionFailed,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(SLEEP_OPTIONS) { i, choice ->
                TvAudiobookOverlayRow(
                    label = choice.label(),
                    isCurrent = choice == currentChoice,
                    focusRequester = if (i == focusIndex) focusRequester else null,
                    onSelect = { onSelectSleep(choice) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
