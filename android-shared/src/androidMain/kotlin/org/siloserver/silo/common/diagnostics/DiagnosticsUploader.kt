package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.CancellationException
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsErrorCode
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResult
import org.siloserver.silo.network.api.DiagnosticsApi
import org.siloserver.silo.network.api.HostedDiagnosticsApi
import org.siloserver.silo.network.api.HostedDiagnosticsApiResult
import org.siloserver.silo.network.api.HostedDiagnosticsCreateReportRequest
import org.siloserver.silo.network.api.HostedDiagnosticsAvailability
import org.siloserver.silo.network.api.HostedDiagnosticsCapabilities
import org.siloserver.silo.network.api.HostedDiagnosticsReportState

sealed interface DiagnosticsUploadDecision {
    data class Uploaded(val shortId: String, val state: String = "ready") : DiagnosticsUploadDecision
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
    suspend fun tokens(destinationKind: DiagnosticsDestinationKind): List<String>
}

fun interface DiagnosticsSentRecorder {
    suspend fun record(binding: DiagnosticsBinding, shortId: String, sentAtEpochMs: Long, state: String)
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
    private val hostedApi: HostedDiagnosticsApi? = null,
    private val hostedInstallations: HostedDiagnosticsInstallationManager? = null,
    private val hostedCapabilities: HostedDiagnosticsCapabilitiesRepository? = null,
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
        if (
            report.binding.destinationKind == DiagnosticsDestinationKind.HOSTED &&
            report.state.hostedRemoteShortId != null
        ) {
            return try {
                pollHostedStatus(report)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                markRetryable(report.id, "status_unavailable")
                DiagnosticsUploadDecision.KeptRetryable
            }
        }
        val retryDeadline = reports.retryAfterDeadline(report.binding.binding)
        if (retryDeadline != null && retryDeadline > nowMs()) return DiagnosticsUploadDecision.KeptRetryable
        if (requireAlwaysConsent && report.binding.destinationKind == DiagnosticsDestinationKind.HOSTED) {
            return DiagnosticsUploadDecision.KeptConsentReviewRequired
        }
        val liveHostedCapabilities = if (report.binding.destinationKind == DiagnosticsDestinationKind.HOSTED) {
            when (val result = hostedCapabilities?.refresh()) {
                is HostedDiagnosticsApiResult.Success -> result.value
                is HostedDiagnosticsApiResult.Failure -> return mapHostedError(report, result)
                is HostedDiagnosticsApiResult.NetworkError, null -> {
                    markRetryable(report.id, "network")
                    return DiagnosticsUploadDecision.KeptRetryable
                }
            }
        } else {
            null
        }
        val beforeBase = identity.resolve(requirePersistentCapture = true)
            ?: return DiagnosticsUploadDecision.KeptUnavailable
        val before = beforeBase.withHostedCapabilities(liveHostedCapabilities)
            ?: return DiagnosticsUploadDecision.KeptIdentityChanged
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
        var hostedEnvelopeMustBePersisted = false
        val bundle = if (report.binding.destinationKind == DiagnosticsDestinationKind.HOSTED) {
            when (val cached = reports.loadHostedEnvelope(report.id)) {
                HostedEnvelopeLoadResult.Corrupt -> {
                    markPermanent(report.id, "invalid_hosted_envelope")
                    return DiagnosticsUploadDecision.KeptInvalid
                }
                is HostedEnvelopeLoadResult.Available -> {
                    if (report.state.hostedConsentRefreshRequired) {
                        hostedEnvelopeMustBePersisted = true
                        runCatching {
                            bundleBuilder.reframeHosted(cached.bundle, framedReport.manifest.consent)
                        }.getOrElse {
                            markPermanent(report.id, "invalid_hosted_envelope")
                            return DiagnosticsUploadDecision.KeptInvalid
                        }
                    } else {
                        // Once the first create envelope is committed locally,
                        // every ambiguous retry must replay its exact manifest,
                        // length and SHA even if tokens or collector policy rotate.
                        cached.bundle
                    }
                }
                HostedEnvelopeLoadResult.Missing -> {
                    val tokens = try {
                        redactionTokens.tokens(report.binding.destinationKind)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        markRetryable(report.id, "redaction_tokens_unavailable")
                        return DiagnosticsUploadDecision.KeptRetryable
                    }
                    hostedEnvelopeMustBePersisted = true
                    runCatching { bundleBuilder.build(framedReport, tokens) }.getOrElse {
                        markPermanent(report.id, "invalid_bundle")
                        return DiagnosticsUploadDecision.KeptInvalid
                    }
                }
            }
        } else {
            val tokens = try {
                redactionTokens.tokens(report.binding.destinationKind)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                markRetryable(report.id, "redaction_tokens_unavailable")
                return DiagnosticsUploadDecision.KeptRetryable
            }
            runCatching { bundleBuilder.build(framedReport, tokens) }.getOrElse {
                markPermanent(report.id, "invalid_bundle")
                return DiagnosticsUploadDecision.KeptInvalid
            }
        }
        val enforceAdvertisedSizeLimits =
            report.binding.destinationKind != DiagnosticsDestinationKind.HOSTED || hostedEnvelopeMustBePersisted
        if (
            enforceAdvertisedSizeLimits &&
            (bundle.bytes.size.toLong() > before.maxBundleBytes ||
                bundle.manifestBytes.size.toLong() > before.maxManifestBytes)
        ) {
            markPermanent(report.id, "too_large")
            return DiagnosticsUploadDecision.KeptTooLarge
        }

        val afterBase = identity.resolve(requirePersistentCapture = true)
            ?: return DiagnosticsUploadDecision.KeptIdentityChanged
        val after = afterBase.withHostedCapabilities(liveHostedCapabilities)
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
            enforceAdvertisedSizeLimits &&
            (bundle.bytes.size.toLong() > after.maxBundleBytes ||
                bundle.manifestBytes.size.toLong() > after.maxManifestBytes)
        ) {
            markPermanent(report.id, "too_large")
            return DiagnosticsUploadDecision.KeptTooLarge
        }

        if (hostedEnvelopeMustBePersisted) {
            try {
                // This durable local commit is the send boundary. Never make a
                // create request unless the exact sanitized envelope can be
                // replayed after process death or a lost response.
                reports.saveHostedEnvelope(report.id, bundle)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                markRetryable(report.id, "hosted_envelope_unavailable")
                return DiagnosticsUploadDecision.KeptRetryable
            }
        }

        val decision = try {
            when (report.binding.destinationKind) {
                DiagnosticsDestinationKind.HOSTED -> uploadHosted(report, bundle)
                DiagnosticsDestinationKind.SELF_HOSTED -> uploadSelfHosted(report, bundle, after.noticeVersion)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            markRetryable(report.id, "network")
            return DiagnosticsUploadDecision.KeptRetryable
        }
        return decision
    }

    private suspend fun uploadSelfHosted(
        report: PendingReport,
        bundle: DiagnosticsBundle,
        noticeVersion: Int,
    ): DiagnosticsUploadDecision = when (
        val result = api.upload(bundle.manifestBytes, bundle.bytes, report.binding.profileId)
    ) {
            is DiagnosticsUploadResult.Success -> {
                reports.delete(report.id)
                runCatching { sentRecorder.record(report.binding.binding, result.response.shortId, nowMs(), "ready") }
                DiagnosticsUploadDecision.Uploaded(result.response.shortId, "ready")
            }
            is DiagnosticsUploadResult.NetworkError -> {
                markRetryable(report.id, "network")
                DiagnosticsUploadDecision.KeptRetryable
            }
            is DiagnosticsUploadResult.Failure -> mapServerError(report, result, noticeVersion)
        }

    private suspend fun uploadHosted(
        report: PendingReport,
        bundle: DiagnosticsBundle,
    ): DiagnosticsUploadDecision {
        val wireReportId = report.id.toHostedWireReportIdOrNull() ?: run {
            markPermanent(report.id, "invalid_report_id")
            return DiagnosticsUploadDecision.KeptInvalid
        }
        val installations = hostedInstallations ?: return DiagnosticsUploadDecision.KeptUnavailable
        val credentials = installations.getOrCreate() ?: run {
            markRetryable(report.id, "installation_unavailable")
            return DiagnosticsUploadDecision.KeptRetryable
        }
        return uploadHosted(report, bundle, wireReportId, credentials, mayReplaceInvalidCredentials = true)
    }

    private suspend fun uploadHosted(
        report: PendingReport,
        bundle: DiagnosticsBundle,
        wireReportId: String,
        credentials: HostedDiagnosticsCredentials,
        mayReplaceInvalidCredentials: Boolean,
    ): DiagnosticsUploadDecision {
        val hostedApi = hostedApi ?: return DiagnosticsUploadDecision.KeptUnavailable
        val created = when (
            val result = hostedApi.createReport(
                installationToken = credentials.installationToken,
                request = HostedDiagnosticsCreateReportRequest(
                    reportId = wireReportId,
                    manifest = bundle.manifest,
                    bundleBytes = bundle.bytes.size.toLong(),
                    bundleSha256 = bundle.manifest.archive.sha256,
                ),
            )
        ) {
            is HostedDiagnosticsApiResult.Success -> result.value
            is HostedDiagnosticsApiResult.Failure -> {
                if (result.errorCode == "invalid_installation_token" && mayReplaceInvalidCredentials) {
                    val replacement = replaceHostedCredentials() ?: run {
                        markRetryable(report.id, "installation_unavailable")
                        return DiagnosticsUploadDecision.KeptRetryable
                    }
                    return uploadHosted(
                        report,
                        bundle,
                        wireReportId,
                        replacement,
                        mayReplaceInvalidCredentials = false,
                    )
                }
                return mapHostedError(report, result)
            }
            is HostedDiagnosticsApiResult.NetworkError -> {
                markRetryable(report.id, "network")
                return DiagnosticsUploadDecision.KeptRetryable
            }
        }
        if (created.reportId != wireReportId || created.shortId.isBlank() || created.uploadToken.isBlank()) {
            markPermanent(report.id, "invalid_response")
            return DiagnosticsUploadDecision.KeptInvalid
        }
        val uploadReceipt = when (
            val uploaded = hostedApi.uploadBundle(
                installationToken = credentials.installationToken,
                reportId = wireReportId,
                uploadToken = created.uploadToken,
                bundle = bundle.bytes,
            )
        ) {
            is HostedDiagnosticsApiResult.Success -> uploaded.value
            is HostedDiagnosticsApiResult.Failure -> return mapHostedError(report, uploaded)
            is HostedDiagnosticsApiResult.NetworkError -> {
                markRetryable(report.id, "network")
                return DiagnosticsUploadDecision.KeptRetryable
            }
        }
        val uploadShortId = uploadReceipt.shortId?.takeIf(String::isNotBlank) ?: run {
            markPermanent(report.id, "invalid_response")
            return DiagnosticsUploadDecision.KeptInvalid
        }
        if (
            uploadReceipt.reportId != wireReportId ||
            uploadShortId != created.shortId ||
            uploadReceipt.state !in HOSTED_DURABLY_ACCEPTED_STATES
        ) {
            markPermanent(report.id, "invalid_response")
            return DiagnosticsUploadDecision.KeptInvalid
        }
        // Persist the remote identity immediately after the first validated
        // durable receipt so an eventual rejection can still be deleted from
        // the collector before local evidence is removed.
        reports.markHostedProcessing(report.id, uploadShortId)

        val state = when (
            val status = hostedApi.reportStatus(credentials.installationToken, wireReportId)
        ) {
            is HostedDiagnosticsApiResult.Success -> {
                if (
                    status.value.reportId != wireReportId ||
                    status.value.shortId?.takeIf(String::isNotBlank) != uploadShortId
                ) {
                    markPermanent(report.id, "invalid_response")
                    return DiagnosticsUploadDecision.KeptInvalid
                }
                when (status.value.state) {
                    HostedDiagnosticsReportState.REJECTED,
                    HostedDiagnosticsReportState.DELETING,
                    HostedDiagnosticsReportState.DELETED,
                    -> {
                        markPermanent(report.id, status.value.errorCode ?: status.value.state.wireValue)
                        return DiagnosticsUploadDecision.KeptInvalid
                    }
                    in HOSTED_DURABLY_ACCEPTED_STATES -> status.value.state
                    else -> {
                        markPermanent(report.id, "invalid_response")
                        return DiagnosticsUploadDecision.KeptInvalid
                    }
                }
            }
            // Only a validated durable-acceptance receipt permits this fallback.
            is HostedDiagnosticsApiResult.Failure,
            is HostedDiagnosticsApiResult.NetworkError,
            -> uploadReceipt.state
        }
        if (state == HostedDiagnosticsReportState.READY) {
            reports.delete(report.id)
            runCatching { sentRecorder.record(report.binding.binding, uploadShortId, nowMs(), state.wireValue) }
            return DiagnosticsUploadDecision.Uploaded(uploadShortId, state.wireValue)
        }
        return DiagnosticsUploadDecision.KeptRetryable
    }

    private suspend fun pollHostedStatus(report: PendingReport): DiagnosticsUploadDecision {
        val expectedShortId = report.state.hostedRemoteShortId ?: return DiagnosticsUploadDecision.KeptRetryable
        val wireReportId = report.id.toHostedWireReportIdOrNull() ?: run {
            markPermanent(report.id, "invalid_report_id")
            return DiagnosticsUploadDecision.KeptInvalid
        }
        val credentials = hostedInstallations?.current() ?: run {
            markRetryable(report.id, "installation_unavailable")
            return DiagnosticsUploadDecision.KeptRetryable
        }
        val api = hostedApi ?: return DiagnosticsUploadDecision.KeptUnavailable
        return when (val result = api.reportStatus(credentials.installationToken, wireReportId)) {
            is HostedDiagnosticsApiResult.NetworkError -> {
                markRetryable(report.id, "network")
                DiagnosticsUploadDecision.KeptRetryable
            }
            is HostedDiagnosticsApiResult.Failure -> {
                markRetryable(report.id, result.errorCode.ifBlank { "status_unavailable" })
                DiagnosticsUploadDecision.KeptRetryable
            }
            is HostedDiagnosticsApiResult.Success -> {
                val status = result.value
                if (
                    status.reportId != wireReportId ||
                    status.shortId?.takeIf(String::isNotBlank) != expectedShortId
                ) {
                    markPermanent(report.id, "invalid_response")
                    return DiagnosticsUploadDecision.KeptInvalid
                }
                when (status.state) {
                    HostedDiagnosticsReportState.READY -> {
                        reports.delete(report.id)
                        runCatching {
                            sentRecorder.record(report.binding.binding, expectedShortId, nowMs(), "ready")
                        }
                        DiagnosticsUploadDecision.Uploaded(expectedShortId, "ready")
                    }
                    HostedDiagnosticsReportState.PROCESSING -> {
                        reports.markHostedProcessing(report.id, expectedShortId)
                        DiagnosticsUploadDecision.KeptRetryable
                    }
                    HostedDiagnosticsReportState.REJECTED,
                    HostedDiagnosticsReportState.DELETING,
                    HostedDiagnosticsReportState.DELETED,
                    -> {
                        // Keep the last local evidence copy. The collector may
                        // have removed its unvalidated raw object already.
                        markPermanent(report.id, status.errorCode ?: status.state.wireValue)
                        DiagnosticsUploadDecision.KeptInvalid
                    }
                    else -> {
                        markPermanent(report.id, "invalid_response")
                        DiagnosticsUploadDecision.KeptInvalid
                    }
                }
            }
        }
    }

    private suspend fun replaceHostedCredentials(): HostedDiagnosticsCredentials? {
        val installations = hostedInstallations ?: return null
        return try {
            installations.clear()
            installations.getOrCreate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun mapHostedError(
        report: PendingReport,
        error: HostedDiagnosticsApiResult.Failure,
    ): DiagnosticsUploadDecision {
        val code = error.errorCode.ifBlank { "unknown" }
        if (code == "stale_consent") {
            return try {
                reports.markHostedConsentRefreshRequired(report.id)
                staleConsentHandler.demote(report.binding.binding, report.manifest.consent.noticeVersion)
                markRetryable(report.id, code)
                DiagnosticsUploadDecision.KeptConsentReviewRequired
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                markRetryable(report.id, code)
                DiagnosticsUploadDecision.KeptRetryable
            }
        }
        val decision = when {
            code in HOSTED_TOO_LARGE_ERRORS -> DiagnosticsUploadDecision.KeptTooLarge
            code == "unsupported_schema" -> DiagnosticsUploadDecision.KeptServerUpdateRequired
            code == "disabled" || code == "storage_unavailable" -> DiagnosticsUploadDecision.KeptUnavailable
            code in HOSTED_PERMANENT_ERRORS -> DiagnosticsUploadDecision.KeptInvalid
            code in HOSTED_RETRYABLE_ERRORS ||
                (code == "invalid_response" && error.httpStatus == 202) ||
                error.httpStatus == 429 || error.httpStatus >= 500 -> {
                DiagnosticsUploadDecision.KeptRetryable
            }
            else -> DiagnosticsUploadDecision.KeptInvalid
        }
        if (decision == DiagnosticsUploadDecision.KeptRetryable) {
            error.retryAfterSeconds?.coerceIn(0, MAX_RETRY_AFTER_SECONDS)?.let { seconds ->
                reports.setRetryAfterDeadline(report.binding.binding, nowMs() + seconds * 1_000L)
            }
        }
        when (decision) {
            DiagnosticsUploadDecision.KeptRetryable,
            DiagnosticsUploadDecision.KeptUnavailable,
            -> markRetryable(report.id, code)
            else -> markPermanent(report.id, code)
        }
        return decision
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
        val HOSTED_RETRYABLE_ERRORS = setOf(
            "busy",
            "quota_exceeded",
            "rate_limited",
            "internal_error",
            "invalid_upload_token",
            "upload_cancelled",
        )
        val HOSTED_TOO_LARGE_ERRORS = setOf(
            "bundle_too_large",
            "manifest_too_large",
            "compression_ratio_exceeded",
        )
        val HOSTED_DURABLY_ACCEPTED_STATES = setOf(
            HostedDiagnosticsReportState.PROCESSING,
            HostedDiagnosticsReportState.READY,
        )
        val HOSTED_PERMANENT_ERRORS = setOf(
            "invalid_request",
            "unexpected_field",
            "invalid_report_id",
            "invalid_bundle_size",
            "invalid_bundle_sha256",
            "invalid_manifest",
            "hosted_consent_required",
            "privacy_field_rejected",
            "privacy_value_rejected",
            "privacy_artifact_rejected",
            "wrong_destination",
            "archive_metadata_mismatch",
            "report_conflict",
            "upload_attempt_limit_exceeded",
            "unsupported_media_type",
            "size_mismatch",
            "invalid_installation_token",
        )
    }
}

private fun PendingReport.canUploadUnder(context: DiagnosticsCaptureContext): Boolean =
    context.profileEligible &&
        binding.destinationKind == context.destinationKind &&
        binding.matches(context) &&
        manifest.destination.serverInstanceId == context.binding.serverInstanceId

private fun DiagnosticsCaptureContext.withHostedCapabilities(
    capabilities: HostedDiagnosticsCapabilities?,
): DiagnosticsCaptureContext? {
    if (capabilities == null) return this
    if (
        destinationKind != DiagnosticsDestinationKind.HOSTED ||
        capabilities.collectorId != HOSTED_DIAGNOSTICS_COLLECTOR_ID ||
        binding.serverInstanceId != capabilities.collectorId
    ) return null
    return copy(
        noticeVersion = capabilities.consentNoticeVersion,
        status = when (capabilities.status) {
            HostedDiagnosticsAvailability.AVAILABLE -> DiagnosticsAvailabilityStatus.AVAILABLE
            HostedDiagnosticsAvailability.DISABLED -> DiagnosticsAvailabilityStatus.DISABLED
            HostedDiagnosticsAvailability.STORAGE_UNAVAILABLE -> DiagnosticsAvailabilityStatus.STORAGE_UNAVAILABLE
        },
        acceptedSchemaVersions = capabilities.acceptedSchemaVersions.toSet(),
        maxBundleBytes = capabilities.maxBundleBytes,
        maxManifestBytes = capabilities.maxManifestBytes,
        retentionDays = capabilities.retentionDays,
    )
}

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

internal fun PendingReport.withCurrentConsent(
    mode: DiagnosticsConsentMode,
    noticeVersion: Int,
): PendingReport {
    val hosted = binding.destinationKind == DiagnosticsDestinationKind.HOSTED
    val manifestMode = if (manifest.report.type == DiagnosticsReportType.MANUAL) {
        org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.MANUAL
    } else if (!hosted && mode == DiagnosticsConsentMode.ALWAYS) {
        org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.ALWAYS
    } else {
        org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.PROMPT
    }
    return copy(
        manifest = manifest.copy(
            report = if (hosted) manifest.report.copy(profileId = null) else manifest.report,
            consent = org.siloserver.silo.model.diagnostics.DiagnosticsConsent(manifestMode, noticeVersion),
            playbackSessionIds = if (hosted) emptyList() else manifest.playbackSessionIds,
        ),
    )
}

internal fun String.toHostedWireReportIdOrNull(): String? {
    val local = lowercase()
    if (!LOCAL_REPORT_ID.matches(local)) return null
    return buildString(36) {
        append(local, 0, 8)
        append('-')
        append(local, 8, 12)
        append('-')
        append(local, 12, 16)
        append('-')
        append(local, 16, 20)
        append('-')
        append(local, 20, 32)
    }
}

private val LOCAL_REPORT_ID = Regex("[0-9a-f]{32}")
