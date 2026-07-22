package org.siloserver.silo.common.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.common.diagnostics.consent.ConsentChoice
import org.siloserver.silo.common.diagnostics.consent.PendingReportStore

/**
 * Shared Settings→Diagnostics ViewModel for phone and TV — the platforms
 * differ only in Compose UI, not in behavior, so the state machine lives once
 * in android-shared.
 */
class DiagnosticsViewModel(
    private val coordinator: DiagnosticsCoordinator,
) : ViewModel() {

    val state: StateFlow<DiagnosticsCoordinator.State> = coordinator.state
    val prompt: StateFlow<DiagnosticsCoordinator.Prompt?> = coordinator.prompt

    /** Transient, user-visible outcome message (uploaded short id, kept reason…). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Set after Stop & Review / Send Now builds a manual report. */
    private val _manualReview = MutableStateFlow<DiagnosticsCoordinator.ManualReview?>(null)
    val manualReview: StateFlow<DiagnosticsCoordinator.ManualReview?> = _manualReview.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { runCatching { coordinator.refreshNow() } }
    }

    fun setConsentChoice(choice: ConsentChoice) {
        viewModelScope.launch { coordinator.setConsentChoice(choice) }
    }

    fun setDebugLogging(enabled: Boolean) {
        viewModelScope.launch { coordinator.setDebugLogging(enabled) }
    }

    fun startCapture() {
        coordinator.startManualCapture()
    }

    fun stopCaptureAndReview() {
        viewModelScope.launch {
            _busy.value = true
            _manualReview.value = coordinator.stopManualCaptureAndBuildReview()
            _busy.value = false
        }
    }

    /** One-shot "Send Diagnostics Now": build + review in one step. */
    fun buildSendNowReview() {
        viewModelScope.launch {
            _busy.value = true
            _manualReview.value = coordinator.buildManualReport()
            _busy.value = false
            if (_manualReview.value == null) {
                _notice.value = "Diagnostics can't be captured right now."
            }
        }
    }

    fun sendManualReview() {
        val review = _manualReview.value ?: return
        _manualReview.value = null
        upload(review.report)
    }

    /** Cancel review: the user chose not to send — remove the built report. */
    fun cancelManualReview() {
        val review = _manualReview.value ?: return
        _manualReview.value = null
        viewModelScope.launch { coordinator.deletePending(review.report) }
    }

    fun upload(report: PendingReportStore.PendingReport) {
        viewModelScope.launch {
            _busy.value = true
            _notice.value = when (val outcome = coordinator.uploadPending(report)) {
                is DiagnosticsCoordinator.UploadOutcome.Uploaded ->
                    "Report sent. Reference ID: ${outcome.shortId}"
                is DiagnosticsCoordinator.UploadOutcome.Kept -> outcome.userMessage
                is DiagnosticsCoordinator.UploadOutcome.Discarded ->
                    "This report couldn't be packaged and was discarded."
                is DiagnosticsCoordinator.UploadOutcome.Skipped -> null
            }
            _busy.value = false
        }
    }

    fun deletePending(report: PendingReportStore.PendingReport) {
        viewModelScope.launch { coordinator.deletePending(report) }
    }

    fun acceptPrompt(always: Boolean) {
        viewModelScope.launch {
            val outcomes = coordinator.acceptPrompt(always)
            val sent = outcomes.filterIsInstance<DiagnosticsCoordinator.UploadOutcome.Uploaded>()
            _notice.value = when {
                sent.isNotEmpty() -> "Report sent. Reference ID: ${sent.joinToString { it.shortId }}"
                outcomes.isEmpty() -> null
                else -> (outcomes.firstOrNull() as? DiagnosticsCoordinator.UploadOutcome.Kept)?.userMessage
            }
        }
    }

    fun declinePrompt() {
        viewModelScope.launch { coordinator.declinePrompt() }
    }

    fun consumeNotice() {
        _notice.value = null
    }
}
