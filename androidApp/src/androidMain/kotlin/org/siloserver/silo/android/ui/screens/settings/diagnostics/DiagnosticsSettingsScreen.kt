package org.siloserver.silo.android.ui.screens.settings.diagnostics

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.settings.SettingsRow
import org.siloserver.silo.android.ui.screens.settings.SettingsSectionCard
import org.siloserver.silo.android.ui.screens.settings.SettingsSectionHeader
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
import org.siloserver.silo.common.diagnostics.DiagnosticsUiState
import org.siloserver.silo.common.diagnostics.TimedCaptureStatus

@Composable
fun DiagnosticsSettingsScreen(
    onBackClick: () -> Unit,
    onReportSelected: (String) -> Unit,
    viewModel: DiagnosticsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    if (!state.profileEligible) {
        DiagnosticsUnavailableScreen(onBackClick)
        return
    }
    DiagnosticsSettingsContent(
        state = state,
        onBackClick = onBackClick,
        onConsentChanged = viewModel::setConsent,
        onDestinationChanged = viewModel::setDestination,
        onDebugLoggingChanged = viewModel::setDebugLogging,
        onSendNow = { viewModel.captureNow(onReportSelected) },
        onStartCapture = viewModel::startTimedCapture,
        onStopCapture = { viewModel.stopTimedCapture(onReportSelected) },
        onCancelCapture = viewModel::cancelTimedCapture,
        onReportSelected = onReportSelected,
    )
}

