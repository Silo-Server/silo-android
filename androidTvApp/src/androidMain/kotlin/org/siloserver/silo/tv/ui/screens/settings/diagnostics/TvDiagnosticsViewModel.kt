package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
import org.siloserver.silo.common.diagnostics.DiagnosticsPrompt
import org.siloserver.silo.common.diagnostics.DiagnosticsUiState

typealias TvDiagnosticsViewModel = org.siloserver.silo.common.diagnostics.DiagnosticsViewModel

enum class TvDiagnosticsPromptFocus { DONT_SEND, REVIEW, SEND, ALWAYS_SEND }

data class TvDiagnosticsPromptModel(val initialFocus: TvDiagnosticsPromptFocus)

data class TvDiagnosticsConsentAction(val requiresConfirmation: Boolean)

data class TvDiagnosticsScreenModel(
    val showPending: Boolean,
    val canUpload: Boolean,
    val canDelete: Boolean,
    val canCapture: Boolean,
)

fun tvDiagnosticsPromptModel(prompt: DiagnosticsPrompt): TvDiagnosticsPromptModel {
    require(prompt.reportId.isNotBlank())
    return TvDiagnosticsPromptModel(TvDiagnosticsPromptFocus.DONT_SEND)
}

fun tvDiagnosticsConsentAction(
    current: DiagnosticsConsentMode,
    requested: DiagnosticsConsentMode,
): TvDiagnosticsConsentAction = TvDiagnosticsConsentAction(
    requiresConfirmation = requested == DiagnosticsConsentMode.ALWAYS && current != DiagnosticsConsentMode.ALWAYS,
)

fun tvDiagnosticsScreenModel(state: DiagnosticsUiState): TvDiagnosticsScreenModel =
    TvDiagnosticsScreenModel(
        showPending = state.pending.isNotEmpty(),
        canUpload = state.profileEligible && state.availability == DiagnosticsAvailabilityUi.AVAILABLE,
        canDelete = state.profileEligible && state.pending.isNotEmpty(),
        canCapture = state.profileEligible && state.availability != DiagnosticsAvailabilityUi.OFFLINE,
    )

// ---------------------------------------------------------------------------
// Pane presentation — pure helpers shared with TvDiagnosticsSettingsPane.
//
// tvOS renders diagnostics as read-only "info" rows plus a handful of picker /
// toggle / action rows (TVDiagnosticsSettingsPane.swift). These functions carry
// the label and option logic so the composable stays declarative and the
// choices stay unit-testable.
// ---------------------------------------------------------------------------

/** tvOS `DiagnosticsFeatureState.title` parity. */
internal fun tvDiagnosticsStatusTitle(availability: DiagnosticsAvailabilityUi): String =
    when (availability) {
        DiagnosticsAvailabilityUi.AVAILABLE -> "Available"
        DiagnosticsAvailabilityUi.DISABLED -> "Disabled by server"
        DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE -> "Storage unavailable"
        DiagnosticsAvailabilityUi.OFFLINE -> "Offline"
        DiagnosticsAvailabilityUi.INELIGIBLE -> "Unavailable"
    }

/** The two destinations, in the order the picker offers them. */
internal val TvDiagnosticsDestinations: List<DiagnosticsDestinationKind> = listOf(
    DiagnosticsDestinationKind.HOSTED,
    DiagnosticsDestinationKind.SELF_HOSTED,
)

internal fun tvDiagnosticsDestinationTitle(kind: DiagnosticsDestinationKind): String = when (kind) {
    DiagnosticsDestinationKind.HOSTED -> "Silo Diagnostics"
    DiagnosticsDestinationKind.SELF_HOSTED -> "This Silo server"
}

/**
 * tvOS `model.destinationServerName`: the hosted collector is named outright,
 * a self-hosted destination reads as the connected server.
 */
internal fun tvDiagnosticsDestinationName(
    kind: DiagnosticsDestinationKind,
    serverName: String,
): String = when (kind) {
    DiagnosticsDestinationKind.HOSTED -> "Silo Diagnostics"
    DiagnosticsDestinationKind.SELF_HOSTED -> serverName.ifBlank { "This Silo server" }
}

internal fun tvDiagnosticsConsentTitle(mode: DiagnosticsConsentMode): String = when (mode) {
    DiagnosticsConsentMode.ASK -> "Ask"
    DiagnosticsConsentMode.ALWAYS -> "Always"
    DiagnosticsConsentMode.NEVER -> "Never"
}

/**
 * "Always" only exists where the destination can accept an unattended upload.
 * A hosted collector never does, so the option is not offered at all.
 */
internal fun tvDiagnosticsConsentOptions(allowsAutomaticUpload: Boolean): List<DiagnosticsConsentMode> =
    DiagnosticsConsentMode.entries.filter {
        it != DiagnosticsConsentMode.ALWAYS || allowsAutomaticUpload
    }

/**
 * A stored ALWAYS becomes ASK when the destination stopped allowing automatic
 * upload, so the row never claims a mode the picker cannot even show.
 */
internal fun tvDiagnosticsEffectiveConsent(
    consent: DiagnosticsConsentMode,
    allowsAutomaticUpload: Boolean,
): DiagnosticsConsentMode =
    if (consent == DiagnosticsConsentMode.ALWAYS && !allowsAutomaticUpload) {
        DiagnosticsConsentMode.ASK
    } else {
        consent
    }

/** tvOS interpolates the count into the section header. */
internal fun tvDiagnosticsPendingHeader(count: Int): String = "Pending Reports ($count)"

/**
 * tvOS `typeTitle(for:)` parity — the wire enum is not a label. Android carries
 * two extra cases (ANR, NATIVE_CRASH) that Apple folds into the same titles.
 */
internal fun tvDiagnosticsReportTypeTitle(
    type: org.siloserver.silo.model.diagnostics.DiagnosticsReportType,
): String = when (type) {
    org.siloserver.silo.model.diagnostics.DiagnosticsReportType.CRASH,
    org.siloserver.silo.model.diagnostics.DiagnosticsReportType.NATIVE_CRASH,
    -> "Crash"
    org.siloserver.silo.model.diagnostics.DiagnosticsReportType.HANG,
    org.siloserver.silo.model.diagnostics.DiagnosticsReportType.ANR,
    -> "Not Responding"
    org.siloserver.silo.model.diagnostics.DiagnosticsReportType.ABNORMAL_EXIT -> "Unclean Shutdown"
    org.siloserver.silo.model.diagnostics.DiagnosticsReportType.MANUAL -> "Manual Report"
}

/** tvOS "Expires <relative>" — whole days, because a TV is read from a sofa. */
internal fun tvDiagnosticsExpiryLabel(expiresAtEpochMs: Long, nowEpochMs: Long): String {
    val remainingMs = expiresAtEpochMs - nowEpochMs
    if (remainingMs <= 0L) return "Expired"
    val days = ((remainingMs + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY).toInt()
    return when (days) {
        1 -> "Expires in 1 day"
        else -> "Expires in $days days"
    }
}

/**
 * The sent log is read-only, so it sits outside the focus graph and can only be
 * reached by scrolling past it. Keeping it shorter than a pane's worth of rows
 * is what makes that possible (tvOS caps at 10; see the ordering note in
 * TvDiagnosticsSettingsPane).
 */
internal const val TvDiagnosticsSentHistoryLimit = 5

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
