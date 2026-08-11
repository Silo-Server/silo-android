package org.siloserver.silo.common.diagnostics

import org.siloserver.silo.model.diagnostics.DiagnosticsReportType

enum class DiagnosticsAvailabilityUi {
    AVAILABLE,
    DISABLED,
    STORAGE_UNAVAILABLE,
    OFFLINE,
    INELIGIBLE,
}

data class DiagnosticsReportSummary(
    val id: String,
    val type: DiagnosticsReportType,
    val capturedAt: String,
    val capturedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val evidenceBytes: Long,
    val destinationServerInstanceId: String,
    val capturedProfileId: String?,
    val archiveEntries: List<String>,
    val uploadStatus: PendingReportStatus,
    val uploadErrorCode: String?,
    val destinationKind: DiagnosticsDestinationKind = DiagnosticsDestinationKind.SELF_HOSTED,
)

data class DiagnosticsPrompt(
    val reportId: String,
    val reportType: DiagnosticsReportType,
    val capturedAt: String,
    val reportIds: List<String> = listOf(reportId),
    val noticeVersion: Int = 1,
) {
    init {
        require(reportId.isNotBlank())
        require(reportIds.isNotEmpty() && reportIds.all(String::isNotBlank))
        require(noticeVersion > 0)
    }

    val reportCount: Int get() = reportIds.size
}

enum class TimedCaptureStatus { IDLE, ACTIVE, INVALIDATED }

data class TimedCaptureState(
    val status: TimedCaptureStatus = TimedCaptureStatus.IDLE,
    val generation: Long? = null,
    val startedAtEpochMs: Long? = null,
)

data class DiagnosticsUiState(
    val availability: DiagnosticsAvailabilityUi = DiagnosticsAvailabilityUi.OFFLINE,
    val profileEligible: Boolean = false,
    val consent: DiagnosticsConsentMode = DiagnosticsConsentMode.ASK,
    val debugLogging: Boolean = false,
    val pending: List<DiagnosticsReportSummary> = emptyList(),
    val prompt: DiagnosticsPrompt? = null,
    val timedCapture: TimedCaptureState = TimedCaptureState(),
    val sentHistory: List<SentDiagnosticsReport> = emptyList(),
    val destinationKind: DiagnosticsDestinationKind = DiagnosticsDestinationKind.HOSTED,
    val allowsAutomaticUpload: Boolean = false,
    val retentionDays: Int = HOSTED_DIAGNOSTICS_RETENTION_DAYS,
)
