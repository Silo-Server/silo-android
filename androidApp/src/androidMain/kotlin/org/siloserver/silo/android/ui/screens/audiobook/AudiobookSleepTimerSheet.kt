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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.player.SleepTimerChoice
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import androidx.compose.ui.res.pluralStringResource

/**
 * Sleep timer picker bottom sheet. Matches Apple's audiobook player:
 * minute presets only (15 / 30 / 60 min) plus "Off" to cancel — no
 * end-of-chapter / end-of-book boundary options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookSleepTimerSheet(
    currentChoice: SleepTimerChoice,
    minutesLeft: Int?,
    onChoiceSelected: (SleepTimerChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
            Text(
                text = stringResource(CommonR.string.player_sleepTimer_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (minutesLeft != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pluralStringResource(CommonR.plurals.audiobook_sleepTimer_remaining, minutesLeft, minutesLeft),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            val items = listOf(
                pluralStringResource(CommonR.plurals.player_sleepTimer_minutes, 15, 15) to SleepTimerChoice.Minutes(15),
                pluralStringResource(CommonR.plurals.player_sleepTimer_minutes, 30, 30) to SleepTimerChoice.Minutes(30),
                pluralStringResource(CommonR.plurals.player_sleepTimer_minutes, 60, 60) to SleepTimerChoice.Minutes(60),
                stringResource(CommonR.string.audiobook_sleepTimer_off) to SleepTimerChoice.Off,
            )

            items.forEach { (label, choice) ->
                ChoiceRow(
                    label = label,
                    selected = choiceEquals(currentChoice, choice),
                    onClick = {
                        onChoiceSelected(choice)
                        onDismiss()
                    },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private fun choiceEquals(a: SleepTimerChoice, b: SleepTimerChoice): Boolean = when {
    a is SleepTimerChoice.Off && b is SleepTimerChoice.Off -> true
    a is SleepTimerChoice.Minutes && b is SleepTimerChoice.Minutes -> a.minutes == b.minutes
    else -> false
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Bedtime,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(start = 12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(CommonR.string.player_menu_selected),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
