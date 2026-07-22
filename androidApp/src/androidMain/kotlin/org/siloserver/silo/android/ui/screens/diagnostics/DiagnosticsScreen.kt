package org.siloserver.silo.android.ui.screens.diagnostics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.settings.SettingsBadgeRed
import org.siloserver.silo.android.ui.screens.settings.SettingsClickableRow
import org.siloserver.silo.android.ui.screens.settings.SettingsRow
import org.siloserver.silo.android.ui.screens.settings.SettingsSectionCard
import org.siloserver.silo.android.ui.screens.settings.SettingsSectionHeader
import org.siloserver.silo.android.ui.screens.settings.SettingsSwitchRow
import org.siloserver.silo.android.ui.util.formatBytes
import org.siloserver.silo.common.diagnostics.DiagnosticsViewModel
import org.siloserver.silo.common.diagnostics.consent.ConsentChoice
import org.siloserver.silo.common.diagnostics.consent.PendingReportStore
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailability
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Diagnostics. Mirrors the Apple client's structure: feature
 * state, capture settings, manual capture flow, pending reports, and sent
 * history — all backed by the shared [DiagnosticsViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBackClick: () -> Unit,
    viewModel: DiagnosticsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val manualReview by viewModel.manualReview.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val busy by viewModel.busy.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    var consentMenuExpanded by remember { mutableStateOf(false) }
    var confirmAlways by remember { mutableStateOf(false) }
    var confirmNever by remember { mutableStateOf(false) }
    // Track the id, not the report object, so the dialog re-reads fresh
    // state (e.g. a permanent-failure mark after a bounced upload).
    var detailReportId by remember { mutableStateOf<String?>(null) }

    val canSend = state.availability == DiagnosticsAvailability.AVAILABLE && state.binding != null

    Scaffold(
        topBar = {
            SiloTopBar(title = "Diagnostics", onBackClick = onBackClick)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (!state.canManage || state.binding == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            ) {
                Text(
                    text = "Diagnostics is available when a non-kids profile is signed in.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // --- Feature State ---
                item {
                    Column {
                        SettingsSectionCard {
                            SettingsSectionHeader(title = "Feature State")
                            SettingsRow(label = "Status") {
                                Text(
                                    text = availabilityLabel(state.availability),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SettingsRow(label = "Destination") {
                                Text(
                                    text = state.serverName ?: "—",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (state.availability == DiagnosticsAvailability.OFFLINE) {
                            DiagnosticsFootnote("Showing the last known diagnostics state.")
                        }
                    }
                }

                // --- Capture ---
                item {
                    Column {
                        SettingsSectionCard {
                            SettingsSectionHeader(title = "Capture")
                            SettingsSwitchRow(
                                label = "Debug Logging",
                                checked = state.debugLogging,
                                onCheckedChange = viewModel::setDebugLogging,
                            )
                            Box {
                                SettingsRow(
                                    label = "Crash Reports",
                                    modifier = Modifier.clickable { consentMenuExpanded = true },
                                ) {
                                    Text(
                                        text = consentChoiceLabel(state.consentChoice),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DropdownMenu(
                                    expanded = consentMenuExpanded,
                                    onDismissRequest = { consentMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Ask") },
                                        onClick = {
                                            consentMenuExpanded = false
                                            viewModel.setConsentChoice(ConsentChoice.ASK)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Always") },
                                        onClick = {
                                            consentMenuExpanded = false
                                            confirmAlways = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Never") },
                                        onClick = {
                                            consentMenuExpanded = false
                                            confirmNever = true
                                        },
                                    )
                                }
                            }
                        }
                        DiagnosticsFootnote(
                            "Crash report consent is tied to this server account. " +
                                "Debug logging is a setting for this device.",
                        )
                    }
                }

                // --- Diagnostic Capture (manual flow) ---
                item {
                    Column {
                        SettingsSectionCard {
                            SettingsSectionHeader(title = "Diagnostic Capture")
                            if (!state.captureActive) {
                                SettingsClickableRow(
                                    icon = Icons.Outlined.PlayArrow,
                                    label = "Start Diagnostic Capture",
                                    onClick = viewModel::startCapture,
                                    enabled = !busy,
                                )
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.FiberManualRecord,
                                        contentDescription = null,
                                        tint = SettingsBadgeRed,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Capture running — reproduce the issue, then stop",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                SettingsClickableRow(
                                    icon = Icons.Outlined.Stop,
                                    label = "Stop & Review",
                                    onClick = viewModel::stopCaptureAndReview,
                                    enabled = !busy,
                                )
                            }
                            SettingsClickableRow(
                                icon = Icons.AutoMirrored.Outlined.Send,
                                label = "Send Diagnostics Now",
                                onClick = viewModel::buildSendNowReview,
                                enabled = canSend && !busy && !state.isUploading,
                            )
                        }
                        if (!state.debugLogging) {
                            DiagnosticsFootnote(
                                "This report contains only the last few minutes of basic logs.",
                            )
                        }
                    }
                }

                // --- Pending Reports ---
                if (state.pendingReports.isNotEmpty()) {
                    item {
                        SettingsSectionCard {
                            SettingsSectionHeader(
                                title = "Pending Reports (${state.pendingReports.size})",
                            )
                            state.pendingReports.forEach { report ->
                                PendingReportRow(
                                    report = report,
                                    onClick = { detailReportId = report.id },
                                )
                            }
                        }
                    }
                }

                // --- Sent History ---
                if (state.sentHistory.isNotEmpty()) {
                    item {
                        SettingsSectionCard {
                            SettingsSectionHeader(title = "Sent History")
                            state.sentHistory.forEach { sent ->
                                SettingsRow(label = sent.shortId) {
                                    Text(
                                        text = formatDiagnosticsDate(sent.sentAtEpochMs),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }

    // --- Consent confirmations ---
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
                        viewModel.setConsentChoice(ConsentChoice.ALWAYS)
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
    if (confirmNever) {
        AlertDialog(
            onDismissRequest = { confirmNever = false },
            title = { Text("Turn Off Crash Reports?") },
            text = {
                Text(
                    "Pending reports for this server account will be deleted. " +
                        "The in-memory basic log will continue running and is never " +
                        "sent without an explicit action.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmNever = false
                        viewModel.setConsentChoice(ConsentChoice.NEVER)
                    },
                ) {
                    Text("Turn Off and Delete", color = SettingsBadgeRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmNever = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // --- Pending report detail ---
    val detailReport = state.pendingReports.firstOrNull { it.id == detailReportId }
    if (detailReport != null) {
        AlertDialog(
            onDismissRequest = { detailReportId = null },
            title = { Text(reportTypeLabel(detailReport.binding.type)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Captured: " +
                            formatDiagnosticsDate(detailReport.binding.capturedAtEpochMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    detailReport.manifestDraft.crash?.summary?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    when {
                        detailReport.state.needsServerUpdate -> Text(
                            text = "Your server needs an update to accept this report.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SettingsBadgeRed,
                        )
                        detailReport.state.tooLarge -> Text(
                            text = "This report is larger than the server accepts.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SettingsBadgeRed,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canSend && !detailReport.state.isPermanentFailure && !busy,
                    onClick = {
                        detailReportId = null
                        viewModel.upload(detailReport)
                    },
                ) {
                    Text("Send")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            detailReportId = null
                            viewModel.deletePending(detailReport)
                        },
                    ) {
                        Text("Delete", color = SettingsBadgeRed)
                    }
                    TextButton(onClick = { detailReportId = null }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }

    // --- Manual review step ---
    manualReview?.let { review ->
        AlertDialog(
            // Outside-tap = the user chose not to send; the built report is
            // removed, matching the explicit Cancel button.
            onDismissRequest = viewModel::cancelManualReview,
            title = { Text("Review Diagnostics Report") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Log lines: ${review.lineCount}")
                    Text("Categories: ${review.categories.joinToString(", ").ifEmpty { "—" }}")
                    Text("Approx. size: ${formatBytes(review.approxLogBytes)}")
                    Text("Destination: ${state.serverName ?: "—"}")
                    if (review.ringOnly) {
                        Text(
                            text = "Debug logging was off — this report contains only " +
                                "the last few minutes of basic logs.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = viewModel::sendManualReview,
                ) {
                    Text("Send")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelManualReview) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** Two-line pending-report row: type title, captured date + relative expiry. */
@Composable
private fun PendingReportRow(
    report: PendingReportStore.PendingReport,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            text = reportTypeLabel(report.binding.type),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = formatDiagnosticsDate(report.binding.capturedAtEpochMs) +
                " · " + formatExpiresRelative(report.expiresAtEpochMs()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Footnote text below a section card, matching iOS grouped-list footers. */
@Composable
private fun DiagnosticsFootnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
    )
}

private fun availabilityLabel(availability: DiagnosticsAvailability): String = when (availability) {
    DiagnosticsAvailability.UNKNOWN -> "Checking availability…"
    DiagnosticsAvailability.AVAILABLE -> "Available"
    DiagnosticsAvailability.DISABLED -> "Disabled by server"
    DiagnosticsAvailability.STORAGE_UNAVAILABLE -> "Storage unavailable"
    DiagnosticsAvailability.OFFLINE -> "Offline"
    DiagnosticsAvailability.UNAVAILABLE -> "Unavailable"
}

private fun consentChoiceLabel(choice: ConsentChoice): String = when (choice) {
    ConsentChoice.ASK -> "Ask"
    ConsentChoice.ALWAYS -> "Always"
    ConsentChoice.NEVER -> "Never"
}

internal fun reportTypeLabel(type: DiagnosticsReportType): String = when (type) {
    DiagnosticsReportType.CRASH -> "Crash"
    DiagnosticsReportType.ANR -> "App Not Responding"
    DiagnosticsReportType.NATIVE_CRASH -> "Native Crash"
    DiagnosticsReportType.HANG -> "Hang"
    DiagnosticsReportType.ABNORMAL_EXIT -> "Abnormal Exit"
    DiagnosticsReportType.MANUAL -> "Manual Capture"
}

internal fun formatDiagnosticsDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

private fun formatExpiresRelative(expiresAtEpochMs: Long): String {
    val remainingMs = expiresAtEpochMs - System.currentTimeMillis()
    val hourMs = 60L * 60 * 1000
    val dayMs = 24 * hourMs
    return when {
        remainingMs <= 0 -> "expired"
        remainingMs < hourMs -> "expires soon"
        remainingMs < dayMs -> {
            val hours = (remainingMs / hourMs).toInt()
            if (hours == 1) "expires in 1 hour" else "expires in $hours hours"
        }
        else -> {
            val days = (remainingMs / dayMs).toInt()
            if (days == 1) "expires in 1 day" else "expires in $days days"
        }
    }
}
