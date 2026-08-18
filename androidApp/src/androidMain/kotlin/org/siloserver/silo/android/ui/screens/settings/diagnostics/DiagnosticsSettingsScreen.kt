package org.siloserver.silo.android.ui.screens.settings.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.settings.SettingsChoiceRow
import org.siloserver.silo.android.ui.screens.settings.SettingsNavigationRow
import org.siloserver.silo.android.ui.screens.settings.SettingsProse
import org.siloserver.silo.android.ui.screens.settings.SettingsRow
import org.siloserver.silo.android.ui.screens.settings.SettingsSection
import org.siloserver.silo.android.ui.screens.settings.SettingsSectionCard
import org.siloserver.silo.android.ui.screens.settings.SettingsSwitchRow
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.android.ui.theme.SettingsTextStyles
import org.siloserver.silo.android.ui.theme.SiloForeground
import org.siloserver.silo.android.ui.theme.SiloMutedText
import org.siloserver.silo.android.ui.theme.SiloSettingsBackground
import org.siloserver.silo.android.ui.theme.Spacing
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
        topBar = {
            SiloTopBar(
                title = "Diagnostics",
                onBackClick = onBackClick,
                containerColor = SiloSettingsBackground,
            )
        },
        containerColor = SiloSettingsBackground,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(SettingsDimens.pageGutter),
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.sectionGap),
        ) {
            item {
                SettingsSection(title = "Send reports to") {
                    DiagnosticsDestinationKind.entries.forEach { destination ->
                        val label = when (destination) {
                            DiagnosticsDestinationKind.HOSTED -> "Silo Diagnostics"
                            DiagnosticsDestinationKind.SELF_HOSTED -> "This Silo server"
                        }
                        SettingsChoiceRow(
                            label = label,
                            selected = state.destinationKind == destination,
                            onSelect = { onDestinationChanged(destination) },
                        )
                    }
                    SettingsProse(
                        body = if (state.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                            "Reports include the Silo app version and build, Android version, device model, " +
                                "crash details, and diagnostic logs you review. A pseudonymous installation " +
                                "credential is not linked to an account on your self-hosted server. Username, " +
                                "email, profile, server address, and playback session IDs are omitted. Reports " +
                                "are never sent automatically and may be retained for up to " +
                                "${state.retentionDays} days."
                        } else {
                            "Compatibility mode sends reports to the diagnostics endpoint on your active server."
                        },
                    )
                    TextButton(
                        onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
                        modifier = Modifier.padding(
                            start = SettingsDimens.rowHorizontalPadding - 12.dp,
                            bottom = SettingsDimens.rowVerticalPadding,
                        ),
                    ) {
                        Text("Privacy Policy")
                    }
                }
            }
            item { DiagnosticsStatusCard(state) }
            item {
                SettingsSection(title = "Crash reports") {
                    DiagnosticsConsentMode.entries
                        .filter { it != DiagnosticsConsentMode.ALWAYS || state.allowsAutomaticUpload }
                        .forEach { mode ->
                            val label = when (mode) {
                                DiagnosticsConsentMode.ASK -> "Ask before sending"
                                DiagnosticsConsentMode.ALWAYS -> "Always send"
                                DiagnosticsConsentMode.NEVER -> "Never send"
                            }
                            SettingsChoiceRow(
                                label = label,
                                selected = effectiveConsent == mode,
                                onSelect = {
                                    if (consentActionModel(state.consent, mode).requiresConfirmation) {
                                        confirmAlways = true
                                    } else {
                                        onConsentChanged(mode)
                                    }
                                },
                            )
                        }
                    // The shared switch row now carries `enabled`, so this no
                    // longer needs its own hand-rolled copy of it.
                    SettingsSwitchRow(
                        label = "Debug logging",
                        description = "Record extra detail so a report can explain what went wrong.",
                        checked = state.debugLogging,
                        enabled = state.consent != DiagnosticsConsentMode.NEVER,
                        onCheckedChange = onDebugLoggingChanged,
                    )
                }
            }
            item {
                SettingsSection(title = "Capture") {
                    val paneModifier = Modifier.padding(
                        horizontal = SettingsDimens.proseHorizontalPadding,
                        vertical = SettingsDimens.proseVerticalPadding,
                    )
                    if (state.timedCapture.status == TimedCaptureStatus.ACTIVE) {
                        Column(paneModifier) {
                            Text(
                                "Diagnostic capture is running",
                                style = SettingsTextStyles.rowLabel,
                                color = SiloForeground,
                            )
                            Text(
                                "Reproduce the issue, then stop to review exactly what will be sent.",
                                color = SiloMutedText,
                                style = SettingsTextStyles.rowDescription,
                            )
                            Spacer(Modifier.height(Spacing.md))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Button(onClick = onStopCapture) { Text("Stop & review") }
                                OutlinedButton(onClick = onCancelCapture) { Text("Cancel") }
                            }
                        }
                    } else {
                        Column(paneModifier) {
                            Button(onClick = onSendNow, enabled = model.canCapture) {
                                Text("Send diagnostics now")
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            OutlinedButton(onClick = onStartCapture, enabled = model.canCapture) {
                                Text("Start diagnostic capture")
                            }
                            Text(
                                "A one-time report uses the recent in-memory log. Timed capture records more detail until you stop it.",
                                color = SiloMutedText,
                                style = SettingsTextStyles.rowDescription,
                                modifier = Modifier.padding(top = Spacing.sm),
                            )
                        }
                    }
                }
            }
            if (model.showPending) {
                item {
                    SettingsSection(title = "Pending reports") {
                        state.pending.forEach { report ->
                            SettingsNavigationRow(
                                label = report.type.displayName(),
                                description = "${report.capturedAt} · ${formatDiagnosticBytes(report.evidenceBytes)}",
                                onClick = { onReportSelected(report.id) },
                            )
                        }
                    }
                }
            }
            if (state.sentHistory.isNotEmpty()) {
                item {
                    val clipboard = LocalClipboardManager.current
                    Column {
                        SettingsSection(title = "Recently sent") {
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
                                            tint = SiloMutedText,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "Sent reports are removed from this device once the selected destination has a copy. " +
                                "Use the reference ID when asking for help.",
                            color = SiloMutedText,
                            style = SettingsTextStyles.rowDescription,
                            modifier = Modifier.padding(
                                start = SettingsDimens.headerStartInset,
                                end = SettingsDimens.headerStartInset,
                                top = Spacing.sm,
                            ),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(SettingsDimens.pageBottomSpacer)) }
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
        topBar = {
            SiloTopBar(
                title = "Diagnostics",
                onBackClick = onBackClick,
                containerColor = SiloSettingsBackground,
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
        SettingsProse(title = title, body = detail)
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
