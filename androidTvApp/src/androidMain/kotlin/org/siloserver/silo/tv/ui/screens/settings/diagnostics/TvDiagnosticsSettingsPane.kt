package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
import org.siloserver.silo.common.diagnostics.DiagnosticsUiState
import org.siloserver.silo.common.diagnostics.TimedCaptureStatus
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.tvControlSemantics
import org.siloserver.silo.tv.ui.screens.auth.QrCodePanel
import org.siloserver.silo.tv.ui.screens.settings.PickerOption
import org.siloserver.silo.tv.ui.screens.settings.SettingsActionRow
import org.siloserver.silo.tv.ui.screens.settings.SettingsFooterText
import org.siloserver.silo.tv.ui.screens.settings.SettingsGroup
import org.siloserver.silo.tv.ui.screens.settings.SettingsGroupRowSpacing
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
 * ## Section order, and why read-only rows still stay out of the focus graph
 *
 * Read-only rows are deliberately not focus stops (tvOS behaviour): the D-pad
 * only lands on controls that act. The cost is that Compose's bring-into-view
 * scrolls just far enough to reveal the *focused* node, so read-only content is
 * only ever seen as a side effect of scrolling to some control. Two defects on
 * a Shield came straight out of that (2026-08-15):
 *
 *  - FEATURE STATE scrolled off the top and could never be recovered — the
 *    first focus stop sat below it, so coming back up stopped as soon as that
 *    row was visible.
 *  - One Down press jumped ~320dp from CAPTURE's last row to MANUAL REPORT,
 *    because the privacy footer, the empty PENDING REPORTS row and the whole
 *    SENT HISTORY block lay between them with no focus stop in the middle.
 *
 * The order below fixes both by grouping sections by kind rather than
 * interleaving them: **read-only status first, every control next, read-only
 * log last.**
 *
 *  1. FEATURE STATE   — read-only
 *  2. PENDING REPORTS — read-only when empty, focus stops when populated
 *  3. CAPTURE         — focus stops (+ privacy footer)
 *  4. MANUAL REPORT   — focus stop (+ footer)
 *  5. TIMED CAPTURE   — focus stop (+ footer); Android-only, no tvOS twin
 *  6. SENT HISTORY    — read-only
 *
 * That makes the three control sections contiguous, so the only read-only run
 * left between two focus stops is CAPTURE's own privacy footer. It also
 * replaces the previous deliberate deviation (SENT HISTORY hoisted above MANUAL
 * REPORT so trailing content could not be stranded) — [tvRevealsListContext]
 * now carries that guarantee instead, which frees SENT HISTORY to sit where
 * tvOS puts it, after the controls.
 *
 * Nothing here re-introduces a key ladder: every scroll is a `bringIntoView`
 * request on a row that already has focus.
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
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    val model = tvDiagnosticsScreenModel(state)
    val effectiveConsent = tvDiagnosticsEffectiveConsent(state.consent, state.allowsAutomaticUpload)
    val debugLoggingApplies = state.consent != DiagnosticsConsentMode.NEVER
    val capturing = state.timedCapture.status == TimedCaptureStatus.ACTIVE
    val now = remember(state.pending) { System.currentTimeMillis() }

    // Every reveal below is bounded by the list's own viewport, so a request can
    // never be taller than the container — a rect that straddles both edges is
    // one Compose declines to scroll at all.
    var viewportPx by remember { mutableIntStateOf(0) }
    // Measured rather than assumed: footer height depends on how the prose wraps
    // at the current width and font scale, and both vary by device.
    var privacyFooterPx by remember { mutableIntStateOf(0) }
    var manualFooterPx by remember { mutableIntStateOf(0) }
    val groupRowGapPx = with(LocalDensity.current) { SettingsGroupRowSpacing.roundToPx() }
    val pendingOwnsFirstFocus = tvDiagnosticsPendingOwnsFirstFocus(state.pending.size)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportPx = it.height },
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
            SettingsGroup(title = tvDiagnosticsPendingHeader(state.pending.size)) {
                if (state.pending.isEmpty()) {
                    // tvOS shows the empty state outright. Omitting the section
                    // left a clean device with no sign it had been checked.
                    SettingsInfoRow(label = "Reports", value = "None")
                } else {
                    state.pending.forEachIndexed { index, report ->
                        SettingsValueRow(
                            label = tvDiagnosticsReportTypeTitle(report.type),
                            value = tvFormatShortDateTime(report.capturedAtEpochMs) +
                                " · " + tvDiagnosticsExpiryLabel(report.expiresAtEpochMs, now),
                            onClick = { onReportSelected(report.id) },
                            // A waiting report is the most actionable thing in
                            // the pane, so it owns entry focus while it exists.
                            focusRequester = if (index == 0) firstFocusRequester else null,
                            modifier = if (index == 0) {
                                Modifier.tvRevealsListContext(viewportPx, abovePx = viewportPx)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingsGroup(title = "Capture") {
                SettingsValueRow(
                    label = "Send Reports To",
                    value = tvDiagnosticsDestinationTitle(state.destinationKind),
                    onClick = { activePicker = TvDiagnosticsPicker.Destination },
                    focusRequester = if (pendingOwnsFirstFocus) null else firstFocusRequester,
                    // The pane's first focus stop with no pending report, so it
                    // is the one that has to drag FEATURE STATE back into view
                    // (tvOS `.focused(detailFocus, .top)`).
                    modifier = if (pendingOwnsFirstFocus) {
                        Modifier
                    } else {
                        Modifier.tvRevealsListContext(viewportPx, abovePx = viewportPx)
                    },
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
                    // Last focus stop before the privacy text it qualifies, so
                    // it shows that text instead of leaving it to be flown past
                    // on the way to MANUAL REPORT.
                    modifier = Modifier.tvRevealsListContext(
                        viewportPx = viewportPx,
                        belowPx = groupRowGapPx + privacyFooterPx,
                    ),
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
                    modifier = Modifier.onSizeChanged { privacyFooterPx = it.height },
                )
                if (state.destinationKind == DiagnosticsDestinationKind.HOSTED) {
                    // The address alone is not reachable with a remote: footer
                    // text is deliberately outside the focus graph, so it can
                    // neither be activated nor copied. Keep the action row the
                    // hosted consent surface used to have.
                    SettingsActionRow(
                        label = "Privacy Policy",
                        onClick = { showPrivacyPolicy = true },
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
                    modifier = Modifier
                        .tvRevealsListContext(
                            viewportPx = viewportPx,
                            belowPx = if (state.debugLogging) 0 else groupRowGapPx + manualFooterPx,
                        )
                        .dimWhenDisabled(model.canCapture && !capturing),
                )
                if (!state.debugLogging) {
                    SettingsFooterText(
                        text = "Debug logging is off. This report contains only the last few minutes " +
                            "of basic logs.",
                        modifier = Modifier.onSizeChanged { manualFooterPx = it.height },
                    )
                }
            }
        }
        // Android-only: tvOS has no timed capture. It sits with MANUAL REPORT
        // because both are "capture something now" actions, which is also what
        // keeps the control sections contiguous.
        item {
            SettingsGroup(title = "Timed Capture") {
                if (capturing) {
                    SettingsActionRow(label = "Stop & Review", onClick = onStopTimedCapture)
                    SettingsActionRow(
                        label = "Cancel Capture",
                        onClick = onCancelTimedCapture,
                        destructive = true,
                        modifier = Modifier.tvRevealsListContext(viewportPx, belowPx = viewportPx),
                    )
                    // Explainers sit below their controls here, as they do in
                    // CAPTURE and MANUAL REPORT. Above the buttons this one was
                    // pushed off screen by the reveal that shows SENT HISTORY.
                    SettingsFooterText(
                        text = "Capture is running. Reproduce the issue, then stop to review.",
                    )
                } else {
                    SettingsActionRow(
                        label = "Start Diagnostic Capture",
                        onClick = onStartTimedCapture,
                        enabled = model.canCapture,
                        // The pane's last focus stop in every state, so it owns
                        // revealing everything that trails it.
                        modifier = Modifier
                            .tvRevealsListContext(viewportPx, belowPx = viewportPx)
                            .dimWhenDisabled(model.canCapture),
                    )
                    SettingsFooterText(
                        text = "Records logs until you stop it, then opens the report for review.",
                    )
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

    if (showPrivacyPolicy) {
        TvPrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
    }
}

/**
 * The policy itself is a web page, and an Android TV box is not guaranteed to
 * have a browser — nor is a TV a comfortable place to read one. So the action
 * hands the address to a device that is: the same QR idiom the login and
 * pairing screens use, with the URL spelled out for anyone typing it manually.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPrivacyPolicyDialog(onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Relocation, not acquisition: the dialog window already holds focus,
        // so a miss only costs the viewer a Back press instead of a Select.
        repeat(TvFrameRelocationMaxAttempts) {
            withFrameNanos { }
            if (closeFocus.claimFocusOrReport(target = "privacy_policy", action = "open")) {
                return@LaunchedEffect
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.86f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Privacy Policy",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Scan this code with your phone, or type the address below, to read " +
                        "the full policy.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                QrCodePanel(content = PRIVACY_POLICY_URL, size = 160.dp)
                Text(
                    text = PRIVACY_POLICY_URL,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SettingsActionRow(
                    label = "Done",
                    onClick = onDismiss,
                    focusRequester = closeFocus,
                )
            }
        }
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

// ---------------------------------------------------------------------------
// Bringing read-only context into view with the row that owns it
// ---------------------------------------------------------------------------

/**
 * Asks the scrolling parent for [abovePx] of extra room above this row, or
 * [belowPx] below it, whenever the row takes focus.
 *
 * Compose reveals the focused node and nothing else, which strands read-only
 * content at the ends of a list: there is no focus stop past it to scroll to.
 * The fix is the idiom `Modifier.tvImeAwareFieldContext` already uses for the
 * IME — hold a [BringIntoViewRequester] and ask for a [Rect] larger than the
 * node — pointed at list edges instead of at a keyboard.
 *
 * Pass the viewport height for [abovePx] / [belowPx] to mean "as much as will
 * fit": [tvListContextReveal] clamps the request so the rect is never taller
 * than the viewport. That bound is load-bearing rather than tidiness — a
 * bring-into-view rect that overhangs both edges is one Compose treats as
 * already visible and declines to scroll for at all, so an unclamped request
 * would silently do nothing.
 *
 * Applied to a row rather than to a wrapping container on purpose: a container
 * would need `focusGroup()` to observe its child's focus, and this pane has no
 * other reason to add focus groups between the D-pad and its rows.
 */
@Composable
private fun Modifier.tvRevealsListContext(
    viewportPx: Int,
    abovePx: Int = 0,
    belowPx: Int = 0,
): Modifier {
    if (viewportPx <= 0 || (abovePx <= 0 && belowPx <= 0)) return this

    val requester = remember { BringIntoViewRequester() }
    var nodeSize by remember { mutableStateOf(IntSize.Zero) }
    var hasFocus by remember { mutableStateOf(false) }
    // Null while the row is unfocused or unmeasured, which is also what keeps
    // the effect below from firing on every unrelated recomposition.
    val reveal = if (hasFocus) {
        tvListContextReveal(
            nodeHeightPx = nodeSize.height,
            viewportPx = viewportPx,
            abovePx = abovePx,
            belowPx = belowPx,
        )
    } else {
        null
    }

    LaunchedEffect(reveal, nodeSize.width) {
        val target = reveal ?: return@LaunchedEffect
        // Compose's own focus-driven bring-into-view runs first; landing a
        // frame later is what makes this request the one that wins.
        withFrameNanos { }
        runCatching {
            requester.bringIntoView(
                Rect(
                    left = 0f,
                    top = target.topPx,
                    right = nodeSize.width.toFloat(),
                    bottom = target.bottomPx,
                ),
            )
        }
    }

    return this
        .bringIntoViewRequester(requester)
        .onSizeChanged { nodeSize = it }
        // hasFocus, not isFocused: the focus target is the row's own clickable
        // Surface, a descendant of this modifier's node.
        .onFocusEvent { hasFocus = it.hasFocus }
}

/** Vertical extent a focused row asks for, in its own local coordinates. */
internal data class TvListContextReveal(val topPx: Float, val bottomPx: Float)

/**
 * Clamps a context request so the rect stays inside one viewport.
 *
 * Callers pass how much surrounding content they would *like* revealed (the
 * viewport height itself for "everything on that side"); what comes back never
 * exceeds `viewportPx`, because a taller rect overhangs both container edges
 * and Compose then scrolls by zero. Returns null when there is nothing to ask
 * for, or before the row has been measured.
 */
internal fun tvListContextReveal(
    nodeHeightPx: Int,
    viewportPx: Int,
    abovePx: Int,
    belowPx: Int,
): TvListContextReveal? {
    if (nodeHeightPx <= 0 || viewportPx <= 0) return null
    val room = (viewportPx - nodeHeightPx).coerceAtLeast(0)
    val above = abovePx.coerceIn(0, room)
    val below = belowPx.coerceIn(0, room - above)
    if (above == 0 && below == 0) return null
    return TvListContextReveal(
        // Subtraction rather than unary minus: `-0.toFloat()` is negative zero,
        // which is a different value to Float.equals and so to this data class.
        topPx = 0f - above,
        bottomPx = (nodeHeightPx + below).toFloat(),
    )
}

/**
 * Whether PENDING REPORTS owns the pane's first D-pad stop.
 *
 * The section sits above CAPTURE and only has focusable rows while reports are
 * waiting, so the row that carries entry focus — and with it the reveal that
 * brings FEATURE STATE back on screen — moves between sections with state.
 * Exactly one row must hold `firstFocusRequester`: none and the rail's
 * enter-category claim throws, two and the later one silently wins.
 */
internal fun tvDiagnosticsPendingOwnsFirstFocus(pendingCount: Int): Boolean = pendingCount > 0

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
