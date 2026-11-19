package org.siloserver.silo.common.diagnostics

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType

/*
 * The words the phone and TV diagnostics screens share. Each app lays them out.
 */

/** The Apple apps' report titles; Android's ANR and native crash fold into them. */
@StringRes
fun diagnosticsReportTypeTitle(type: DiagnosticsReportType): Int = when (type) {
    DiagnosticsReportType.CRASH, DiagnosticsReportType.NATIVE_CRASH -> R.string.diagnostics_reportType_crash
    DiagnosticsReportType.HANG, DiagnosticsReportType.ANR -> R.string.diagnostics_reportType_notResponding
    DiagnosticsReportType.ABNORMAL_EXIT -> R.string.diagnostics_reportType_uncleanShutdown
    DiagnosticsReportType.MANUAL -> R.string.diagnostics_reportType_manual
}

/** The Feature State status row: tvOS `DiagnosticsFeatureState.title`. */
@StringRes
fun diagnosticsAvailabilityTitle(availability: DiagnosticsAvailabilityUi): Int = when (availability) {
    DiagnosticsAvailabilityUi.AVAILABLE -> R.string.diagnostics_availability_available
    DiagnosticsAvailabilityUi.DISABLED -> R.string.diagnostics_availability_disabled
    DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE -> R.string.diagnostics_availability_storageUnavailable
    DiagnosticsAvailabilityUi.OFFLINE -> R.string.diagnostics_availability_offline
    DiagnosticsAvailabilityUi.INELIGIBLE -> R.string.diagnostics_availability_ineligible
}

/** The Crash Reports choices. */
@StringRes
fun diagnosticsConsentTitle(mode: DiagnosticsConsentMode): Int = when (mode) {
    DiagnosticsConsentMode.ASK -> R.string.diagnostics_crashReports_ask
    DiagnosticsConsentMode.ALWAYS -> R.string.diagnostics_crashReports_always
    DiagnosticsConsentMode.NEVER -> R.string.diagnostics_crashReports_never
}

/** The crash prompt's first sentence: one report by its type, or how many are waiting. */
@Composable
fun diagnosticsReportsReadyText(prompt: DiagnosticsPrompt): String =
    if (prompt.reportCount == 1) {
        stringResource(
            when (prompt.reportType) {
                DiagnosticsReportType.CRASH, DiagnosticsReportType.NATIVE_CRASH -> R.string.diagnostics_prompt_ready_crash
                DiagnosticsReportType.HANG, DiagnosticsReportType.ANR -> R.string.diagnostics_prompt_ready_notResponding
                DiagnosticsReportType.ABNORMAL_EXIT -> R.string.diagnostics_prompt_ready_uncleanShutdown
                DiagnosticsReportType.MANUAL -> R.string.diagnostics_prompt_ready_manual
            },
        )
    } else {
        pluralStringResource(R.plurals.diagnostics_prompt_readyCount, prompt.reportCount, prompt.reportCount)
    }

/** What became of a Send that did not finish with a reference ID. */
@Composable
fun diagnosticsUploadNotice(decision: DiagnosticsUploadDecision): String = when (decision) {
    DiagnosticsUploadDecision.KeptUncertain -> stringResource(R.string.diagnostics_upload_uncertain)
    is DiagnosticsUploadDecision.Uploaded -> "" // handled by the caller
    is DiagnosticsUploadDecision.HostedProcessing ->
        stringResource(R.string.diagnostics_upload_processing, decision.shortId)
    DiagnosticsUploadDecision.KeptRetryable -> stringResource(R.string.diagnostics_upload_retryable)
    DiagnosticsUploadDecision.KeptIdentityChanged -> stringResource(R.string.diagnostics_upload_identityChanged)
    DiagnosticsUploadDecision.KeptTooLarge -> stringResource(R.string.diagnostics_upload_tooLarge)
    DiagnosticsUploadDecision.KeptServerUpdateRequired -> stringResource(R.string.diagnostics_upload_serverUpdateRequired)
    DiagnosticsUploadDecision.KeptUnavailable -> stringResource(R.string.diagnostics_upload_unavailable)
    DiagnosticsUploadDecision.KeptInvalid -> stringResource(R.string.diagnostics_upload_invalid)
    DiagnosticsUploadDecision.KeptConsentReviewRequired -> stringResource(R.string.diagnostics_upload_consentReviewRequired)
}

/** A pending report's local upload state, in lower case as the detail line shows it. */
@Composable
fun diagnosticsUploadStatusLabel(status: PendingReportStatus): String = stringResource(
    when (status) {
        PendingReportStatus.PENDING -> R.string.diagnostics_uploadStatus_pending
        PendingReportStatus.PROCESSING -> R.string.diagnostics_uploadStatus_processing
        PendingReportStatus.RETRYABLE -> R.string.diagnostics_uploadStatus_retryable
        PendingReportStatus.PERMANENT_FAILURE -> R.string.diagnostics_uploadStatus_permanentFailure
    },
)

/**
 * A sent report's processing state from its destination ("accepted",
 * "processing", …). A state this build doesn't know shows as the server wrote
 * it, with underscores as spaces.
 */
@Composable
fun diagnosticsReportStateLabel(wireValue: String): String {
    val id = when (wireValue) {
        "accepted" -> R.string.diagnostics_reportState_accepted
        "receiving" -> R.string.diagnostics_reportState_receiving
        "uploaded" -> R.string.diagnostics_reportState_uploaded
        "processing" -> R.string.diagnostics_reportState_processing
        "ready" -> R.string.diagnostics_reportState_ready
        "rejected" -> R.string.diagnostics_reportState_rejected
        "deleting" -> R.string.diagnostics_reportState_deleting
        "deleted" -> R.string.diagnostics_reportState_deleted
        else -> null
    }
    return id?.let { stringResource(it) } ?: wireValue.replace('_', ' ')
}

/** A report's evidence size: "1.4 MB", "12.0 KB", "512 B". */
@Composable
fun diagnosticsSizeLabel(bytes: Long): String = when {
    bytes >= 1_048_576 -> stringResource(R.string.diagnostics_size_megabytes, "%.1f".format(bytes / 1_048_576.0))
    bytes >= 1_024 -> stringResource(R.string.diagnostics_size_kilobytes, "%.1f".format(bytes / 1_024.0))
    else -> stringResource(R.string.diagnostics_size_bytes, bytes.toString())
}