@Composable
internal fun DiagnosticsSettingsContent(
    state: DiagnosticsUiState,
    onBackClick: () -> Unit,
    onConsentChanged: (DiagnosticsConsentMode) -> Unit,
    onDestinationChanged: (DiagnosticsDestinationKind) -> Unit,
    onDebugLoggingChanged: (Boolean) -> Unit,
    onSendNow: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onCancelCapture: () -> Unit,
    onReportSelected: (String) -> Unit,
) {
    var confirmAlways by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val model = diagnosticsPhoneScreenModel(state)
    val effectiveConsent = if (
        state.consent == DiagnosticsConsentMode.ALWAYS && !state.allowsAutomaticUpload
    ) {
        DiagnosticsConsentMode.ASK
    } else {
        state.consent
    }
    Scaffold(
        topBar = { SiloTopBar(title = "Diagnostics", onBackClick = onBackClick) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                SettingsSectionCard {
                    SettingsSectionHeader("Send reports to")
                    DiagnosticsDestinationKind.entries.forEach { destination ->
                        val label = when (destination) {
                            DiagnosticsDestinationKind.HOSTED -> "Silo Diagnostics"
                            DiagnosticsDestinationKind.SELF_HOSTED -> "This Silo server"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDestinationChanged(destination) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.destinationKind == destination, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text(
                        if (state.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                            "Reports include the Silo app version and build, Android version, device model, " +
                                "crash details, and diagnostic logs you review. A pseudonymous installation " +
                                "credential is not linked to an account on your self-hosted server. Username, " +
                                "email, profile, server address, and playback session IDs are omitted. Reports " +
                                "are never sent automatically and may be retained for up to " +
                                "${state.retentionDays} days."
                        } else {
                            "Compatibility mode sends reports to the diagnostics endpoint on your active server."
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) {
                        Text("Privacy Policy")
                    }
                }
            }
            item { DiagnosticsStatusCard(state) }
            item {
                SettingsSectionCard {
                    SettingsSectionHeader("Crash reports")
                    DiagnosticsConsentMode.entries
                        .filter { it != DiagnosticsConsentMode.ALWAYS || state.allowsAutomaticUpload }
                        .forEach { mode ->
                        val label = when (mode) {
                            DiagnosticsConsentMode.ASK -> "Ask before sending"
                            DiagnosticsConsentMode.ALWAYS -> "Always send"
                            DiagnosticsConsentMode.NEVER -> "Never send"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (consentActionModel(state.consent, mode).requiresConfirmation) {
                                        confirmAlways = true
                                    } else {
                                        onConsentChanged(mode)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = effectiveConsent == mode,
                                onClick = null,
                            )
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    SettingsRow(
                        label = "Debug logging",
                        trailing = {
                            Switch(
                                checked = state.debugLogging,
                                enabled = state.consent != DiagnosticsConsentMode.NEVER,
                                onCheckedChange = onDebugLoggingChanged,
                            )
                        },
                    )
                }
            }
            item {
                SettingsSectionCard {
                    SettingsSectionHeader("Capture")
                    if (state.timedCapture.status == TimedCaptureStatus.ACTIVE) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("Diagnostic capture is running", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Reproduce the issue, then stop to review exactly what will be sent.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = onStopCapture) { Text("Stop & review") }
                                OutlinedButton(onClick = onCancelCapture) { Text("Cancel") }
                            }
                        }
                    } else {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Button(onClick = onSendNow, enabled = model.canCapture) {
                                Text("Send diagnostics now")
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onStartCapture, enabled = model.canCapture) {
                                Text("Start diagnostic capture")
                            }
                            Text(
                                "A one-time report uses the recent in-memory log. Timed capture records more detail until you stop it.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            }
            if (model.showPending) {
                item {
                    SettingsSectionCard {
                        SettingsSectionHeader("Pending reports")
                        state.pending.forEach { report ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onReportSelected(report.id) }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                            ) {
                                Text(report.type.displayName(), fontWeight = FontWeight.Medium)
                                Text(
                                    "${report.capturedAt} · ${formatDiagnosticBytes(report.evidenceBytes)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            if (state.sentHistory.isNotEmpty()) {
                item {
                    val clipboard = LocalClipboardManager.current
                    Column {
                        SettingsSectionCard {
                            SettingsSectionHeader("Recently sent")
                            state.sentHistory.forEach { sent ->
                                SettingsRow(label = sent.shortId) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${sent.state.replace('_', ' ')} · ${formatDiagnosticDate(sent.sentAtEpochMs)}",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        IconButton(
                                            onClick = { clipboard.setText(AnnotatedString(sent.shortId)) },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.ContentCopy,
                                                contentDescription = "Copy reference ID",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            "Sent reports are removed from this device once the selected destination has a copy. " +
                                "Use the reference ID when asking for help.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmAlways && state.allowsAutomaticUpload) {
        AlertDialog(
            onDismissRequest = { confirmAlways = false },
            title = { Text("Always send crash reports?") },
            text = {
                Text("Future eligible crash reports may be uploaded automatically. You can inspect pending reports and change this at any time.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAlways = false
                    onConsentChanged(DiagnosticsConsentMode.ALWAYS)
                }) { Text("Always send") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAlways = false }) { Text("Cancel") }
            },
        )
    }
}

private const val PRIVACY_POLICY_URL = "https://siloserver.org/privacy"

@Composable
private fun DiagnosticsUnavailableScreen(onBackClick: () -> Unit) {
    Scaffold(
        topBar = { SiloTopBar(title = "Diagnostics", onBackClick = onBackClick) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("Diagnostics aren't available for this profile.", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun DiagnosticsStatusCard(state: DiagnosticsUiState) {
    val destination = if (state.destinationKind == DiagnosticsDestinationKind.HOSTED) "Silo Diagnostics" else "this server"
    val (title, detail) = when (state.availability) {
        DiagnosticsAvailabilityUi.AVAILABLE -> "Available" to "Reports can be reviewed and sent to $destination."
        DiagnosticsAvailabilityUi.DISABLED -> "Disabled" to "Local reports remain available to inspect or delete."
        DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE -> "Storage unavailable" to "Local reports remain on this device."
        DiagnosticsAvailabilityUi.OFFLINE -> "Offline" to "Connect to refresh diagnostics availability."
        DiagnosticsAvailabilityUi.INELIGIBLE -> "Unavailable" to "Diagnostics are not available for this profile."
    }
    SettingsSectionCard {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun org.siloserver.silo.model.diagnostics.DiagnosticsReportType.displayName(): String =
    name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

internal fun formatDiagnosticBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

internal fun formatDiagnosticDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
