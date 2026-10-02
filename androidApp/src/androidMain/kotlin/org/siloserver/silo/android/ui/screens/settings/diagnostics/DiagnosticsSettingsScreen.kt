package org.siloserver.silo.android.ui.screens.settings.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.settings.SettingsDestructiveRow
import org.siloserver.silo.android.ui.screens.settings.SettingsDropdownRow
import org.siloserver.silo.android.ui.screens.settings.SettingsNavigationRow
import org.siloserver.silo.android.ui.screens.settings.SettingsPageScaffold
import org.siloserver.silo.android.ui.screens.settings.SettingsRow
import org.siloserver.silo.android.ui.screens.settings.SettingsSection
import org.siloserver.silo.android.ui.screens.settings.SettingsSwitchRow
import org.siloserver.silo.android.ui.theme.SiloForeground
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSettingsBackground
import org.siloserver.silo.android.ui.theme.SiloWarning
import org.siloserver.silo.android.ui.theme.Spacing
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
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

/**
 * Diagnostics, in the Apple page's order (silo-apple `DiagnosticsSettingsView`):
 * Feature State, Capture, Pending Reports, the send action, and Sent History.
 * Android's timed capture sits beside "Send Diagnostics Now"; it has no Apple
 * twin.
 */
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
    val clipboard = LocalClipboardManager.current
    val model = diagnosticsPhoneScreenModel(state)
    val hosted = state.destinationKind == DiagnosticsDestinationKind.HOSTED
    val effectiveConsent = if (
        state.consent == DiagnosticsConsentMode.ALWAYS && !state.allowsAutomaticUpload
    ) {
        DiagnosticsConsentMode.ASK
    } else {
        state.consent
    }
    val consentOptions = DiagnosticsConsentMode.entries
        .filter { it != DiagnosticsConsentMode.ALWAYS || state.allowsAutomaticUpload }
    val captureActive = state.timedCapture.status == TimedCaptureStatus.ACTIVE
    SettingsPageScaffold(title = "Diagnostics", onBackClick = onBackClick) {
        item(key = "state") {
            SettingsSection(title = "Feature State", footer = availabilityDetail(state)) {
                SettingsNavigationRow(label = "Status", value = availabilityTitle(state.availability))
                SettingsNavigationRow(label = "Destination", value = destinationLabel(state.destinationKind))
            }
        }
        item(key = "capture") {
            SettingsSection(
                title = "Capture",
                footer = if (hosted) {
                    "Reports include the Silo app version and build, Android version, device model, " +
                        "crash details, and diagnostic logs you review. A pseudonymous installation " +
                        "credential is not linked to an account on your self-hosted server. Username, " +
                        "email, profile, server address, and playback session IDs are omitted. Reports " +
                        "are never sent automatically and may be retained for up to " +
                        "${state.retentionDays} days."
                } else {
                    "Crash report consent is tied to this server account. Debug logging is a setting " +
                        "for this device."
                },
            ) {
                SettingsDropdownRow(
                    label = "Send Reports To",
                    value = destinationLabel(state.destinationKind),
                    options = DiagnosticsDestinationKind.entries.map(::destinationLabel),
                    onOptionSelected = { label ->
                        DiagnosticsDestinationKind.entries
                            .firstOrNull { destinationLabel(it) == label }
                            ?.let(onDestinationChanged)
                    },
                )
                SettingsSwitchRow(
                    label = "Debug Logging",
                    checked = state.debugLogging,
                    enabled = state.consent != DiagnosticsConsentMode.NEVER,
                    onCheckedChange = onDebugLoggingChanged,
                )
                SettingsDropdownRow(
                    label = "Crash Reports",
                    value = consentLabel(effectiveConsent),
                    options = consentOptions.map(::consentLabel),
                    onOptionSelected = { label ->
                        consentOptions.firstOrNull { consentLabel(it) == label }?.let { mode ->
                            if (consentActionModel(state.consent, mode).requiresConfirmation) {
                                confirmAlways = true
                            } else {
                                onConsentChanged(mode)
                            }
                        }
                    },
                )
            }
        }
        item(key = "pending") {
            SettingsSection(title = "Pending Reports (${state.pending.size})") {
                if (state.pending.isEmpty()) {
                    SettingsNavigationRow(label = "No pending reports", labelColor = SiloSecondaryText)
                }
                state.pending.forEach { report ->
                    SettingsNavigationRow(
                        label = report.type.displayName(),
                        description = "${report.capturedAt} · ${formatDiagnosticBytes(report.evidenceBytes)}",
                        onClick = { onReportSelected(report.id) },
                    )
                }
            }
        }
        item(key = "send") {
            SettingsSection(
                title = null,
                footer = when {
                    captureActive -> "Capture is running. Reproduce the issue, then stop to review exactly " +
                        "what will be sent."
                    !state.debugLogging -> "Debug logging is off. This report contains only the last few " +
                        "minutes of basic logs. Timed capture records more detail until you stop it."
                    else -> "A one-time report uses the recent in-memory log. Timed capture records more " +
                        "detail until you stop it."
                },
                footerColor = if (!captureActive && !state.debugLogging) SiloWarning else SiloSecondaryText,
            ) {
                if (captureActive) {
                    SettingsNavigationRow(label = "Stop & Review", onClick = onStopCapture, showChevron = false)
                    SettingsDestructiveRow(label = "Cancel Capture", onClick = onCancelCapture)
                } else {
                    SettingsNavigationRow(
                        label = "Send Diagnostics Now",
                        onClick = onSendNow,
                        enabled = model.canCapture,
                        showChevron = false,
                    )
                    SettingsNavigationRow(
                        label = "Start Diagnostic Capture",
                        onClick = onStartCapture,
                        enabled = model.canCapture,
                        showChevron = false,
                    )
                }
            }
        }
        item(key = "sent") {
            SettingsSection(
                title = "Sent History",
                footer = if (state.sentHistory.isEmpty()) {
                    null
                } else {
                    "Sent reports are removed from this device once the selected destination has a copy. " +
                        "Use the reference ID when asking for help."
                },
            ) {
                if (state.sentHistory.isEmpty()) {
                    SettingsNavigationRow(label = "No reports sent yet", labelColor = SiloSecondaryText)
                }
                state.sentHistory.forEach { sent ->
                    SettingsRow(
                        label = sent.shortId,
                        description = "${sent.state.replace('_', ' ')} · " +
                            formatDiagnosticDate(sent.sentAtEpochMs),
                    ) {
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(sent.shortId)) },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy reference ID",
                                tint = SiloSecondaryText,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
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

private fun destinationLabel(destination: DiagnosticsDestinationKind): String = when (destination) {
    DiagnosticsDestinationKind.HOSTED -> "Silo Diagnostics"
    DiagnosticsDestinationKind.SELF_HOSTED -> "My Silo Server"
}

private fun consentLabel(mode: DiagnosticsConsentMode): String = when (mode) {
    DiagnosticsConsentMode.ASK -> "Ask"
    DiagnosticsConsentMode.ALWAYS -> "Always"
    DiagnosticsConsentMode.NEVER -> "Never"
}

private fun availabilityTitle(availability: DiagnosticsAvailabilityUi): String = when (availability) {
    DiagnosticsAvailabilityUi.AVAILABLE -> "Available"
    DiagnosticsAvailabilityUi.DISABLED -> "Disabled by server"
    DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE -> "Storage unavailable"
    DiagnosticsAvailabilityUi.OFFLINE -> "Offline"
    DiagnosticsAvailabilityUi.INELIGIBLE -> "Unavailable"
}

/**
 * The Feature State footer: what a degraded availability means for reports.
 * Like the Apple page, a working state needs no footer.
 */
private fun availabilityDetail(state: DiagnosticsUiState): String? = when (state.availability) {
    DiagnosticsAvailabilityUi.AVAILABLE -> null
    DiagnosticsAvailabilityUi.DISABLED -> "Local reports remain available to inspect or delete."
    DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE -> "Local reports remain on this device."
    DiagnosticsAvailabilityUi.OFFLINE ->
        "Showing the last known diagnostics state. Reports stay on this device while the server is offline."
    DiagnosticsAvailabilityUi.INELIGIBLE -> "Diagnostics are not available for this profile."
}

@Composable
private fun DiagnosticsUnavailableScreen(onBackClick: () -> Unit) {
    Scaffold(
        topBar = {
            SiloTopBar(
                title = "Diagnostics",
                onBackClick = onBackClick,
                containerColor = SiloSettingsBackground,
                centerTitle = true,
            )
        },
        containerColor = SiloSettingsBackground,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.xxl)) {
            Text(
                "Diagnostics aren't available for this profile.",
                style = MaterialTheme.typography.titleMedium,
                color = SiloForeground,
            )
        }
    }
}

/** The Apple apps' report titles; Android's ANR and native crash fold into them. */
internal fun DiagnosticsReportType.displayName(): String = when (this) {
    DiagnosticsReportType.CRASH, DiagnosticsReportType.NATIVE_CRASH -> "Crash"
    DiagnosticsReportType.HANG, DiagnosticsReportType.ANR -> "Not Responding"
    DiagnosticsReportType.ABNORMAL_EXIT -> "Unclean Shutdown"
    DiagnosticsReportType.MANUAL -> "Manual Report"
}

internal fun formatDiagnosticBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

internal fun formatDiagnosticDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
