package org.siloserver.silo.tv.ui.screens.audiobook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.player.AudiobookSettingsStore

/**
 * Focusable skip-interval overlay: choose how far the transport skip buttons
 * jump for back and forward. On a revision-9 server the choices are the
 * profile-wide contract values and a pick is saved for the whole profile; on
 * an older server they are this device's 10 / 15 / 30 / 60s. While the server
 * has not answered, rows stay focusable but selecting does nothing. TV equivalent of the phone
 * AudiobookSkipIntervalSheet (spec §4.9 — focusable overlay, not a sheet).
 *
 * The rows scroll inside the panel (14 rows outgrow a 1080p overlay); each
 * row brings itself into view when D-pad focus reaches it. [errorMessage]
 * reports a pick the server did not save.
 */
@Composable
fun TvAudiobookSkipIntervalPanel(
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
    onSelectSkipBack: (Int) -> Unit,
    onSelectSkipForward: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onFocusAcquisitionFailed: () -> Unit = {},
    choices: List<Int> = AudiobookSettingsStore.ALLOWED_SKIP,
    profileWide: Boolean = false,
    editable: Boolean = true,
    errorMessage: String? = null,
) {
    val focusRequester = remember { FocusRequester() }
    // A persisted interval outside the allowed set would attach the requester to
    // no row at all, leaving the panel with nothing to acquire.
    val focusValue = skipBackSeconds.takeIf { it in choices } ?: choices.first()

    TvAudiobookOverlayScaffold(
        title = "Skip interval",
        initialFocus = focusRequester,
        modifier = modifier,
        onAcquisitionFailed = onFocusAcquisitionFailed,
    ) {
        // Scroll here, not in the shared scaffold: the chapter and bookmark
        // panels put a LazyColumn in the same slot.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            val note = when {
                !editable -> "Checking the server. Intervals can be changed once it answers."
                profileWide -> "Applies to every device on this profile."
                else -> "Saved on this device. This server does not sync skip intervals."
            }
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
            errorMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF8A80),
                )
            }
            SectionLabel("Skip back")
            choices.forEach { seconds ->
                TvAudiobookOverlayRow(
                    label = "${seconds}s",
                    isCurrent = skipBackSeconds == seconds,
                    focusRequester = if (seconds == focusValue) focusRequester else null,
                    onSelect = { if (editable) onSelectSkipBack(seconds) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            SectionLabel("Skip forward")
            choices.forEach { seconds ->
                TvAudiobookOverlayRow(
                    label = "${seconds}s",
                    isCurrent = skipForwardSeconds == seconds,
                    onSelect = { if (editable) onSelectSkipForward(seconds) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = Color.White.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(4.dp))
}
