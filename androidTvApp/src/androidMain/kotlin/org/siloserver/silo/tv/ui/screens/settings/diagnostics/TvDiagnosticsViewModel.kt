package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
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
