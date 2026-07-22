package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.CancellationException
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsErrorCode
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResult
import org.siloserver.silo.network.api.DiagnosticsApi

sealed interface DiagnosticsUploadDecision {
    data class Uploaded(val shortId: String) : DiagnosticsUploadDecision
    data object KeptRetryable : DiagnosticsUploadDecision
    data object KeptIdentityChanged : DiagnosticsUploadDecision
    data object KeptTooLarge : DiagnosticsUploadDecision
    data object KeptServerUpdateRequired : DiagnosticsUploadDecision
    data object KeptUnavailable : DiagnosticsUploadDecision
    data object KeptInvalid : DiagnosticsUploadDecision
    data object KeptConsentReviewRequired : DiagnosticsUploadDecision
}

fun interface DiagnosticsUploader {
    suspend fun upload(reportId: String): DiagnosticsUploadDecision

    suspend fun upload(
        reportId: String,
        expectedNoticeVersion: Int,
    ): DiagnosticsUploadDecision = upload(reportId)

    suspend fun uploadAutomatically(reportId: String): DiagnosticsUploadDecision = upload(reportId)
}

fun interface DiagnosticsRedactionTokenProvider {
    suspend fun tokens(): List<String>
}

fun interface DiagnosticsSentRecorder {
    suspend fun record(binding: DiagnosticsBinding, shortId: String, sentAtEpochMs: Long)
}

fun interface DiagnosticsUploadConsentProvider {
    suspend fun consent(binding: DiagnosticsBinding, noticeVersion: Int): DiagnosticsConsentMode
}

fun interface DiagnosticsStaleConsentHandler {
    suspend fun demote(binding: DiagnosticsBinding, noticeVersion: Int)
}

class SettingsDiagnosticsStaleConsentHandler(
    private val settings: DiagnosticsSettingsStore,
) : DiagnosticsStaleConsentHandler {
    override suspend fun demote(binding: DiagnosticsBinding, noticeVersion: Int) {
        settings.demoteAlwaysToAsk(binding, noticeVersion)
    }
}

