package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
import org.siloserver.silo.common.diagnostics.DiagnosticsPrompt
import org.siloserver.silo.common.diagnostics.DiagnosticsUiState
import org.siloserver.silo.common.diagnostics.diagnosticsAvailabilityTitle
import org.siloserver.silo.common.diagnostics.diagnosticsConsentTitle
import org.siloserver.silo.common.diagnostics.diagnosticsReportTypeTitle
import org.siloserver.silo.common.R as CommonR

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
@StringRes
internal fun tvDiagnosticsStatusTitle(availability: DiagnosticsAvailabilityUi): Int =
    diagnosticsAvailabilityTitle(availability)

/** The two destinations, in the order the picker offers them. */
internal val TvDiagnosticsDestinations: List<DiagnosticsDestinationKind> = listOf(
    DiagnosticsDestinationKind.HOSTED,
    DiagnosticsDestinationKind.SELF_HOSTED,
)

@StringRes
internal fun tvDiagnosticsDestinationTitle(kind: DiagnosticsDestinationKind): Int = when (kind) {
    DiagnosticsDestinationKind.HOSTED -> CommonR.string.diagnostics_destination_hosted
    DiagnosticsDestinationKind.SELF_HOSTED -> CommonR.string.diagnostics_destination_thisServer
}

/**
 * tvOS `model.destinationServerName`: the connected server's name for a
 * self-hosted destination, or null where the destination's title stands in
 * (the hosted collector is named outright; an unnamed server must not render
 * an empty value row).
 */
internal fun tvDiagnosticsDestinationServerName(
    kind: DiagnosticsDestinationKind,
    serverName: String,
): String? = when (kind) {
    DiagnosticsDestinationKind.HOSTED -> null
    DiagnosticsDestinationKind.SELF_HOSTED -> serverName.ifBlank { null }
}

@Composable
internal fun tvDiagnosticsDestinationName(kind: DiagnosticsDestinationKind, serverName: String): String =
    tvDiagnosticsDestinationServerName(kind, serverName) ?: stringResource(tvDiagnosticsDestinationTitle(kind))

@StringRes
internal fun tvDiagnosticsConsentTitle(mode: DiagnosticsConsentMode): Int = diagnosticsConsentTitle(mode)

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
@Composable
internal fun tvDiagnosticsPendingHeader(count: Int): String =
    stringResource(CommonR.string.diagnostics_section_pending, count)

/**
 * tvOS `typeTitle(for:)` parity — the wire enum is not a label. Android carries
 * two extra cases (ANR, NATIVE_CRASH) that Apple folds into the same titles.
 */
@StringRes
internal fun tvDiagnosticsReportTypeTitle(
    type: org.siloserver.silo.model.diagnostics.DiagnosticsReportType,
): Int = diagnosticsReportTypeTitle(type)

/**
 * Whole days left before a pending report expires, rounded up; 0 once it has.
 * tvOS "Expires <relative>" — whole days, because a TV is read from a sofa.
 */
internal fun tvDiagnosticsExpiryDays(expiresAtEpochMs: Long, nowEpochMs: Long): Int {
    val remainingMs = expiresAtEpochMs - nowEpochMs
    if (remainingMs <= 0L) return 0
    return ((remainingMs + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY).toInt()
}

/** "Expires in 3 days", or "Expired". */
@Composable
internal fun tvDiagnosticsExpiryLabel(expiresAtEpochMs: Long, nowEpochMs: Long): String {
    val days = tvDiagnosticsExpiryDays(expiresAtEpochMs, nowEpochMs)
    return if (days <= 0) {
        stringResource(CommonR.string.diagnostics_report_expired)
    } else {
        pluralStringResource(CommonR.plurals.diagnostics_report_expiresIn, days, days)
    }
}

/**
 * The sent log is read-only, so it sits outside the focus graph, and it is the
 * last thing in the pane — the only way it can be seen at all is the pane's
 * last focus stop asking for it (`tvRevealsListContext`). That request cannot
 * exceed one viewport, which turns the cap into arithmetic rather than taste.
 *
 * At the 960x540dp reference surface the pane's list viewport is ~384dp, the
 * focused row is 42dp, and TIMED CAPTURE's footer plus the section gap take
 * ~36dp, leaving ~306dp. A section header (~26dp), the trailing footer (~35dp)
 * and the 6dp row gaps mean each entry costs 48dp: five entries land exactly on
 * the limit with nothing to spare, four leave ~48dp of slack for a wider font
 * scale or a footer that wraps one line further. tvOS caps at 10, but its pane
 * is not sharing a 540dp canvas with a rail and a category header.
 */
internal const val TvDiagnosticsSentHistoryLimit = 4

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
