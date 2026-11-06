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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
import org.siloserver.silo.common.diagnostics.DiagnosticsUploadDecision
import org.siloserver.silo.common.diagnostics.diagnosticsReportStateLabel
import org.siloserver.silo.common.diagnostics.diagnosticsSizeLabel
import org.siloserver.silo.common.diagnostics.diagnosticsUploadNotice
import org.siloserver.silo.common.diagnostics.diagnosticsUploadStatusLabel
import org.siloserver.silo.common.R as CommonR

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
    var uploadNotice by remember { mutableStateOf<DiagnosticsUploadDecision?>(null) }
    var uploadNoticeIsError by remember { mutableStateOf(true) }
    BackHandler(onBack = onBack)
    TvDiagnosticsPage(title = stringResource(CommonR.string.diagnostics_report_title)) {
        val shortId = sentShortId
        if (shortId != null) {
            // Successful sends delete the local report, so this renders before
            // the null-report fallback — otherwise success reads as data loss.
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(CommonR.string.diagnostics_report_sent), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                TvReportLine(stringResource(CommonR.string.diagnostics_report_referenceId), shortId)
                TvReportLine(
                    stringResource(CommonR.string.diagnostics_report_processingState),
                    diagnosticsReportStateLabel(sentState),
                )
                Text(
                    stringResource(CommonR.string.diagnostics_report_sentBody),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TvDiagnosticsAction(label = stringResource(CommonR.string.common_done), onClick = onBack)
            }
        } else if (report == null) {
            if (uploading) {
                Text(stringResource(CommonR.string.diagnostics_report_sending))
            } else {
                Text(stringResource(CommonR.string.diagnostics_report_gone))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text(report.type.tvDisplayName(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(report.capturedAt, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    TvReportLine(
                        stringResource(CommonR.string.diagnostics_report_evidence),
                        diagnosticsSizeLabel(report.evidenceBytes),
                    )
                    TvReportLine(
                        stringResource(CommonR.string.diagnostics_row_destination),
                        if (report.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                            stringResource(CommonR.string.diagnostics_destination_hosted)
                        } else {
                            report.destinationServerInstanceId
                        },
                    )
                    if (report.destinationKind == DiagnosticsDestinationKind.SELF_HOSTED) {
                        TvReportLine(
                            stringResource(CommonR.string.diagnostics_report_capturedProfile),
                            report.capturedProfileId ?: stringResource(CommonR.string.diagnostics_report_accountScoped),
                        )
                    }
                    TvReportLine(
                        stringResource(CommonR.string.diagnostics_report_expires),
                        tvFormatDate(report.expiresAtEpochMs),
                    )
                    TvReportLine(
                        stringResource(CommonR.string.diagnostics_report_uploadState),
                        diagnosticsUploadStatusLabel(report.uploadStatus),
                    )
                    report.uploadErrorCode?.let {
                        TvReportLine(stringResource(CommonR.string.diagnostics_report_lastError), it)
                    }
                }
                item {
                    TvDiagnosticsSection(stringResource(CommonR.string.diagnostics_report_archiveEntriesHeader)) {
                        report.archiveEntries.forEach { entry ->
                            Text(entry, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 3.dp))
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (uploading) {
                            Text(
                                stringResource(CommonR.string.diagnostics_report_sending),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        uploadNotice?.let { decision ->
                            Text(
                                diagnosticsUploadNotice(decision),
                                color = if (uploadNoticeIsError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvDiagnosticsAction(
                                label = stringResource(
                                    if (uploading) {
                                        CommonR.string.settings_diagnostics_sending
                                    } else {
                                        CommonR.string.diagnostics_consent_confirm
                                    },
                                ),
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
                                                uploadNotice = decision
                                            }
                                            else -> {
                                                uploadNoticeIsError = true
                                                uploadNotice = decision
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TvDiagnosticsAction(
                                label = stringResource(CommonR.string.diagnostics_erase_confirm),
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
            title = stringResource(CommonR.string.diagnostics_report_deleteTitle),
            message = stringResource(
                if (report.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                    CommonR.string.diagnostics_report_deleteHostedBody
                } else {
                    CommonR.string.diagnostics_report_deleteLocalBody
                },
            ),
            confirmLabel = stringResource(CommonR.string.diagnostics_erase_confirm),
            onConfirm = {
                confirmDelete = false
                viewModel.delete(report.id, onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun TvReportLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.weight(1.8f))
    }
}
