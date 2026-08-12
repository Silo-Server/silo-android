package org.siloserver.silo.common.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Shared phone/TV diagnostics behavior; platform modules own presentation only. */
class DiagnosticsViewModel(
    private val coordinator: DiagnosticsCoordinator,
) : ViewModel() {
    val state: StateFlow<DiagnosticsUiState> = coordinator.state

    init {
        coordinator.start()
        viewModelScope.launch { coordinator.refresh() }
    }

    fun refresh() {
        viewModelScope.launch { coordinator.refresh() }
    }

    fun setConsent(mode: DiagnosticsConsentMode) {
        viewModelScope.launch { coordinator.setConsent(mode) }
    }

    fun setDestination(destinationKind: DiagnosticsDestinationKind) {
        viewModelScope.launch { coordinator.setDestination(destinationKind) }
    }

    fun setDebugLogging(enabled: Boolean) {
        viewModelScope.launch { coordinator.setDebugLogging(enabled) }
    }

    fun captureNow(onCreated: (String) -> Unit = {}) {
        viewModelScope.launch { coordinator.captureNow()?.let(onCreated) }
    }

    fun startTimedCapture() {
        viewModelScope.launch { coordinator.startTimedCapture() }
    }

    fun stopTimedCapture(onCreated: (String) -> Unit = {}) {
        viewModelScope.launch { coordinator.stopTimedCapture()?.let(onCreated) }
    }

    fun cancelTimedCapture() {
        viewModelScope.launch { coordinator.cancelTimedCapture() }
    }

    fun upload(reportId: String, onComplete: (DiagnosticsUploadDecision) -> Unit = {}) {
        viewModelScope.launch { onComplete(coordinator.upload(reportId)) }
    }

    fun uploadPrompt(prompt: DiagnosticsPrompt) {
        viewModelScope.launch {
            for (reportId in prompt.reportIds) {
                val decision = coordinator.upload(reportId, expectedNoticeVersion = prompt.noticeVersion)
                when (decision) {
                    is DiagnosticsUploadDecision.Uploaded,
                    is DiagnosticsUploadDecision.HostedProcessing,
                    DiagnosticsUploadDecision.KeptInvalid,
                    DiagnosticsUploadDecision.KeptTooLarge,
                    DiagnosticsUploadDecision.KeptServerUpdateRequired,
                    -> Unit
                    DiagnosticsUploadDecision.KeptRetryable,
                    DiagnosticsUploadDecision.KeptIdentityChanged,
                    DiagnosticsUploadDecision.KeptUnavailable,
                    DiagnosticsUploadDecision.KeptConsentReviewRequired,
                    -> break
                }
            }
        }
    }

    fun alwaysSendPrompt(prompt: DiagnosticsPrompt) {
        viewModelScope.launch {
            coordinator.setConsent(
                DiagnosticsConsentMode.ALWAYS,
                expectedNoticeVersion = prompt.noticeVersion,
            )
        }
    }

    fun delete(reportId: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            if (coordinator.delete(reportId)) onDeleted()
        }
    }

    fun decline(reportId: String) {
        viewModelScope.launch { coordinator.decline(reportId) }
    }

    fun declinePrompt(prompt: DiagnosticsPrompt) {
        viewModelScope.launch { prompt.reportIds.forEach { coordinator.decline(it) } }
    }
}
