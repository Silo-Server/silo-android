package org.siloserver.silo.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.diagnostics.DiagnosticsViewModel
import org.siloserver.silo.common.diagnostics.consent.ConsentChoice
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailability
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Diagnostics detail pane (mirrors the Apple tvOS diagnostics pane).
 *
 * Self-contained on purpose: the pane resolves the shared [DiagnosticsViewModel]
 * itself instead of threading state/callbacks through `SettingsSplitLayout`'s
 * already-huge parameter list, so diagnostics state changes recompose only
 * this pane.
 */
@Composable
internal fun TvDiagnosticsPane(
    firstFocusRequester: FocusRequester,
) {
    val viewModel: DiagnosticsViewModel = koinViewModel()
    val state by viewModel.state.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val manualReview by viewModel.manualReview.collectAsState()
    val busy by viewModel.busy.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Child profiles can't manage diagnostics: one explanatory line, no controls.
    if (!state.canManage && state.binding != null) {
        Text(
            text = "Crash reports and debug logs are managed by grown-up profiles. " +
                "Switch to a non-child profile on this server account to change diagnostics.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(max = DiagRowMaxWidth)
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        return
    }

    val sendEnabled = state.availability == DiagnosticsAvailability.AVAILABLE && state.binding != null
    val sendActionsEnabled = sendEnabled && !busy && !state.isUploading

    var showConsentPicker by remember { mutableStateOf(false) }
    var pendingConsent by remember { mutableStateOf<ConsentChoice?>(null) }
    var selectedReportId by remember { mutableStateOf<String?>(null) }

    // Transient notice row: auto-consume after a few seconds so the flow can't
    // hold a stale message the next time the pane opens.
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6_000)
            viewModel.consumeNotice()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = Spacing.xxxl),
    ) {
        notice?.let { text ->
            item(key = "notice") {
                DiagnosticsNoticeRow(text)
            }
        }
        item(key = "feature") {
            SettingsGroup(title = "Feature") {
                SettingsInfoRow(label = "Status", value = availabilityLabel(state.availability))
                SettingsInfoRow(label = "Destination", value = state.serverName ?: "—")
                if (state.availability == DiagnosticsAvailability.OFFLINE) {
                    SettingsFooterText(
                        text = "Your server can't be reached right now. Reports stay on this " +
                            "Android TV until the connection returns.",
                    )
                }
            }
        }
        item(key = "capture") {
            SettingsGroup(title = "Capture") {
                SettingsToggleRow(
                    label = "Debug Logging",
                    checked = state.debugLogging,
                    onCheckedChange = viewModel::setDebugLogging,
                    focusRequester = firstFocusRequester,
                )
                SettingsValueRow(
                    label = "Crash Reports",
                    value = consentLabel(state.consentChoice),
                    onClick = { showConsentPicker = true },
                )
            }
        }
        item(key = "diagnostic_capture") {
            SettingsGroup(title = "Diagnostic Capture") {
                if (state.captureActive) {
                    // Stopping must always be possible — the review's Send is
                    // where availability gating applies (offline uploads come
                    // back as a Kept notice).
                    SettingsActionRow(
                        label = "Stop & Review",
                        onClick = viewModel::stopCaptureAndReview,
                        enabled = !busy,
                    )
                    SettingsFooterText(
                        text = "Capture running… reproduce the problem, then stop to review before sending.",
                    )
                } else {
                    SettingsActionRow(
                        label = "Start Diagnostic Capture",
                        onClick = viewModel::startCapture,
                        enabled = sendEnabled,
                    )
                }
                SettingsActionRow(
                    label = "Send Diagnostics Now",
                    onClick = viewModel::buildSendNowReview,
                    enabled = sendActionsEnabled,
                )
                if (!state.debugLogging) {
                    SettingsFooterText(text = "Contains only the last few minutes of basic logs")
                }
            }
        }
        if (state.pendingReports.isNotEmpty()) {
            item(key = "pending") {
                SettingsGroup(title = "Pending Reports") {
                    state.pendingReports.forEach { report ->
                        SettingsActionRow(
                            label = "${reportTypeTitle(report.binding.type)} — " +
                                shortDate(report.binding.capturedAtEpochMs),
                            onClick = { selectedReportId = report.id },
                        )
                    }
                }
            }
        }
        if (state.sentHistory.isNotEmpty()) {
            item(key = "sent") {
                SettingsGroup(title = "Sent History") {
                    state.sentHistory.forEach { sent ->
                        SettingsInfoRow(
                            label = sent.shortId,
                            value = shortDate(sent.sentAtEpochMs),
                        )
                    }
                }
            }
        }
    }

    if (showConsentPicker) {
        TvSettingsPickerSheet(
            title = "Crash Reports",
            options = listOf(
                PickerOption(ConsentChoice.ASK.name, "Ask"),
                PickerOption(ConsentChoice.ALWAYS.name, "Always"),
                PickerOption(ConsentChoice.NEVER.name, "Never"),
            ),
            selectedId = state.consentChoice.name,
            onSelect = { id ->
                showConsentPicker = false
                val choice = ConsentChoice.entries.firstOrNull { it.name == id }
                when {
                    choice == null || choice == state.consentChoice -> Unit
                    choice == ConsentChoice.ASK -> viewModel.setConsentChoice(ConsentChoice.ASK)
                    // Always/Never are consequential — confirm before applying.
                    else -> pendingConsent = choice
                }
            },
            onDismiss = { showConsentPicker = false },
        )
    }

    when (pendingConsent) {
        ConsentChoice.ALWAYS -> TvSettingsConfirmDialog(
            title = "Always Send Crash Reports?",
            message = "Any pending reports and future crash or hang reports for this server " +
                "account will be sent automatically.",
            confirmLabel = "Always Send",
            onConfirm = {
                pendingConsent = null
                viewModel.setConsentChoice(ConsentChoice.ALWAYS)
            },
            onDismiss = { pendingConsent = null },
        )
        ConsentChoice.NEVER -> TvSettingsConfirmDialog(
            title = "Turn Off Crash Reports?",
            message = "Pending reports for this server account will be deleted. The in-memory " +
                "basic log will continue running and is never sent without an explicit action.",
            confirmLabel = "Turn Off and Delete",
            onConfirm = {
                pendingConsent = null
                viewModel.setConsentChoice(ConsentChoice.NEVER)
            },
            onDismiss = { pendingConsent = null },
        )
        else -> Unit
    }

    manualReview?.let { review ->
        TvDiagnosticsDialog(
            title = "Send Diagnostics?",
            lines = buildList {
                add("Log lines: ${review.lineCount}")
                if (review.categories.isNotEmpty()) {
                    add("Categories: ${review.categories.joinToString(", ")}")
                }
                add("Approx. size: ${approxSizeLabel(review.approxLogBytes)}")
                add("Destination: ${state.serverName ?: "your server"}")
                if (review.ringOnly) {
                    add(
                        "Debug logging was off, so this report contains only the last few " +
                            "minutes of basic logs.",
                    )
                }
            },
            actions = listOf(
                TvDialogAction(label = "Send", onClick = viewModel::sendManualReview),
                TvDialogAction(label = "Cancel", onClick = viewModel::cancelManualReview),
            ),
            defaultFocusIndex = 0,
            onDismiss = viewModel::cancelManualReview,
        )
    }

    val selectedReport = state.pendingReports.firstOrNull { it.id == selectedReportId }
    if (selectedReport != null) {
        val permanent = selectedReport.state.isPermanentFailure
        val canSend = !permanent && sendActionsEnabled
        TvDiagnosticsDialog(
            title = reportTypeTitle(selectedReport.binding.type),
            lines = buildList {
                selectedReport.manifestDraft.crash?.summary
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add(it) }
                add(
                    "Captured ${shortDate(selectedReport.binding.capturedAtEpochMs)} · " +
                        "expires ${shortDate(selectedReport.expiresAtEpochMs())}",
                )
                if (selectedReport.state.needsServerUpdate) {
                    add("Your server needs an update to accept this report.")
                }
                if (selectedReport.state.tooLarge) {
                    add("This report is larger than the server accepts.")
                }
            },
            actions = listOf(
                TvDialogAction(
                    label = "Send",
                    enabled = canSend,
                    onClick = {
                        selectedReportId = null
                        viewModel.upload(selectedReport)
                    },
                ),
                TvDialogAction(
                    label = "Delete",
                    destructive = true,
                    onClick = {
                        selectedReportId = null
                        viewModel.deletePending(selectedReport)
                    },
                ),
                TvDialogAction(label = "Cancel", onClick = { selectedReportId = null }),
            ),
            defaultFocusIndex = if (canSend) 0 else 2,
            onDismiss = { selectedReportId = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Local building blocks
// ---------------------------------------------------------------------------

/** Non-focusable transient outcome line (uploaded short id, kept reason…). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiagnosticsNoticeRow(text: String) {
    Box(
        modifier = Modifier
            .widthIn(max = DiagRowMaxWidth)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = Color.White.copy(alpha = 0.92f),
        )
    }
}

internal data class TvDialogAction(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * TvSettingsConfirmDialog-style dialog with multiple body lines and an
 * arbitrary action row (Send/Delete/Cancel…). Focus lands on
 * [defaultFocusIndex]; Back dismisses.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvDiagnosticsDialog(
    title: String,
    lines: List<String>,
    actions: List<TvDialogAction>,
    defaultFocusIndex: Int,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val defaultFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { defaultFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.86f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(400.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEachIndexed { index, action ->
                        TvDiagnosticsDialogButton(
                            label = action.label,
                            onClick = action.onClick,
                            destructive = action.destructive,
                            enabled = action.enabled,
                            focusRequester = if (index == defaultFocusIndex) defaultFocus else null,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvDiagnosticsDialogButton(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = if (destructive) MaterialTheme.colorScheme.error else Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
                shape = shape,
            ),
            focusedBorder = androidx.tv.material3.Border.None,
            pressedBorder = androidx.tv.material3.Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
            color = when {
                !enabled -> Color.White.copy(alpha = 0.35f)
                isFocused -> FocusedContent
                destructive -> MaterialTheme.colorScheme.error
                else -> Color.White
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Labels / formatting
// ---------------------------------------------------------------------------

private val DiagRowMaxWidth = 520.dp

internal fun availabilityLabel(availability: DiagnosticsAvailability): String = when (availability) {
    DiagnosticsAvailability.UNKNOWN -> "Checking availability…"
    DiagnosticsAvailability.AVAILABLE -> "Available"
    DiagnosticsAvailability.DISABLED -> "Disabled by server"
    DiagnosticsAvailability.STORAGE_UNAVAILABLE -> "Storage unavailable"
    DiagnosticsAvailability.OFFLINE -> "Offline"
    DiagnosticsAvailability.UNAVAILABLE -> "Unavailable"
}

private fun consentLabel(choice: ConsentChoice): String = when (choice) {
    ConsentChoice.ASK -> "Ask"
    ConsentChoice.ALWAYS -> "Always"
    ConsentChoice.NEVER -> "Never"
}

internal fun reportTypeTitle(type: DiagnosticsReportType): String = when (type) {
    DiagnosticsReportType.CRASH -> "Crash"
    DiagnosticsReportType.ANR -> "App Not Responding"
    DiagnosticsReportType.NATIVE_CRASH -> "Native Crash"
    DiagnosticsReportType.HANG -> "Hang"
    DiagnosticsReportType.ABNORMAL_EXIT -> "Abnormal Exit"
    DiagnosticsReportType.MANUAL -> "Manual Capture"
}

internal fun shortDate(epochMs: Long): String =
    SimpleDateFormat("MMM d", Locale.US).format(Date(epochMs))

private fun approxSizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
