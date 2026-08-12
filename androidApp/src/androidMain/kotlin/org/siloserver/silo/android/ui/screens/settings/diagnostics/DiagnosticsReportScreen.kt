package org.siloserver.silo.android.ui.screens.settings.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.settings.SettingsSectionCard
import org.siloserver.silo.android.ui.screens.settings.SettingsSectionHeader
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
import org.siloserver.silo.common.diagnostics.DiagnosticsUploadDecision

@Composable
fun DiagnosticsReportScreen(
    reportId: String,
    onBackClick: () -> Unit,
    viewModel: DiagnosticsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val report = state.pending.firstOrNull { it.id == reportId }
    var confirmDelete by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var sentShortId by remember { mutableStateOf<String?>(null) }
    var sentState by remember { mutableStateOf("processing") }
    var uploadNotice by remember { mutableStateOf<String?>(null) }
    var uploadNoticeIsError by remember { mutableStateOf(true) }
    Scaffold(
        topBar = { SiloTopBar(title = "Report details", onBackClick = onBackClick) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val shortId = sentShortId
        when {
            // A successful send removes the pending report, so this must render
            // before the null-report fallback — otherwise a completed upload
            // reads as the report having vanished.
            shortId != null -> DiagnosticsSentConfirmation(
                shortId = shortId,
                state = sentState,
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                onDone = onBackClick,
            )
            report == null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                if (uploading) {
                    // The pending list can drop the report a frame before the
                    // upload callback delivers the reference id.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("Sending report…")
                    }
                } else {
                    Text("This report is no longer on this device.")
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    SettingsSectionCard {
                        Column(Modifier.padding(16.dp)) {
                            Text(report.type.displayName(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(report.capturedAt, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            DetailLine("Evidence", formatDiagnosticBytes(report.evidenceBytes))
                            DetailLine(
                                "Destination",
                                if (report.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                                    "Silo Diagnostics"
                                } else {
                                    report.destinationServerInstanceId
                                },
                            )
                            if (report.destinationKind == DiagnosticsDestinationKind.SELF_HOSTED) {
                                DetailLine("Captured profile", report.capturedProfileId ?: "Account scoped")
                            }
                            DetailLine("Expires", formatDiagnosticDate(report.expiresAtEpochMs))
                            DetailLine("Upload state", report.uploadStatus.name.lowercase().replace('_', ' '))
                            report.uploadErrorCode?.let { DetailLine("Last error", it) }
                        }
                    }
                }
                item {
                    SettingsSectionCard {
                        SettingsSectionHeader("Archive entries")
                        report.archiveEntries.forEach { entry ->
                            Text(
                                entry,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (uploading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(12.dp))
                                Text(
                                    "Sending report…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        uploadNotice?.let { notice ->
                            Text(
                                notice,
                                color = if (uploadNoticeIsError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                enabled = state.availability == DiagnosticsAvailabilityUi.AVAILABLE && !uploading,
                                onClick = {
                                    uploading = true
                                    uploadNotice = null
                                    viewModel.upload(report.id) { decision ->
                                        uploading = false
                                        when (decision) {
                                            is DiagnosticsUploadDecision.Uploaded -> {
                                                sentShortId = decision.shortId
                                                sentState = decision.state.wireValue
                                            }
                                            is DiagnosticsUploadDecision.HostedProcessing -> {
                                                uploadNoticeIsError = false
                                                uploadNotice = uploadKeptMessage(decision)
                                            }
                                            else -> {
                                                uploadNoticeIsError = true
                                                uploadNotice = uploadKeptMessage(decision)
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (uploading) "Sending…" else "Send") }
                            OutlinedButton(
                                enabled = !uploading,
                                onClick = { confirmDelete = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && report != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this report?") },
            text = {
                Text(
                    if (report.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                        "The local evidence will be removed from this device. If this report was already " +
                            "submitted, its copy in Silo Diagnostics will also be permanently deleted."
                    } else {
                        "The local evidence will be permanently removed from this device."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(report.id, onBackClick)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DiagnosticsSentConfirmation(
    shortId: String,
    state: String,
    modifier: Modifier,
    onDone: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsSectionCard {
            Column(Modifier.padding(16.dp)) {
                Text("Report sent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reference ID", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text(shortId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(shortId)) }) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy reference ID",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                DetailLine("Processing state", state.replace('_', ' '))
                Spacer(Modifier.height(12.dp))
                Text(
                    "The report was removed from this device after the destination accepted a copy. " +
                        "Share the reference ID with Silo Diagnostics or your server admin.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

internal fun uploadKeptMessage(decision: DiagnosticsUploadDecision): String = when (decision) {
    is DiagnosticsUploadDecision.Uploaded -> "" // handled by the caller
    is DiagnosticsUploadDecision.HostedProcessing ->
        "Report ${decision.shortId} was accepted and is still processing. It will be checked again automatically."
    DiagnosticsUploadDecision.KeptRetryable ->
        "The upload didn't go through. The report stays on this device to try again later."
    DiagnosticsUploadDecision.KeptIdentityChanged ->
        "Sign-in changed during the upload. The report stays on this device."
    DiagnosticsUploadDecision.KeptTooLarge ->
        "This report is larger than the server accepts."
    DiagnosticsUploadDecision.KeptServerUpdateRequired ->
        "Your server needs an update to accept this report."
    DiagnosticsUploadDecision.KeptUnavailable ->
        "Diagnostics uploads aren't available right now. The report stays on this device."
    DiagnosticsUploadDecision.KeptInvalid ->
        "This report couldn't be read and can't be sent."
    DiagnosticsUploadDecision.KeptConsentReviewRequired ->
        "The server's consent notice changed. Review it and send again."
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.weight(1.4f))
    }
}
