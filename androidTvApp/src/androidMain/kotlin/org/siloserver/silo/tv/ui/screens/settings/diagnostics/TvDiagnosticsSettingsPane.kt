package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
import org.siloserver.silo.common.diagnostics.DiagnosticsUiState
import org.siloserver.silo.common.diagnostics.TimedCaptureStatus
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.tvControlSemantics
import org.siloserver.silo.tv.ui.screens.settings.PickerOption
import org.siloserver.silo.tv.ui.screens.settings.SettingsActionRow
import org.siloserver.silo.tv.ui.screens.settings.SettingsFooterText
import org.siloserver.silo.tv.ui.screens.settings.SettingsGroup
import org.siloserver.silo.tv.ui.screens.settings.SettingsInfoRow
import org.siloserver.silo.tv.ui.screens.settings.SettingsToggleRow
import org.siloserver.silo.tv.ui.screens.settings.SettingsValueRow
import org.siloserver.silo.tv.ui.screens.settings.TvSettingsConfirmDialog
import org.siloserver.silo.tv.ui.screens.settings.TvSettingsPickerSheet
import org.siloserver.silo.tv.ui.theme.Spacing

/**
 * Diagnostics as a Settings category, rendered inline in the detail pane —
 * modeled on `iosApp/.../tvOS/Screens/Settings/TVDiagnosticsSettingsPane.swift`.
 *
 * Two things this replaces are worth remembering.
 *
 * It used to be a top-level route outside `TvMainShell`, so the surface had no
 * top bar, no on-screen Back, and no relationship to the rest of Settings.
 *
 * The destination choice ("Silo Diagnostics" vs "This Silo server") used to be
 * a pair of inline rows above the consent ladder, and a hand-rolled
 * `onPreviewKeyEvent` ladder swallowed Up at the top of that ladder — the rows
 * rendered but no D-pad press could ever reach them. tvOS does not have that
 * problem because both choices live behind a picker row; so do they now, which
 * deletes the ladder rather than repairing it.
 *
 * Section order follows tvOS with one deliberate swap: SENT HISTORY sits before
 * MANUAL REPORT. Read-only rows are outside the focus graph, and Compose's
 * bring-into-view only scrolls far enough to reveal the *focused* node — so
 * anything below the last focusable row can never be scrolled to. Sandwiching
 * the read-only log between focusable sections keeps it reachable.
 */
