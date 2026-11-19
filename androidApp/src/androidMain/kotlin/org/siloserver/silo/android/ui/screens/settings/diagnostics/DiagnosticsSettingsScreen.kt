package org.siloserver.silo.android.ui.screens.settings.diagnostics

import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.settings.SILO_PRIVACY_POLICY_URL
import org.siloserver.silo.android.ui.screens.settings.SettingsDestructiveRow
import org.siloserver.silo.android.ui.screens.settings.SettingsIndexedDropdownRow
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
import org.siloserver.silo.common.diagnostics.diagnosticsAvailabilityTitle
import org.siloserver.silo.common.diagnostics.diagnosticsConsentTitle
import org.siloserver.silo.common.diagnostics.diagnosticsReportStateLabel
import org.siloserver.silo.common.diagnostics.diagnosticsReportTypeTitle
import org.siloserver.silo.common.diagnostics.diagnosticsSizeLabel
import org.siloserver.silo.common.R as CommonR

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
    val uriHandler = LocalUriHandler.current
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
    val destinationLabels = DiagnosticsDestinationKind.entries.associateWith { destinationLabel(it) }
    SettingsPageScaffold(title = stringResource(CommonR.string.settings_diagnostics), onBackClick = onBackClick) {
        item(key = "state") {
            SettingsSection(
                title = stringResource(CommonR.string.diagnostics_section_featureState),
                footer = availabilityDetail(state)?.let { stringResource(it) },
            ) {
                SettingsNavigationRow(
                    label = stringResource(CommonR.string.diagnostics_row_status),
                    value = stringResource(diagnosticsAvailabilityTitle(state.availability)),
                )
                SettingsNavigationRow(
                    label = stringResource(CommonR.string.diagnostics_row_destination),
                    value = destinationLabels.getValue(state.destinationKind),
                )
            }
        }
        item(key = "capture") {
            SettingsSection(
                title = stringResource(CommonR.string.diagnostics_section_capture),
                footer = if (hosted) {
                    pluralStringResource(
                        CommonR.plurals.diagnostics_capture_hostedFooter,
                        state.retentionDays,
                        state.retentionDays,
                    )
                } else {
                    stringResource(CommonR.string.diagnostics_capture_selfHostedFooter)
                },
            ) {
                SettingsIndexedDropdownRow(
                    label = stringResource(CommonR.string.diagnostics_row_sendReportsTo),
                    value = destinationLabels.getValue(state.destinationKind),
                    options = destinationLabels.values.toList(),
                    selectedIndex = DiagnosticsDestinationKind.entries.indexOf(state.destinationKind),
                    onIndexSelected = { onDestinationChanged(DiagnosticsDestinationKind.entries[it]) },
                )
                SettingsSwitchRow(
                    label = stringResource(CommonR.string.diagnostics_row_debugLogging),
                    checked = state.debugLogging,
                    enabled = state.consent != DiagnosticsConsentMode.NEVER,
                    onCheckedChange = onDebugLoggingChanged,
                )
                SettingsIndexedDropdownRow(
                    label = stringResource(CommonR.string.diagnostics_row_crashReports),
                    value = stringResource(diagnosticsConsentTitle(effectiveConsent)),
                    options = consentOptions.map { stringResource(diagnosticsConsentTitle(it)) },
                    selectedIndex = consentOptions.indexOf(effectiveConsent),
                    onIndexSelected = { index ->
                        val mode = consentOptions[index]
                        if (consentActionModel(state.consent, mode).requiresConfirmation) {
                            confirmAlways = true
                        } else {
                            onConsentChanged(mode)
                        }
                    },
                )
                // The policy behind the consent above, one tap away where the
                // choice is made (Settings → About links it too).
                SettingsNavigationRow(
                    label = stringResource(CommonR.string.settings_about_privacyPolicy),
                    onClick = { uriHandler.openUri(SILO_PRIVACY_POLICY_URL) },
                )
            }
        }
        item(key = "pending") {
            SettingsSection(title = stringResource(CommonR.string.diagnostics_section_pending, state.pending.size)) {
                if (state.pending.isEmpty()) {
                    SettingsNavigationRow(
                        label = stringResource(CommonR.string.diagnostics_pending_none),
                        labelColor = SiloSecondaryText,
                    )
                }
                state.pending.forEach { report ->
                    SettingsNavigationRow(
                        label = report.type.displayName(),
                        description = "${report.capturedAt} · ${diagnosticsSizeLabel(report.evidenceBytes)}",
                        onClick = { onReportSelected(report.id) },
                    )
                }
            }
        }
        item(key = "send") {
            SettingsSection(
                title = null,
                footer = stringResource(
                    when {
                        captureActive -> CommonR.string.diagnostics_send_captureRunningFooter
                        !state.debugLogging -> CommonR.string.diagnostics_send_debugOffFooter
                        else -> CommonR.string.diagnostics_send_oneTimeFooter
                    },
                ),
                footerColor = if (!captureActive && !state.debugLogging) SiloWarning else SiloSecondaryText,
            ) {
                if (captureActive) {
                    SettingsNavigationRow(
                        label = stringResource(CommonR.string.diagnostics_capture_stopReview),
                        onClick = onStopCapture,
                        showChevron = false,
                    )
                    SettingsDestructiveRow(
                        label = stringResource(CommonR.string.diagnostics_capture_cancel),
                        onClick = onCancelCapture,
                    )
                } else {
                    SettingsNavigationRow(
                        label = stringResource(CommonR.string.settings_diagnostics_send),
                        onClick = onSendNow,
                        enabled = model.canCapture,
                        showChevron = false,
                    )
                    SettingsNavigationRow(
                        label = stringResource(CommonR.string.diagnostics_capture_start),
                        onClick = onStartCapture,
                        enabled = model.canCapture,
                        showChevron = false,
                    )
                }
            }
        }
        item(key = "sent") {
            SettingsSection(
                title = stringResource(CommonR.string.diagnostics_section_sentHistory),
                footer = if (state.sentHistory.isEmpty()) {
                    null
                } else {
                    stringResource(CommonR.string.diagnostics_sentHistory_footer)
                },
            ) {
                if (state.sentHistory.isEmpty()) {
                    SettingsNavigationRow(
                        label = stringResource(CommonR.string.diagnostics_sentHistory_none),
                        labelColor = SiloSecondaryText,
                    )
                }
                state.sentHistory.forEach { sent ->
                    SettingsRow(
                        label = sent.shortId,
                        description = "${diagnosticsReportStateLabel(sent.state)} · " +
                            formatDiagnosticDate(sent.sentAtEpochMs),
                    ) {
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(sent.shortId)) },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(CommonR.string.diagnostics_copyReferenceId),
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
        SiloConfirmDialog(
            title = stringResource(CommonR.string.diagnostics_alwaysSend_title),
            body = stringResource(CommonR.string.diagnostics_alwaysSend_settingsBody),
            confirmLabel = stringResource(CommonR.string.diagnostics_alwaysSend_action),
            destructive = false,
            onConfirm = {
                confirmAlways = false
                onConsentChanged(DiagnosticsConsentMode.ALWAYS)
            },
            onDismiss = { confirmAlways = false },
        )
    }
}

@Composable
private fun destinationLabel(destination: DiagnosticsDestinationKind): String = stringResource(
    when (destination) {
        DiagnosticsDestinationKind.HOSTED -> CommonR.string.diagnostics_destination_hosted
        DiagnosticsDestinationKind.SELF_HOSTED -> CommonR.string.diagnostics_destination_myServer
    },
)

/**
 * The Feature State footer: what a degraded availability means for reports.
 * Like the Apple page, a working state needs no footer.
 */
private fun availabilityDetail(state: DiagnosticsUiState): Int? = when (state.availability) {
    DiagnosticsAvailabilityUi.AVAILABLE -> null
    DiagnosticsAvailabilityUi.DISABLED -> CommonR.string.diagnostics_availability_disabledDetail
    DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE -> CommonR.string.diagnostics_availability_storageUnavailableDetail
    DiagnosticsAvailabilityUi.OFFLINE -> CommonR.string.diagnostics_availability_offlineDetail
    DiagnosticsAvailabilityUi.INELIGIBLE -> CommonR.string.diagnostics_availability_ineligibleDetail
}

@Composable
private fun DiagnosticsUnavailableScreen(onBackClick: () -> Unit) {
    Scaffold(
        topBar = {
            SiloTopBar(
                title = stringResource(CommonR.string.settings_diagnostics),
                onBackClick = onBackClick,
                containerColor = SiloSettingsBackground,
                centerTitle = true,
            )
        },
        containerColor = SiloSettingsBackground,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.xxl)) {
            Text(
                stringResource(CommonR.string.diagnostics_unavailableForProfile),
                style = MaterialTheme.typography.titleMedium,
                color = SiloForeground,
            )
        }
    }
}

/** The Apple apps' report titles; Android's ANR and native crash fold into them. */
@Composable
internal fun DiagnosticsReportType.displayName(): String = stringResource(diagnosticsReportTypeTitle(this))

internal fun formatDiagnosticDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
