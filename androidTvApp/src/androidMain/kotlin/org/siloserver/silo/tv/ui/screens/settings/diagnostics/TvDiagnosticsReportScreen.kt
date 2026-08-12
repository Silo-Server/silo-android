package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
import org.siloserver.silo.common.diagnostics.DiagnosticsUploadDecision

@Composable
fun TvDiagnosticsReportScreen(
    reportId: String,
    onBack: () -> Unit,
    viewModel: TvDiagnosticsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val report = state.pending.firstOrNull { it.id == reportId }
    var confirmDelete by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var sentShortId by remember { mutableStateOf<String?>(null) }
    var sentState by remember { mutableStateOf("processing") }
    var uploadNotice by remember { mutableStateOf<String?>(null) }
    var uploadNoticeIsError by remember { mutableStateOf(true) }
    BackHandler(onBack = onBack)
    TvDiagnosticsPage(title = "Report details") {
        val shortId = sentShortId
        if (shortId != null) {
            // Successful sends delete the local report, so this renders before
            // the null-report fallback — otherwise success reads as data loss.
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Report sent", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                TvReportLine("Reference ID", shortId)
                TvReportLine("Processing state", sentState.replace('_', ' '))
                Text(
                    "The report was removed from this device after the destination accepted a copy. " +
                        "Share the reference ID with Silo Diagnostics or your server admin.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TvDiagnosticsAction(label = "Done", onClick = onBack)
            }
        } else if (report == null) {
            if (uploading) {
                Text("Sending report…")
            } else {
                Text("This report is no longer on this device.")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text(report.type.tvDisplayName(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(report.capturedAt, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    TvReportLine("Evidence", tvFormatBytes(report.evidenceBytes))
                    TvReportLine(
                        "Destination",
                        if (report.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                            "Silo Diagnostics"
                        } else {
                            report.destinationServerInstanceId
                        },
                    )
                    if (report.destinationKind == DiagnosticsDestinationKind.SELF_HOSTED) {
                        TvReportLine("Captured profile", report.capturedProfileId ?: "Account scoped")
                    }
                    TvReportLine("Expires", tvFormatDate(report.expiresAtEpochMs))
                    TvReportLine("Upload state", report.uploadStatus.name.lowercase().replace('_', ' '))
                    report.uploadErrorCode?.let { TvReportLine("Last error", it) }
                }
                item {
                    TvDiagnosticsSection("ARCHIVE ENTRIES") {
                        report.archiveEntries.forEach { entry ->
                            Text(entry, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 3.dp))
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (uploading) {
                            Text(
                                "Sending report…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvDiagnosticsAction(
                                label = if (uploading) "Sending…" else "Send",
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
                                                uploadNotice = tvUploadKeptMessage(decision)
                                            }
                                            else -> {
                                                uploadNoticeIsError = true
                                                uploadNotice = tvUploadKeptMessage(decision)
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TvDiagnosticsAction(
                                label = "Delete",
                                enabled = !uploading,
                                onClick = { confirmDelete = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete && report != null) {
        TvDiagnosticsConfirmation(
            title = "Delete this report?",
            message = if (report.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                "The local evidence will be removed from this device. If this report was already submitted, " +
                    "its copy in Silo Diagnostics will also be permanently deleted."
            } else {
                "The local evidence will be permanently removed from this device."
            },
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                viewModel.delete(report.id, onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

internal fun tvUploadKeptMessage(decision: DiagnosticsUploadDecision): String = when (decision) {
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
private fun TvReportLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.weight(1.8f))
    }
}