@Composable
internal fun TvDiagnosticsSettingsPane(
    state: DiagnosticsUiState,
    serverName: String,
    firstFocusRequester: FocusRequester,
    onSetDestination: (DiagnosticsDestinationKind) -> Unit,
    onSetConsent: (DiagnosticsConsentMode) -> Unit,
    onSetDebugLogging: (Boolean) -> Unit,
    onCaptureNow: () -> Unit,
    onStartTimedCapture: () -> Unit,
    onStopTimedCapture: () -> Unit,
    onCancelTimedCapture: () -> Unit,
    onReportSelected: (String) -> Unit,
) {
    // The crash prompt used to be suppressed by route (`TvRoute.Diagnostics`).
    // Now that this is a pane inside Main, presence is the signal — otherwise
    // the prompt reopens on top of the very screen the viewer opened to read
    // about it.
    DisposableEffect(Unit) {
        TvDiagnosticsSurfacePresence.enter()
        onDispose { TvDiagnosticsSurfacePresence.leave() }
    }

    var activePicker by remember { mutableStateOf<TvDiagnosticsPicker?>(null) }
    var confirmAlways by remember { mutableStateOf(false) }
    val model = tvDiagnosticsScreenModel(state)
    val effectiveConsent = tvDiagnosticsEffectiveConsent(state.consent, state.allowsAutomaticUpload)
    val debugLoggingApplies = state.consent != DiagnosticsConsentMode.NEVER
    val capturing = state.timedCapture.status == TimedCaptureStatus.ACTIVE
    val now = remember(state.pending) { System.currentTimeMillis() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = Spacing.xxxl),
    ) {
        item {
            SettingsGroup(title = "Feature State") {
                SettingsInfoRow(
                    label = "Status",
                    value = tvDiagnosticsStatusTitle(state.availability),
                )
                SettingsInfoRow(
                    label = "Destination",
                    value = tvDiagnosticsDestinationName(state.destinationKind, serverName),
                )
            }
        }
        item {
            SettingsGroup(title = "Capture") {
                // The first focusable row in the pane, so entering the category
                // from the rail lands here (tvOS `.focused(detailFocus, .top)`).
                SettingsValueRow(
                    label = "Send Reports To",
                    value = tvDiagnosticsDestinationTitle(state.destinationKind),
                    onClick = { activePicker = TvDiagnosticsPicker.Destination },
                    focusRequester = firstFocusRequester,
                )
                // Under consent NEVER nothing is logged, so the toggle cannot
                // apply: structural, i.e. out of the focus graph rather than a
                // dead D-pad stop (see TvControlEnablement).
                SettingsToggleRow(
                    label = "Debug Logging",
                    checked = state.debugLogging,
                    onCheckedChange = onSetDebugLogging,
                    enabled = debugLoggingApplies,
                    modifier = Modifier
                        .tvControlSemantics(TvControlState.structural(debugLoggingApplies))
                        .dimWhenDisabled(debugLoggingApplies),
                )
                SettingsValueRow(
                    label = "Crash Reports",
                    value = tvDiagnosticsConsentTitle(effectiveConsent),
                    onClick = { activePicker = TvDiagnosticsPicker.Consent },
                )
                SettingsFooterText(
                    text = if (state.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                        "Reports include the Silo app version and build, Android version, device model, " +
                            "crash details, and diagnostic logs you review. A pseudonymous installation " +
                            "credential is not linked to an account on your self-hosted server. Username, " +
                            "email, profile, server address, and playback session IDs are omitted. Reports " +
                            "are never sent automatically and may be retained for up to " +
                            "${state.retentionDays} days. Full policy: $PRIVACY_POLICY_URL"
                    } else {
                        "Crash report consent is tied to this server account. Debug logging is a " +
                            "setting for this Android TV."
                    },
                )
            }
        }
        item {
            SettingsGroup(title = tvDiagnosticsPendingHeader(state.pending.size)) {
                if (state.pending.isEmpty()) {
                    // tvOS shows the empty state outright. Omitting the section
                    // left a clean device with no sign it had been checked.
                    SettingsInfoRow(label = "Reports", value = "None")
                } else {
                    state.pending.forEach { report ->
                        SettingsValueRow(
                            label = tvDiagnosticsReportTypeTitle(report.type),
                            value = tvFormatShortDateTime(report.capturedAtEpochMs) +
                                " · " + tvDiagnosticsExpiryLabel(report.expiresAtEpochMs, now),
                            onClick = { onReportSelected(report.id) },
                        )
                    }
                }
            }
        }
        item {
            SettingsGroup(title = "Sent History") {
                if (state.sentHistory.isEmpty()) {
                    SettingsInfoRow(label = "Reports", value = "None")
                } else {
                    state.sentHistory.take(TvDiagnosticsSentHistoryLimit).forEach { sent ->
                        SettingsInfoRow(
                            label = sent.shortId,
                            value = tvFormatShortDateTime(sent.sentAtEpochMs),
                        )
                    }
                    SettingsFooterText(
                        text = "Sent reports are removed from this device once the destination has a " +
                            "copy. Use the reference ID when asking for help.",
                    )
                }
            }
        }
        item {
            SettingsGroup(title = "Manual Report") {
                // Availability can come back (the server reconnects), so these
                // stay in the focus graph — transient, not structural. They
                // only have to *look* dead, which they previously did not.
                SettingsActionRow(
                    label = "Send Diagnostics Now",
                    onClick = onCaptureNow,
                    enabled = model.canCapture && !capturing,
                    modifier = Modifier.dimWhenDisabled(model.canCapture && !capturing),
                )
                if (!state.debugLogging) {
                    SettingsFooterText(
                        text = "Debug logging is off. This report contains only the last few minutes " +
                            "of basic logs.",
                    )
                }
            }
        }
        // Android-only: tvOS has no timed capture. It stays last so the pane
        // always ends on a focusable row.
        item {
            SettingsGroup(title = "Timed Capture") {
                if (capturing) {
                    SettingsFooterText(
                        text = "Capture is running. Reproduce the issue, then stop to review.",
                    )
                    SettingsActionRow(label = "Stop & Review", onClick = onStopTimedCapture)
                    SettingsActionRow(
                        label = "Cancel Capture",
                        onClick = onCancelTimedCapture,
                        destructive = true,
                    )
                } else {
                    SettingsFooterText(
                        text = "Records logs until you stop it, then opens the report for review.",
                    )
                    SettingsActionRow(
                        label = "Start Diagnostic Capture",
                        onClick = onStartTimedCapture,
                        enabled = model.canCapture,
                        modifier = Modifier.dimWhenDisabled(model.canCapture),
                    )
                }
            }
        }
    }

    when (activePicker) {
        TvDiagnosticsPicker.Destination -> TvSettingsPickerSheet(
            title = "Send Reports To",
            options = TvDiagnosticsDestinations.map {
                PickerOption(it.name, tvDiagnosticsDestinationTitle(it))
            },
            selectedId = state.destinationKind.name,
            onSelect = { id ->
                activePicker = null
                TvDiagnosticsDestinations.firstOrNull { it.name == id }?.let(onSetDestination)
            },
            onDismiss = { activePicker = null },
        )
        TvDiagnosticsPicker.Consent -> TvSettingsPickerSheet(
            title = "Crash Reports",
            options = tvDiagnosticsConsentOptions(state.allowsAutomaticUpload).map {
                PickerOption(it.name, tvDiagnosticsConsentTitle(it))
            },
            selectedId = effectiveConsent.name,
            onSelect = { id ->
                activePicker = null
                val requested = DiagnosticsConsentMode.entries.firstOrNull { it.name == id }
                    ?: return@TvSettingsPickerSheet
                if (tvDiagnosticsConsentAction(state.consent, requested).requiresConfirmation) {
                    confirmAlways = true
                } else {
                    onSetConsent(requested)
                }
            },
            onDismiss = { activePicker = null },
        )
        null -> Unit
    }

    if (confirmAlways && state.allowsAutomaticUpload) {
        TvSettingsConfirmDialog(
            title = "Always send crash reports?",
            message = "Future eligible reports may upload automatically until you change this setting.",
            confirmLabel = "Always Send",
            onConfirm = {
                confirmAlways = false
                onSetConsent(DiagnosticsConsentMode.ALWAYS)
            },
            onDismiss = { confirmAlways = false },
        )
    }
}

private enum class TvDiagnosticsPicker { Destination, Consent }

/**
 * A TV `Surface` paints its content at full strength whether or not it is
 * enabled, so a row that refuses to act still reads as live. Say it in the
 * paint as well as in the semantics.
 */
private fun Modifier.dimWhenDisabled(enabled: Boolean): Modifier =
    if (enabled) this else alpha(0.45f)

/**
 * Whether a diagnostics settings surface is on screen.
 *
 * The crash prompt is a global overlay hosted by the NavHost; it used to check
 * the current route, which stopped working the moment diagnostics became a pane
 * inside `TvRoute.Main`. A counter rather than a flag, so an overlapping
 * enter/leave during a transition cannot latch it false.
 */
internal object TvDiagnosticsSurfacePresence {
    private var count by mutableIntStateOf(0)

    val isVisible: Boolean get() = count > 0

    fun enter() {
        count += 1
    }

    fun leave() {
        count = (count - 1).coerceAtLeast(0)
    }
}
