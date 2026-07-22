package org.siloserver.silo.android.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.siloserver.silo.common.diagnostics.DiagnosticsViewModel
import org.siloserver.silo.common.diagnostics.consent.PendingReportStore
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType

/**
 * Root-level crash/ANR consent prompt. Renders nothing while
 * [DiagnosticsViewModel.prompt] is null; when a prompt is pending it shows a
 * modal that can only be resolved by an explicit choice (no outside-tap or
 * back-press dismissal — the user must decide what happens to the report).
 */
@Composable
fun CrashPromptDialog(viewModel: DiagnosticsViewModel) {
    val prompt by viewModel.prompt.collectAsState()
    val current = prompt ?: return
    val state by viewModel.state.collectAsState()

    // Reset per prompt so a later prompt doesn't inherit expanded details
    // or a half-finished Always confirmation.
    var showDetails by remember(current) { mutableStateOf(false) }
    var confirmAlways by remember(current) { mutableStateOf(false) }

    val reports = current.reports
    val plural = reports.size > 1

    AlertDialog(
        onDismissRequest = { /* must be resolved by an explicit button */ },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        ),
        title = {
            Text(if (plural) "Send diagnostics reports?" else "Send a diagnostics report?")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(promptMessage(reports))
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide Report" else "View Report")
                }
                if (showDetails) {
                    reports.forEach { report ->
                        PromptReportSummary(report = report, serverName = state.serverName)
                    }
                }
            }
        },
        confirmButton = {
            // iOS-style stacked actions: AlertDialog only offers two slots,
            // so the three choices stack vertically in the confirm slot.
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { viewModel.acceptPrompt(false) }) {
                    Text("Send")
                }
                TextButton(onClick = { confirmAlways = true }) {
                    Text("Always Send")
                }
                TextButton(onClick = { viewModel.declinePrompt() }) {
                    Text("Don't Send")
                }
            }
        },
    )

    if (confirmAlways) {
        AlertDialog(
            onDismissRequest = { confirmAlways = false },
            title = { Text("Always Send Crash Reports?") },
            text = {
                Text(
                    "Any pending reports and future crash or hang reports for this " +
                        "server account will be sent automatically.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAlways = false
                        viewModel.acceptPrompt(true)
                    },
                ) {
                    Text("Always Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAlways = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** Per-report inline summary shown by "View Report". */
@Composable
private fun PromptReportSummary(
    report: PendingReportStore.PendingReport,
    serverName: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = reportTypeLabel(report.binding.type),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = formatDiagnosticsDate(report.binding.capturedAtEpochMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        report.manifestDraft.crash?.summary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = "${report.manifestDraft.deviceSummary.model} → ${serverName ?: "your server"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun promptMessage(reports: List<PendingReportStore.PendingReport>): String {
    val base = if (reports.size == 1) {
        when (reports.first().binding.type) {
            DiagnosticsReportType.ANR,
            DiagnosticsReportType.HANG,
            -> "Silo stopped responding. Send a diagnostic report to your server?"
            DiagnosticsReportType.MANUAL -> "A diagnostics report is ready to send."
            else -> "Silo closed unexpectedly. Send a diagnostic report to your server?"
        }
    } else {
        "Silo had ${reports.size} problems recently."
    }
    val review = if (reports.size == 1) {
        "You can review the report before it is sent."
    } else {
        "You can review the reports before they are sent."
    }
    return "$base $review"
}