class DefaultDiagnosticsUploader(
    private val reports: PendingReportStore,
    private val identity: DiagnosticsIdentityResolver,
    private val bundleBuilder: DiagnosticsBundleBuilder,
    private val api: DiagnosticsApi,
    private val redactionTokens: DiagnosticsRedactionTokenProvider,
    private val sentRecorder: DiagnosticsSentRecorder,
    private val consentProvider: DiagnosticsUploadConsentProvider = DiagnosticsUploadConsentProvider {
            _, _ -> DiagnosticsConsentMode.ASK
    },
    private val staleConsentHandler: DiagnosticsStaleConsentHandler = DiagnosticsStaleConsentHandler { _, _ -> },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DiagnosticsUploader {
    override suspend fun upload(reportId: String): DiagnosticsUploadDecision =
        upload(reportId, requireAlwaysConsent = false, expectedNoticeVersion = null)

    override suspend fun upload(
        reportId: String,
        expectedNoticeVersion: Int,
    ): DiagnosticsUploadDecision = upload(
        reportId,
        requireAlwaysConsent = false,
        expectedNoticeVersion = expectedNoticeVersion,
    )

    override suspend fun uploadAutomatically(reportId: String): DiagnosticsUploadDecision =
        upload(reportId, requireAlwaysConsent = true, expectedNoticeVersion = null)

    private suspend fun upload(
        reportId: String,
        requireAlwaysConsent: Boolean,
        expectedNoticeVersion: Int?,
    ): DiagnosticsUploadDecision {
        val report = reports.load(reportId) ?: return DiagnosticsUploadDecision.KeptInvalid
        val retryDeadline = reports.retryAfterDeadline(report.binding.binding)
        if (retryDeadline != null && retryDeadline > nowMs()) return DiagnosticsUploadDecision.KeptRetryable
        val before = identity.resolve(requirePersistentCapture = true)
            ?: return DiagnosticsUploadDecision.KeptUnavailable
        if (expectedNoticeVersion != null && before.noticeVersion != expectedNoticeVersion) {
            return DiagnosticsUploadDecision.KeptConsentReviewRequired
        }
        if (!report.canUploadUnder(before)) return DiagnosticsUploadDecision.KeptIdentityChanged
        if (before.status != DiagnosticsAvailabilityStatus.AVAILABLE) {
            return DiagnosticsUploadDecision.KeptUnavailable
        }
        if (report.manifest.schemaVersion !in before.acceptedSchemaVersions) {
            markPermanent(report.id, "unsupported_schema")
            return DiagnosticsUploadDecision.KeptServerUpdateRequired
        }
        val consentBefore = consentMode(report, requireAlwaysConsent, before.noticeVersion)
            ?: return DiagnosticsUploadDecision.KeptUnavailable
        if (!report.canUploadWithConsent(consentBefore, requireAlwaysConsent)) {
            return consentBefore.rejectedUploadDecision(requireAlwaysConsent)
        }
        val framedReport = report.withCurrentConsent(consentBefore, before.noticeVersion)

        val tokens = try {
            redactionTokens.tokens()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            markRetryable(report.id, "redaction_tokens_unavailable")
            return DiagnosticsUploadDecision.KeptRetryable
        }
        val bundle = runCatching { bundleBuilder.build(framedReport, tokens) }.getOrElse {
            markPermanent(report.id, "invalid_bundle")
            return DiagnosticsUploadDecision.KeptInvalid
        }
        if (bundle.bytes.size.toLong() > before.maxBundleBytes || bundle.manifestBytes.size.toLong() > before.maxManifestBytes) {
            markPermanent(report.id, "too_large")
            return DiagnosticsUploadDecision.KeptTooLarge
        }

        val after = identity.resolve(requirePersistentCapture = true)
            ?: return DiagnosticsUploadDecision.KeptIdentityChanged
        if (before.identityKey != after.identityKey || !report.canUploadUnder(after)) {
            return DiagnosticsUploadDecision.KeptIdentityChanged
        }
        if (after.status != DiagnosticsAvailabilityStatus.AVAILABLE) {
            return DiagnosticsUploadDecision.KeptUnavailable
        }
        if (report.manifest.schemaVersion !in after.acceptedSchemaVersions) {
            markPermanent(report.id, "unsupported_schema")
            return DiagnosticsUploadDecision.KeptServerUpdateRequired
        }
        val consentAfter = consentMode(report, requireAlwaysConsent, after.noticeVersion)
            ?: return DiagnosticsUploadDecision.KeptUnavailable
        if (!report.canUploadWithConsent(consentAfter, requireAlwaysConsent)) {
            return consentAfter.rejectedUploadDecision(requireAlwaysConsent)
        }
        if (after.noticeVersion != before.noticeVersion || consentAfter != consentBefore) {
            return DiagnosticsUploadDecision.KeptConsentReviewRequired
        }
        if (
            bundle.bytes.size.toLong() > after.maxBundleBytes ||
            bundle.manifestBytes.size.toLong() > after.maxManifestBytes
        ) {
            markPermanent(report.id, "too_large")
            return DiagnosticsUploadDecision.KeptTooLarge
        }

        val result = try {
            api.upload(bundle.manifestBytes, bundle.bytes, report.binding.profileId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            markRetryable(report.id, "network")
            return DiagnosticsUploadDecision.KeptRetryable
        }
        return when (result) {
            is DiagnosticsUploadResult.Success -> {
                reports.delete(report.id)
                runCatching { sentRecorder.record(report.binding.binding, result.response.shortId, nowMs()) }
                DiagnosticsUploadDecision.Uploaded(result.response.shortId)
            }
            is DiagnosticsUploadResult.NetworkError -> {
                markRetryable(report.id, "network")
                DiagnosticsUploadDecision.KeptRetryable
            }
            is DiagnosticsUploadResult.Failure -> mapServerError(report, result, after.noticeVersion)
        }
    }

    private suspend fun mapServerError(
        report: PendingReport,
        error: DiagnosticsUploadResult.Failure,
        noticeVersion: Int,
    ): DiagnosticsUploadDecision {
        val decision = when (error.code) {
            DiagnosticsErrorCode.BUSY,
            DiagnosticsErrorCode.QUOTA_EXCEEDED,
            DiagnosticsErrorCode.RATE_LIMITED,
            DiagnosticsErrorCode.INTERNAL_ERROR,
            -> DiagnosticsUploadDecision.KeptRetryable
            DiagnosticsErrorCode.TOO_LARGE -> DiagnosticsUploadDecision.KeptTooLarge
            DiagnosticsErrorCode.UNSUPPORTED_SCHEMA -> DiagnosticsUploadDecision.KeptServerUpdateRequired
            DiagnosticsErrorCode.STORAGE_UNAVAILABLE,
            DiagnosticsErrorCode.DISABLED,
            -> DiagnosticsUploadDecision.KeptUnavailable
            DiagnosticsErrorCode.DESTINATION_MISMATCH,
            DiagnosticsErrorCode.PROFILE_MISMATCH,
            DiagnosticsErrorCode.CHILD_PROFILE_FORBIDDEN,
            -> DiagnosticsUploadDecision.KeptIdentityChanged
            DiagnosticsErrorCode.STALE_CONSENT -> {
                try {
                    staleConsentHandler.demote(report.binding.binding, noticeVersion)
                    DiagnosticsUploadDecision.KeptConsentReviewRequired
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    markRetryable(report.id, error.code.wire)
                    return DiagnosticsUploadDecision.KeptRetryable
                }
            }
            DiagnosticsErrorCode.INVALID_BUNDLE,
            DiagnosticsErrorCode.INVALID_ARCHIVE,
            DiagnosticsErrorCode.INVALID_MANIFEST,
            DiagnosticsErrorCode.ARCHIVE_MISMATCH,
            DiagnosticsErrorCode.STALE_REPORT,
            DiagnosticsErrorCode.UNAUTHORIZED,
            DiagnosticsErrorCode.API_KEY_NOT_ALLOWED,
            DiagnosticsErrorCode.FORBIDDEN,
            -> DiagnosticsUploadDecision.KeptInvalid
            DiagnosticsErrorCode.UNKNOWN -> if (error.httpStatus == 429 || error.httpStatus >= 500) {
                DiagnosticsUploadDecision.KeptRetryable
            } else {
                DiagnosticsUploadDecision.KeptInvalid
            }
        }
        if (decision == DiagnosticsUploadDecision.KeptRetryable) {
            error.retryAfterSeconds?.coerceIn(0, MAX_RETRY_AFTER_SECONDS)?.let { seconds ->
                reports.setRetryAfterDeadline(report.binding.binding, nowMs() + seconds * 1_000L)
            }
        }
        val code = error.code.wire
        when (decision) {
            DiagnosticsUploadDecision.KeptRetryable,
            DiagnosticsUploadDecision.KeptUnavailable,
            DiagnosticsUploadDecision.KeptIdentityChanged,
            DiagnosticsUploadDecision.KeptConsentReviewRequired,
            -> markRetryable(report.id, code)
            else -> markPermanent(report.id, code)
        }
        return decision
    }

    private fun markRetryable(reportId: String, code: String) {
        runCatching { reports.markState(reportId, PendingReportStatus.RETRYABLE, code) }
    }

    private fun markPermanent(reportId: String, code: String) {
        runCatching { reports.markState(reportId, PendingReportStatus.PERMANENT_FAILURE, code) }
    }

    private suspend fun consentMode(
        report: PendingReport,
        requireAlways: Boolean,
        noticeVersion: Int,
    ): DiagnosticsConsentMode? {
        if (report.manifest.report.type == DiagnosticsReportType.MANUAL && !requireAlways) {
            return try {
                consentProvider.consent(report.binding.binding, noticeVersion)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                DiagnosticsConsentMode.ASK
            }
        }
        val mode = try {
            consentProvider.consent(report.binding.binding, noticeVersion)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        } ?: return null
        return mode
    }

    private companion object {
        const val MAX_RETRY_AFTER_SECONDS = 7L * 24 * 60 * 60
    }
}

private fun PendingReport.canUploadUnder(context: DiagnosticsCaptureContext): Boolean =
    context.profileEligible &&
        binding.matches(context) &&
        manifest.destination.serverInstanceId == context.binding.serverInstanceId

private fun PendingReport.canUploadWithConsent(
    mode: DiagnosticsConsentMode,
    requireAlways: Boolean,
): Boolean =
    (manifest.report.type == DiagnosticsReportType.MANUAL && !requireAlways) ||
        (mode != DiagnosticsConsentMode.NEVER && (!requireAlways || mode == DiagnosticsConsentMode.ALWAYS))

private fun DiagnosticsConsentMode.rejectedUploadDecision(requireAlways: Boolean): DiagnosticsUploadDecision =
    if (requireAlways && this == DiagnosticsConsentMode.ASK) {
        DiagnosticsUploadDecision.KeptConsentReviewRequired
    } else {
        DiagnosticsUploadDecision.KeptUnavailable
    }

private fun PendingReport.withCurrentConsent(
    mode: DiagnosticsConsentMode,
    noticeVersion: Int,
): PendingReport {
    val manifestMode = if (manifest.report.type == DiagnosticsReportType.MANUAL) {
        org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.MANUAL
    } else if (mode == DiagnosticsConsentMode.ALWAYS) {
        org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.ALWAYS
    } else {
        org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.PROMPT
    }
    return copy(
        manifest = manifest.copy(
            consent = org.siloserver.silo.model.diagnostics.DiagnosticsConsent(manifestMode, noticeVersion),
        ),
    )
}
