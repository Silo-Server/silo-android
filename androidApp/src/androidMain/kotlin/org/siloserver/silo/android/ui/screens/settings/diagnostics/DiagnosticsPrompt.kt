package org.siloserver.silo.android.ui.screens.settings.diagnostics

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.siloserver.silo.common.diagnostics.DiagnosticsPrompt

@Composable
fun DiagnosticsPromptDialog(
    prompt: DiagnosticsPrompt,
    onReview: () -> Unit,
    onSend: () -> Unit,
    onAlwaysSend: () -> Unit,
    onDontSend: () -> Unit,
) {
    var confirmAlways by remember { mutableStateOf(false) }
    if (confirmAlways) {
        AlertDialog(
            onDismissRequest = { confirmAlways = false },
            title = { Text("Always send crash reports?") },
            text = { Text("Future eligible reports may be uploaded automatically until you change this setting.") },
            confirmButton = {
                TextButton(onClick = onAlwaysSend) { Text("Always send") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAlways = false }) { Text("Cancel") }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDontSend,
        title = { Text("Silo encountered a problem") },
        text = {
            Text(
                if (prompt.reportCount == 1) {
                    "A ${prompt.reportType.displayName().lowercase()} report is ready. Review it before deciding whether to send it."
                } else {
                    "${prompt.reportCount} diagnostics reports are ready. Review them before deciding whether to send them."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onReview) { Text("Review") }
        },
        dismissButton = {
            ColumnButtons(onSend, { confirmAlways = true }, onDontSend)
        },
    )
}

@Composable
private fun ColumnButtons(
    onSend: () -> Unit,
    onAlwaysSend: () -> Unit,
    onDontSend: () -> Unit,
) {
    TextButton(onClick = onSend) { Text("Send") }
    TextButton(onClick = onAlwaysSend) { Text("Always send") }
    TextButton(onClick = onDontSend) { Text("Don't send") }
}
