package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import java.text.DateFormat
import java.util.Date
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
import org.siloserver.silo.common.diagnostics.TimedCaptureStatus
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent

@Composable
fun TvDiagnosticsSettingsScreen(
    onBack: () -> Unit,
    onReportSelected: (String) -> Unit,
    viewModel: TvDiagnosticsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    BackHandler(onBack = onBack)
    if (!state.profileEligible) {
        TvDiagnosticsPage(title = "Diagnostics") {
            Text("Diagnostics aren't available for this profile.")
        }
        return
    }
    var confirmAlways by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val effectiveConsent = if (
        state.consent == DiagnosticsConsentMode.ALWAYS && !state.allowsAutomaticUpload
    ) {
        DiagnosticsConsentMode.ASK
    } else {
        state.consent
    }
    val crashFocusRequesters = remember {
        TvDiagnosticsCrashFocus.entries.associateWith { FocusRequester() }
    }
    var crashRowHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state.consent, state.allowsAutomaticUpload) {
        val target = initialTvDiagnosticsCrashFocus(state.consent, state.allowsAutomaticUpload)
        // Relocation, not acquisition: the page is already focusable, so a
        // miss just leaves focus wherever the route transition put it.
        // tvDiagnosticsCrashFocusRequestResult mapped a Result, so "did not
        // throw" counted as FOCUSED and the loop stopped on acceptance rather
        // than on arrival.
        requestFocusUntilObserved(
            maxAttempts = TvFrameRelocationMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = crashFocusRequesters.getValue(target)::requestFocus,
            isFocused = { crashRowHasFocus },
        )
    }
    val model = tvDiagnosticsScreenModel(state)
    fun Modifier.crashFocusControl(current: TvDiagnosticsCrashFocus): Modifier =
        focusRequester(crashFocusRequesters.getValue(current))
            .onFocusChanged { crashRowHasFocus = it.isFocused || crashRowHasFocus }
            .onPreviewKeyEvent { event ->
                val direction = when {
                    event.type != KeyEventType.KeyDown -> null
                    event.key == Key.DirectionUp -> TvDiagnosticsFocusDirection.Up
                    event.key == Key.DirectionDown -> TvDiagnosticsFocusDirection.Down
                    else -> null
                }
                val keyResult = direction?.let {
                    tvDiagnosticsCrashFocusKeyResult(
                        current = current,
                        direction = it,
                        debugLoggingEnabled = state.consent != DiagnosticsConsentMode.NEVER,
                        allowAlways = state.allowsAutomaticUpload,
                        isRepeat = event.nativeKeyEvent.repeatCount > 0,
                    )
                }
                if (keyResult == null || !keyResult.consume) {
                    false
                } else {
                    keyResult.target?.let { target ->
                        crashFocusRequesters.getValue(target).claimFocusOrReport(
                            target = "diagnostics_row",
                            action = "dpad_${'$'}{direction?.name?.lowercase()}",
                        )
                    }
                    true
                }
            }
    TvDiagnosticsPage(title = "Diagnostics") {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TvDiagnosticsSection("SEND REPORTS TO") {
                    TvDiagnosticsAction(
                        label = "Silo Diagnostics",
                        value = if (state.destinationKind == DiagnosticsDestinationKind.HOSTED) "Selected" else null,
                        onClick = { viewModel.setDestination(DiagnosticsDestinationKind.HOSTED) },
                    )
                    TvDiagnosticsAction(
                        label = "This Silo server",
                        value = if (state.destinationKind == DiagnosticsDestinationKind.SELF_HOSTED) "Selected" else null,
                        onClick = { viewModel.setDestination(DiagnosticsDestinationKind.SELF_HOSTED) },
                    )
                    Text(
                        if (state.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                            "Reports include the Silo app version and build, Android version, device model, " +
                                "crash details, and diagnostic logs you review. A pseudonymous installation " +
                                "credential is not linked to an account on your self-hosted server. Username, " +
                                "email, profile, server address, and playback session IDs are omitted. Reports " +
                                "are never sent automatically and may be retained for up to " +
                                "${state.retentionDays} days."
                        } else {
                            "Compatibility mode sends reports to your active server."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TvDiagnosticsAction(
                        label = "Privacy Policy",
                        onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
                    )
                }
            }
            item {
                TvDiagnosticsSection("STATUS") {
                    val status = when (state.availability) {
                        DiagnosticsAvailabilityUi.AVAILABLE -> "Available — reports can be sent to the selected destination."
                        DiagnosticsAvailabilityUi.DISABLED -> "Disabled — local review and deletion remain available."
                        DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE -> "Destination storage unavailable — reports stay local."
                        DiagnosticsAvailabilityUi.OFFLINE -> "Offline — connect to refresh availability."
                        DiagnosticsAvailabilityUi.INELIGIBLE -> "Unavailable for this profile."
                    }
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                TvDiagnosticsSection("CRASH REPORTS") {
                    DiagnosticsConsentMode.entries
                        .filter { it != DiagnosticsConsentMode.ALWAYS || state.allowsAutomaticUpload }
                        .forEach { mode ->
                        TvDiagnosticsAction(
                            label = when (mode) {
                                DiagnosticsConsentMode.ASK -> "Ask before sending"
                                DiagnosticsConsentMode.ALWAYS -> "Always send"
                                DiagnosticsConsentMode.NEVER -> "Never send"
                            },
                            value = if (effectiveConsent == mode) "Selected" else null,
                            onClick = {
                                if (tvDiagnosticsConsentAction(state.consent, mode).requiresConfirmation) {
                                    confirmAlways = true
                                } else {
                                    viewModel.setConsent(mode)
                                }
                            },
                            modifier = Modifier.crashFocusControl(
                                initialTvDiagnosticsCrashFocus(mode, state.allowsAutomaticUpload),
                            ),
                        )
                    }
                    TvDiagnosticsAction(
                        label = "Debug logging",
                        value = if (state.debugLogging) "On" else "Off",
                        enabled = state.consent != DiagnosticsConsentMode.NEVER,
                        onClick = { viewModel.setDebugLogging(!state.debugLogging) },
                        modifier = Modifier.crashFocusControl(TvDiagnosticsCrashFocus.DEBUG_LOGGING),
                    )
                }
            }
            item {
                TvDiagnosticsSection("CAPTURE") {
                    if (state.timedCapture.status == TimedCaptureStatus.ACTIVE) {
                        Text("Capture is running. Reproduce the issue, then stop to review.")
                        TvDiagnosticsAction("Stop & review", onClick = { viewModel.stopTimedCapture(onReportSelected) })
                        TvDiagnosticsAction("Cancel capture", onClick = viewModel::cancelTimedCapture)
                    } else {
                        TvDiagnosticsAction(
                            "Send diagnostics now",
                            enabled = model.canCapture,
                            onClick = { viewModel.captureNow(onReportSelected) },
                        )
                        TvDiagnosticsAction(
                            "Start diagnostic capture",
                            enabled = model.canCapture,
                            onClick = viewModel::startTimedCapture,
                        )
                    }
                }
            }
            if (model.showPending) {
                item {
                    TvDiagnosticsSection("PENDING REPORTS") {
                        state.pending.forEach { report ->
                            TvDiagnosticsAction(
                                label = report.type.tvDisplayName(),
                                value = "${report.capturedAt} · ${tvFormatBytes(report.evidenceBytes)}",
                                onClick = { onReportSelected(report.id) },
                            )
                        }
                    }
                }
            }
            if (state.sentHistory.isNotEmpty()) {
                item {
                    TvDiagnosticsSection("RECENTLY SENT") {
                        state.sentHistory.forEach { sent ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(sent.shortId, modifier = Modifier.weight(1f))
                                Text(
                                    "${sent.state.replace('_', ' ')} · ${tvFormatDate(sent.sentAtEpochMs)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "Sent reports are removed from this device once the selected destination has a copy. " +
                                "Use the reference ID when asking for help.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
    if (confirmAlways && state.allowsAutomaticUpload) {
        TvDiagnosticsConfirmation(
            title = "Always send crash reports?",
            message = "Future eligible reports may upload automatically until you change this setting.",
            confirmLabel = "Always send",
            onConfirm = {
                confirmAlways = false
                viewModel.setConsent(DiagnosticsConsentMode.ALWAYS)
            },
            onDismiss = { confirmAlways = false },
        )
    }
}

private const val PRIVACY_POLICY_URL = "https://siloserver.org/privacy"

internal enum class TvDiagnosticsCrashFocus { ASK, ALWAYS, NEVER, DEBUG_LOGGING }

internal enum class TvDiagnosticsFocusDirection { Up, Down }

internal data class TvDiagnosticsCrashFocusKeyResult(
    val target: TvDiagnosticsCrashFocus?,
    val consume: Boolean,
)

internal enum class TvDiagnosticsCrashFocusRequestResult { FOCUSED, RETRY }

internal fun tvDiagnosticsCrashFocusRequestResult(
    result: Result<Boolean>,
): TvDiagnosticsCrashFocusRequestResult = if (result.getOrDefault(false)) {
    TvDiagnosticsCrashFocusRequestResult.FOCUSED
} else {
    TvDiagnosticsCrashFocusRequestResult.RETRY
}

internal fun initialTvDiagnosticsCrashFocus(
    mode: DiagnosticsConsentMode,
    allowAlways: Boolean = true,
) = when (mode) {
    DiagnosticsConsentMode.ASK -> TvDiagnosticsCrashFocus.ASK
    DiagnosticsConsentMode.ALWAYS -> if (allowAlways) TvDiagnosticsCrashFocus.ALWAYS else TvDiagnosticsCrashFocus.ASK
    DiagnosticsConsentMode.NEVER -> TvDiagnosticsCrashFocus.NEVER
}

internal fun tvDiagnosticsCrashFocusOrder(
    debugLoggingEnabled: Boolean,
    allowAlways: Boolean = true,
) = buildList {
    add(TvDiagnosticsCrashFocus.ASK)
    if (allowAlways) add(TvDiagnosticsCrashFocus.ALWAYS)
    add(TvDiagnosticsCrashFocus.NEVER)
    if (debugLoggingEnabled) add(TvDiagnosticsCrashFocus.DEBUG_LOGGING)
}

internal fun nextTvDiagnosticsCrashFocus(
    current: TvDiagnosticsCrashFocus,
    direction: TvDiagnosticsFocusDirection,
    debugLoggingEnabled: Boolean,
    allowAlways: Boolean = true,
): TvDiagnosticsCrashFocus? {
    val order = tvDiagnosticsCrashFocusOrder(debugLoggingEnabled, allowAlways)
    // A control outside the current order (Debug logging under consent NEVER)
    // has no neighbour to move to. Coercing a -1 miss to 0 would silently treat
    // it as the FIRST row and send Down upwards, so hand the key back instead.
    val index = order.indexOf(current)
    if (index < 0) return null
    return when (direction) {
        TvDiagnosticsFocusDirection.Up -> order[(index - 1).coerceAtLeast(0)]
        TvDiagnosticsFocusDirection.Down -> order.getOrNull(index + 1)
    }
}

internal fun tvDiagnosticsCrashFocusKeyResult(
    current: TvDiagnosticsCrashFocus,
    direction: TvDiagnosticsFocusDirection,
    debugLoggingEnabled: Boolean,
    isRepeat: Boolean,
    allowAlways: Boolean = true,
): TvDiagnosticsCrashFocusKeyResult {
    if (isRepeat) return TvDiagnosticsCrashFocusKeyResult(target = null, consume = true)
    val target = nextTvDiagnosticsCrashFocus(current, direction, debugLoggingEnabled, allowAlways)
    return TvDiagnosticsCrashFocusKeyResult(target = target, consume = target != null)
}

@Composable
internal fun TvDiagnosticsPage(title: String, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF17181A))
                .padding(horizontal = 64.dp, vertical = 38.dp),
        ) {
            Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(22.dp))
            Column(Modifier.widthIn(max = 760.dp), content = { content() })
        }
    }
}

@Composable
internal fun TvDiagnosticsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvDiagnosticsAction(
    label: String,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        modifier = modifier.fillMaxWidth().height(52.dp),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), color = if (focused) FocusedContent else Color.White)
            value?.let {
                Text(it, color = if (focused) FocusedContent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun org.siloserver.silo.model.diagnostics.DiagnosticsReportType.tvDisplayName(): String =
    name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

internal fun tvFormatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

internal fun tvFormatDate(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
