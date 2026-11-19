package org.siloserver.silo.android.ui.screens.settings.diagnostics

import org.siloserver.silo.android.ui.components.SiloConfirmDialog
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.settings.SettingsSection
import org.siloserver.silo.android.ui.screens.settings.SettingsSectionCard
import org.siloserver.silo.android.ui.theme.SiloSettingsBackground
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
import org.siloserver.silo.common.diagnostics.DiagnosticsUploadDecision
import org.siloserver.silo.common.diagnostics.diagnosticsReportStateLabel
import org.siloserver.silo.common.diagnostics.diagnosticsSizeLabel
import org.siloserver.silo.common.diagnostics.diagnosticsUploadNotice
import org.siloserver.silo.common.diagnostics.diagnosticsUploadStatusLabel
import org.siloserver.silo.common.R as CommonR

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
    var uploadNotice by remember { mutableStateOf<DiagnosticsUploadDecision?>(null) }
    var uploadNoticeIsError by remember { mutableStateOf(true) }
    Scaffold(
        topBar = {
            SiloTopBar(
                title = stringResource(CommonR.string.diagnostics_report_title),
                onBackClick = onBackClick,
                containerColor = SiloSettingsBackground,
            )
        },
        containerColor = SiloSettingsBackground,
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
                        Text(stringResource(CommonR.string.diagnostics_report_sending))
                    }
                } else {
                    Text(stringResource(CommonR.string.diagnostics_report_gone))
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
                            DetailLine(
                                stringResource(CommonR.string.diagnostics_report_evidence),
                                diagnosticsSizeLabel(report.evidenceBytes),
                            )
                            DetailLine(
                                stringResource(CommonR.string.diagnostics_row_destination),
                                if (report.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                                    stringResource(CommonR.string.diagnostics_destination_hosted)
                                } else {
                                    report.destinationServerInstanceId
                                },
                            )
                            if (report.destinationKind == DiagnosticsDestinationKind.SELF_HOSTED) {
                                DetailLine(
                                    stringResource(CommonR.string.diagnostics_report_capturedProfile),
                                    report.capturedProfileId
                                        ?: stringResource(CommonR.string.diagnostics_report_accountScoped),
                                )
                            }
                            DetailLine(
                                stringResource(CommonR.string.diagnostics_report_expires),
                                formatDiagnosticDate(report.expiresAtEpochMs),
                            )
                            DetailLine(
                                stringResource(CommonR.string.diagnostics_report_uploadState),
                                diagnosticsUploadStatusLabel(report.uploadStatus),
                            )
                            report.uploadErrorCode?.let {
                                DetailLine(stringResource(CommonR.string.diagnostics_report_lastError), it)
                            }
                        }
                    }
                }
                item {
                    SettingsSection(title = stringResource(CommonR.string.diagnostics_report_archiveEntries)) {
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
                                    stringResource(CommonR.string.diagnostics_report_sending),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                            ) {
                                Text(
                                    stringResource(
                                        if (uploading) {
                                            CommonR.string.settings_diagnostics_sending
                                        } else {
                                            CommonR.string.diagnostics_consent_confirm
                                        },
                                    ),
                                )
                            }
                            OutlinedButton(
                                enabled = !uploading,
                                onClick = { confirmDelete = true },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(CommonR.string.diagnostics_erase_confirm)) }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && report != null) {
        SiloConfirmDialog(
            title = stringResource(CommonR.string.diagnostics_report_deleteTitle),
            body = stringResource(
                if (report.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                    CommonR.string.diagnostics_report_deleteHostedBody
                } else {
                    CommonR.string.diagnostics_report_deleteLocalBody
                },
            ),
            confirmLabel = stringResource(CommonR.string.diagnostics_erase_confirm),
            onConfirm = {
                confirmDelete = false
                viewModel.delete(report.id, onBackClick)
            },
            onDismiss = { confirmDelete = false },
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
                Text(stringResource(CommonR.string.diagnostics_report_sent), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(CommonR.string.diagnostics_report_referenceId), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Text(shortId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(shortId)) }) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(CommonR.string.diagnostics_copyReferenceId),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                DetailLine(
                    stringResource(CommonR.string.diagnostics_report_processingState),
                    diagnosticsReportStateLabel(state),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(CommonR.string.diagnostics_report_sentBody),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(CommonR.string.common_done)) }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.weight(1.4f))
    }
}
