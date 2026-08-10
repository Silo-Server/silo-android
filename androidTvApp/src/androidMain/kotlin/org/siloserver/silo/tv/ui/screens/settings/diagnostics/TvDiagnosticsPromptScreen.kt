package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.common.diagnostics.DiagnosticsPrompt
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved

@Composable
fun TvDiagnosticsPromptScreen(
    prompt: DiagnosticsPrompt,
    onReview: () -> Unit,
    onSend: () -> Unit,
    onAlwaysSend: () -> Unit,
    onDontSend: () -> Unit,
) {
    var confirmAlways by remember { mutableStateOf(false) }
    val safeFocus = remember(prompt.reportId, confirmAlways) { FocusRequester() }
    // Matches the treatment on the crash-prompt-reachability branch: retry
    // until focus is OBSERVED, because an accepted request is not an acquired
    // one and this dialog is the only route to sending a crash report.
    var modalHasFocus by remember(prompt.reportId, confirmAlways) { mutableStateOf(false) }
    LaunchedEffect(prompt.reportId, confirmAlways) {
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = safeFocus::requestFocus,
            isFocused = { modalHasFocus },
        )
    }
    BackHandler(onBack = onDontSend)
    Surface(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .onFocusChanged { modalHasFocus = it.hasFocus }
                .background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(560.dp).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (confirmAlways) {
                    Text("Always send crash reports?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("Future eligible reports may upload automatically until you change this setting.")
                    TvDiagnosticsAction("Always send", onClick = onAlwaysSend)
                    TvDiagnosticsAction(
                        "Cancel",
                        onClick = { confirmAlways = false },
                        modifier = Modifier.focusRequester(safeFocus),
                    )
                } else {
                    Text("Silo encountered a problem", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (prompt.reportCount == 1) {
                            "A ${prompt.reportType.tvDisplayName().lowercase()} report is ready. Review it before deciding whether to send it."
                        } else {
                            "${prompt.reportCount} diagnostics reports are ready. Review them before deciding whether to send them."
                        },
                    )
                    TvDiagnosticsAction("Review", onClick = onReview)
                    TvDiagnosticsAction("Send", onClick = onSend)
                    TvDiagnosticsAction("Always send", onClick = { confirmAlways = true })
                    TvDiagnosticsAction(
                        "Don't send",
                        onClick = onDontSend,
                        modifier = Modifier.focusRequester(safeFocus),
                    )
                }
            }
        }
    }
}

@Composable
internal fun TvDiagnosticsConfirmation(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    var confirmationHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = cancelFocus::requestFocus,
            isFocused = { confirmationHasFocus },
        )
    }
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .onFocusChanged { confirmationHasFocus = it.hasFocus }
                .background(Color.Black.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(message)
                TvDiagnosticsAction(confirmLabel, onClick = onConfirm)
                TvDiagnosticsAction("Cancel", onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus))
            }
        }
    }
}
