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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import org.siloserver.silo.tv.ui.focus.TvModalRestoreMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.common.diagnostics.DiagnosticsPrompt

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
    // One claim is not enough here. This prompt is composed immediately after a
    // crash, when the tree is the least settled it will ever be, and
    // requestFocus() throws rather than returning false if its node has not
    // attached yet. Swallowed, that left every button focusable but unfocused —
    // and a leanback app takes no touch input, so the dialog became completely
    // unreachable: no D-pad path, no tap fallback. Crash reports could not be
    // sent from a TV at all.
    // The prompt is a sibling overlay after the NavHost, not a modal window, so
    // the shell underneath stays composed and keeps claiming focus back through
    // its own effects. A single first-frame claim lost that race silently, and
    // one retry a frame later still lost it: the buttons rendered focusable and
    // unfocused while a card behind the dialog held focus. A leanback app takes
    // no touch, so there was no D-pad path and no tap fallback — the dialog was
    // unusable, and with it the only route to sending a crash report.
    //
    // Retry until focus is *observed* rather than until requestFocus() returns
    // true; an accepted request is not an acquired one. Paired with
    // tvModalFocusBoundary() below, which stops the search escaping back out to
    // the page behind.
    var modalHasFocus by remember(prompt.reportId, confirmAlways) { mutableStateOf(false) }
    LaunchedEffect(prompt.reportId, confirmAlways) {
        requestFocusUntilObserved(
            maxAttempts = TvModalRestoreMaxAttempts,
            awaitAttempt = { delay(60L) },
            requestFocus = { safeFocus.requestFocus(); true },
            isFocused = { modalHasFocus },
        )
    }
    // Its own window, not an overlay inside the shell.
    //
    // Composed as a sibling after the NavHost, this sat inside the shell's
    // content Box — which carries a focusRestorer. A restorer intercepts focus
    // *entry* into its subtree and redirects it to the child it remembers, so
    // every claim the prompt made was rerouted to whatever card the viewer had
    // last used. That is why a card behind the dialog held focus while every
    // button in front of it rendered focusable and unfocused, and why neither
    // retrying nor a focus boundary inside the subtree could win: they govern
    // movement once focus is in, and it never got in.
    //
    // A Dialog gets its own window and its own focus, which is what a modal
    // asking a yes/no question needs — and on leanback there is no touch to
    // fall back on when it does not.
    Dialog(
        onDismissRequest = onDontSend,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
    Surface(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .onFocusChanged { modalHasFocus = it.hasFocus }
                .tvModalFocusBoundary(),
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

    // Observed acquisition, same as the prompt above. A fixed "try, wait one
    // frame, try again" is still blind: it cannot tell a claim that landed from
    // one that was dropped, and this dialog is the second step of a flow whose
    // whole purpose is being reachable after a crash.
    LaunchedEffect(Unit) {
        requestFocusUntilObserved(
            maxAttempts = TvModalRestoreMaxAttempts,
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
