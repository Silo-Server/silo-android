package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.CancellationException
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsErrorCode
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResult
import org.siloserver.silo.network.DiagnosticsUploadAuthorization
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.api.DiagnosticsApi
import org.siloserver.silo.network.api.HostedDiagnosticsApi
import org.siloserver.silo.network.api.HostedDiagnosticsApiResult
import org.siloserver.silo.network.api.HostedDiagnosticsCreateReportRequest
import org.siloserver.silo.network.api.HostedDiagnosticsAvailability
import org.siloserver.silo.network.api.HostedDiagnosticsCapabilities
import org.siloserver.silo.network.api.HostedDiagnosticsReportState
import org.siloserver.silo.network.api.HostedDiagnosticsReportStatusResponse

sealed interface DiagnosticsUploadDecision {
    data class Uploaded(
        val shortId: String,
        val state: HostedDiagnosticsReportState = HostedDiagnosticsReportState.READY,
    ) : DiagnosticsUploadDecision
    data class HostedProcessing(val shortId: String) : DiagnosticsUploadDecision
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

fun interface DiagnosticsSelfHostedAuthorizationProvider {
    suspend fun current(): DiagnosticsUploadAuthorization?
}

fun interface DiagnosticsSentRecorder {
    suspend fun record(binding: DiagnosticsBinding, shortId: String, sentAtEpochMs: Long, state: String)
}

fun interface DiagnosticsUploadConsentProvider {
    suspend fun consent(binding: DiagnosticsBinding, noticeVersion: Int): DiagnosticsConsentMode
}

fun interface DiagnosticsTransportPolicy {
    suspend fun permits(
        binding: PendingReportBinding,
        noticeVersion: Int,
        requireAlwaysConsent: Boolean,
    ): Boolean
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
    private val identityTransitions: IdentityTransitionBarrier,
    private val privacyBarrier: DiagnosticsPrivacyBarrier = DiagnosticsPrivacyBarrier(),
    private val bundleBuilder: DiagnosticsBundleBuilder,
    private val api: DiagnosticsApi,
    private val hostedApi: HostedDiagnosticsApi? = null,
    private val hostedInstallations: HostedDiagnosticsInstallationManager? = null,
    private val hostedCapabilities: HostedDiagnosticsCapabilitiesRepository? = null,
    private val redactionTokens: DiagnosticsRedactionTokenProvider,
    private val selfHostedAuthorization: DiagnosticsSelfHostedAuthorizationProvider,
    private val sentRecorder: DiagnosticsSentRecorder,
    private val consentProvider: DiagnosticsUploadConsentProvider = DiagnosticsUploadConsentProvider {
            _, _ -> DiagnosticsConsentMode.ASK
    },
    private val transportPolicy: DiagnosticsTransportPolicy = DiagnosticsTransportPolicy { _, _, _ -> true },
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
        // Capture before loading evidence so any identity mutation that races
        // this operation invalidates all post-network local bookkeeping.
        val operationGeneration = identityTransitions.generation.value
        val report = reports.load(reportId) ?: return DiagnosticsUploadDecision.KeptInvalid
        if (
            report.binding.destinationKind == DiagnosticsDestinationKind.HOSTED &&
            report.state.hostedRemoteShortId != null
        ) {
            return try {
                pollHostedStatus(report, operationGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                runCatching {
                    reports.markHostedProcessing(report.id, checkNotNull(report.state.hostedRemoteShortId))
                }
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
        val beforeBase = identity.resolveForUpload(requirePersistentCapture = true)
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

        val afterBase = identity.resolveForUpload(requirePersistentCapture = true)
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

        val exactSelfHostedAuthorization = if (
            report.binding.destinationKind == DiagnosticsDestinationKind.SELF_HOSTED
        ) {
            val authorization = try {
                selfHostedAuthorization.current()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            } ?: return DiagnosticsUploadDecision.KeptUnavailable
            if (!authorization.matches(after, operationGeneration)) {
                return DiagnosticsUploadDecision.KeptIdentityChanged
            }
            authorization
        } else {
            null
        }

        val decision = try {
            when (report.binding.destinationKind) {
                DiagnosticsDestinationKind.HOSTED ->
                    uploadHosted(report, bundle, after, operationGeneration, requireAlwaysConsent)
                DiagnosticsDestinationKind.SELF_HOSTED ->
                    uploadSelfHosted(
                        report,
                        bundle,
                        after,
                        checkNotNull(exactSelfHostedAuthorization),
                        operationGeneration,
                        requireAlwaysConsent,
                    )
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
        expectedIdentity: DiagnosticsCaptureContext,
        authorization: DiagnosticsUploadAuthorization,
        operationGeneration: Long,
        requireAlwaysConsent: Boolean,
    ): DiagnosticsUploadDecision {
        val uploadAttempt = identityTransitions.withCurrentGeneration(operationGeneration) {
            privacyBarrier.withTransport {
                when {
                    !report.canUploadUnder(expectedIdentity) ||
                        !authorization.matches(expectedIdentity, operationGeneration) ->
                        SelfHostedUploadAttempt.IdentityChanged
                    !transportPolicy.permits(
                        report.binding,
                        expectedIdentity.noticeVersion,
                        requireAlwaysConsent,
                    ) -> SelfHostedUploadAttempt.Revoked
                    reports.load(report.id) == null -> SelfHostedUploadAttempt.ReportRemoved
                    else -> {
                        // Keep the request bound to the exact identity that approved
                        // this report. Privacy and identity revocations either happen
                        // before this lease and prevent the POST, or wait for it.
                        SelfHostedUploadAttempt.Sent(
                            api.upload(
                                bundle.manifestBytes,
                                bundle.bytes,
                                report.binding.profileId,
                                authorization,
                            ),
                        )
                    }
                }
            }
        } ?: return DiagnosticsUploadDecision.KeptIdentityChanged
        val result = when (uploadAttempt) {
            SelfHostedUploadAttempt.IdentityChanged -> return DiagnosticsUploadDecision.KeptIdentityChanged
            SelfHostedUploadAttempt.Revoked -> return DiagnosticsUploadDecision.KeptConsentReviewRequired
            SelfHostedUploadAttempt.ReportRemoved -> return DiagnosticsUploadDecision.KeptInvalid
            is SelfHostedUploadAttempt.Sent -> uploadAttempt.result
        }
        return when (result) {
            is DiagnosticsUploadResult.Success -> {
                val finalized = identityTransitions.withCurrentGeneration(operationGeneration) {
                    privacyBarrier.withTransport {
                        if (
                            !transportPolicy.permits(
                                report.binding,
                                expectedIdentity.noticeVersion,
                                requireAlwaysConsent,
                            ) || reports.load(report.id) == null
                        ) {
                            false
                        } else {
                            reports.delete(report.id)
                            runCatching {
                                sentRecorder.record(report.binding.binding, result.response.shortId, nowMs(), "ready")
                            }
                            true
                        }
                    }
                }
                if (finalized != true) {
                    DiagnosticsUploadDecision.KeptIdentityChanged
                } else {
                    DiagnosticsUploadDecision.Uploaded(result.response.shortId)
                }
            }
            is DiagnosticsUploadResult.NetworkError -> {
                markRetryable(report.id, "network")
                DiagnosticsUploadDecision.KeptRetryable
            }
            is DiagnosticsUploadResult.Failure -> if (result.code == DiagnosticsErrorCode.UNAUTHORIZED) {
                // The leased exact-scope request deliberately suppresses auth
                // refresh to avoid re-entering the identity barrier. A normal
                // preflight on the next attempt may refresh before send.
                markRetryable(report.id, result.code.wire)
                DiagnosticsUploadDecision.KeptRetryable
            } else {
                mapServerError(report, result, expectedIdentity.noticeVersion)
            }
        }
    }

    private suspend fun uploadHosted(
        report: PendingReport,
        bundle: DiagnosticsBundle,
        expectedIdentity: DiagnosticsCaptureContext,
        operationGeneration: Long,
        requireAlwaysConsent: Boolean,
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
        return uploadHosted(
            report,
            bundle,
            wireReportId,
            credentials,
            expectedIdentity,
            operationGeneration,
            requireAlwaysConsent,
        )
    }

    private suspend fun uploadHosted(
        report: PendingReport,
        bundle: DiagnosticsBundle,
        wireReportId: String,
        credentials: HostedDiagnosticsCredentials,
        expectedIdentity: DiagnosticsCaptureContext,
        operationGeneration: Long,
        requireAlwaysConsent: Boolean,
    ): DiagnosticsUploadDecision {
        val hostedApi = hostedApi ?: return DiagnosticsUploadDecision.KeptUnavailable
        val createAttempt = identityTransitions.withCurrentGeneration(operationGeneration) {
            privacyBarrier.withTransport {
                when {
                    !report.canUploadUnder(expectedIdentity) -> HostedCreateAttempt.IdentityChanged
                    !transportPolicy.permits(
                        report.binding,
                        expectedIdentity.noticeVersion,
                        requireAlwaysConsent,
                    ) -> HostedCreateAttempt.Revoked
                    reports.load(report.id) == null -> HostedCreateAttempt.ReportRemoved
                    else -> HostedCreateAttempt.Sent(
                        hostedApi.createReport(
                            installationToken = credentials.installationToken,
                            request = HostedDiagnosticsCreateReportRequest(
                                reportId = wireReportId,
                                manifest = bundle.manifest,
                                bundleBytes = bundle.bytes.size.toLong(),
                                bundleSha256 = bundle.manifest.archive.sha256,
                            ),
                        ),
                    )
                }
            }
        } ?: return DiagnosticsUploadDecision.KeptIdentityChanged
        val createResult = when (createAttempt) {
            HostedCreateAttempt.IdentityChanged -> return DiagnosticsUploadDecision.KeptIdentityChanged
            HostedCreateAttempt.Revoked -> return DiagnosticsUploadDecision.KeptConsentReviewRequired
            HostedCreateAttempt.ReportRemoved -> return DiagnosticsUploadDecision.KeptInvalid
            is HostedCreateAttempt.Sent -> createAttempt.result
        }
        val created = when (val result = createResult) {
            is HostedDiagnosticsApiResult.Success -> result.value
            is HostedDiagnosticsApiResult.Failure -> {
                if (result.errorCode == "report_conflict") {
                    return reconcileHostedConflict(
                        report = report,
                        wireReportId = wireReportId,
                        credentials = credentials,
                        expectedIdentity = expectedIdentity,
                        operationGeneration = operationGeneration,
                        requireAlwaysConsent = requireAlwaysConsent,
                    )
                }
                if (result.errorCode == "invalid_installation_token") {
                    runCatching { hostedInstallations?.recoverIfInvalid(credentials) }
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
        val uploadAttempt = identityTransitions.withCurrentGeneration(operationGeneration) {
            privacyBarrier.withTransport {
                when {
                    !report.canUploadUnder(expectedIdentity) -> HostedUploadAttempt.IdentityChanged
                    !transportPolicy.permits(
                        report.binding,
                        expectedIdentity.noticeVersion,
                        requireAlwaysConsent,
                    ) -> HostedUploadAttempt.Revoked
                    reports.load(report.id) == null -> HostedUploadAttempt.ReportRemoved
                    else -> {
                        // Starting the full-bundle PUT is itself a privacy boundary.
                        // A revocation either prevents it or waits for it to finish.
                        HostedUploadAttempt.Sent(
                            hostedApi.uploadBundle(
                                installationToken = credentials.installationToken,
                                reportId = wireReportId,
                                uploadToken = created.uploadToken,
                                bundle = bundle.bytes,
                            ),
                        )
                    }
                }
            }
        } ?: return DiagnosticsUploadDecision.KeptIdentityChanged
        val uploaded = when (uploadAttempt) {
            HostedUploadAttempt.IdentityChanged -> return DiagnosticsUploadDecision.KeptIdentityChanged
            HostedUploadAttempt.Revoked -> return DiagnosticsUploadDecision.KeptConsentReviewRequired
            HostedUploadAttempt.ReportRemoved -> return DiagnosticsUploadDecision.KeptInvalid
            is HostedUploadAttempt.Sent -> uploadAttempt.result
        }
        val uploadReceipt = when (uploaded) {
            is HostedDiagnosticsApiResult.Success -> uploaded.value
            is HostedDiagnosticsApiResult.Failure -> {
                if (uploaded.errorCode == "invalid_installation_token") {
                    runCatching { hostedInstallations?.recoverIfInvalid(credentials) }
                }
                return mapHostedError(report, uploaded)
            }
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
        if (
            identityTransitions.withCurrentGeneration(operationGeneration) {
                privacyBarrier.withTransport {
                    if (
                        !transportPolicy.permits(
                            report.binding,
                            expectedIdentity.noticeVersion,
                            requireAlwaysConsent,
                        ) || reports.load(report.id) == null
                    ) {
                        false
                    } else {
                        reports.markHostedProcessing(report.id, uploadShortId)
                        true
                    }
                }
            } != true
        ) {
            return DiagnosticsUploadDecision.KeptIdentityChanged
        }

        val statusAttempt = identityTransitions.withCurrentGeneration(operationGeneration) {
            privacyBarrier.withTransport {
                when {
                    !transportPolicy.permits(
                        report.binding,
                        expectedIdentity.noticeVersion,
                        requireAlwaysConsent,
                    ) -> HostedStatusAttempt.Revoked
                    reports.load(report.id) == null -> HostedStatusAttempt.ReportRemoved
                    else -> HostedStatusAttempt.Sent(
                        hostedApi.reportStatus(credentials.installationToken, wireReportId),
                    )
                }
            }
        } ?: return DiagnosticsUploadDecision.KeptIdentityChanged
        val statusResult = when (statusAttempt) {
            HostedStatusAttempt.Revoked -> return DiagnosticsUploadDecision.KeptConsentReviewRequired
            HostedStatusAttempt.ReportRemoved -> return DiagnosticsUploadDecision.KeptInvalid
            is HostedStatusAttempt.Sent -> statusAttempt.result
        }
        val state = when (val status = statusResult) {
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
            val finalized = identityTransitions.withCurrentGeneration(operationGeneration) {
                privacyBarrier.withTransport {
                    if (
                        !transportPolicy.permits(
                            report.binding,
                            expectedIdentity.noticeVersion,
                            requireAlwaysConsent,
                        ) || reports.load(report.id) == null
                    ) {
                        false
                    } else {
                        reports.recordHostedReadyAndDelete(report.id, report.binding, uploadShortId)
                        runCatching {
                            sentRecorder.record(report.binding.binding, uploadShortId, nowMs(), state.wireValue)
                        }
                        true
                    }
                }
            }
            return if (finalized != true) {
                DiagnosticsUploadDecision.KeptIdentityChanged
            } else {
                DiagnosticsUploadDecision.Uploaded(uploadShortId, state)
            }
        }
        return DiagnosticsUploadDecision.HostedProcessing(uploadShortId)
    }

    private suspend fun pollHostedStatus(
        report: PendingReport,
        operationGeneration: Long,
    ): DiagnosticsUploadDecision {
        val expectedShortId = report.state.hostedRemoteShortId ?: return DiagnosticsUploadDecision.KeptRetryable
        val wireReportId = report.id.toHostedWireReportIdOrNull() ?: run {
            markPermanent(report.id, "invalid_report_id")
            return DiagnosticsUploadDecision.KeptInvalid
        }
        val credentials = hostedInstallations?.credentialsForOutstanding()?.firstOrNull() ?: run {
            markRetryable(report.id, "installation_unavailable")
            return DiagnosticsUploadDecision.KeptRetryable
        }
        val api = hostedApi ?: return DiagnosticsUploadDecision.KeptUnavailable
        val statusAttempt = identityTransitions.withCurrentGeneration(operationGeneration) {
            privacyBarrier.withTransport {
                when {
                    !transportPolicy.permits(
                        report.binding,
                        report.manifest.consent.noticeVersion,
                        false,
                    ) -> HostedStatusAttempt.Revoked
                    reports.load(report.id) == null -> HostedStatusAttempt.ReportRemoved
                    else -> HostedStatusAttempt.Sent(
                        reportHostedStatusWithFallback(api, credentials, wireReportId),
                    )
                }
            }
        } ?: return DiagnosticsUploadDecision.KeptIdentityChanged
        val result = when (statusAttempt) {
            HostedStatusAttempt.Revoked -> return DiagnosticsUploadDecision.KeptConsentReviewRequired
            HostedStatusAttempt.ReportRemoved -> return DiagnosticsUploadDecision.KeptInvalid
            is HostedStatusAttempt.Sent -> statusAttempt.result
        }
        return when (result) {
            is HostedDiagnosticsApiResult.NetworkError -> {
                reports.markHostedProcessing(report.id, expectedShortId)
                DiagnosticsUploadDecision.KeptRetryable
            }
            is HostedDiagnosticsApiResult.Failure -> {
                if (result.errorCode == "invalid_installation_token") {
                    runCatching { hostedInstallations?.recoverIfInvalid(credentials) }
                }
                reports.markHostedProcessing(report.id, expectedShortId)
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
                        val finalized = identityTransitions.withCurrentGeneration(operationGeneration) {
                            privacyBarrier.withTransport {
                                if (
                                    !transportPolicy.permits(
                                        report.binding,
                                        report.manifest.consent.noticeVersion,
                                        false,
                                    ) || reports.load(report.id) == null
                                ) {
                                    false
                                } else {
                                    reports.recordHostedReadyAndDelete(report.id, report.binding, expectedShortId)
                                    runCatching {
                                        sentRecorder.record(report.binding.binding, expectedShortId, nowMs(), "ready")
                                    }
                                    true
                                }
                            }
                        }
                        if (finalized != true) {
                            DiagnosticsUploadDecision.KeptIdentityChanged
                        } else {
                            DiagnosticsUploadDecision.Uploaded(expectedShortId)
                        }
                    }
                    HostedDiagnosticsReportState.PROCESSING -> {
                        val finalized = identityTransitions.withCurrentGeneration(operationGeneration) {
                            privacyBarrier.withTransport {
                                if (
                                    !transportPolicy.permits(
                                        report.binding,
                                        report.manifest.consent.noticeVersion,
                                        false,
                                    ) || reports.load(report.id) == null
                                ) {
                                    false
                                } else {
                                    reports.markHostedProcessing(report.id, expectedShortId)
                                    true
                                }
                            }
                        }
                        if (finalized != true) {
                            DiagnosticsUploadDecision.KeptIdentityChanged
                        } else {
                            DiagnosticsUploadDecision.HostedProcessing(expectedShortId)
                        }
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

    private suspend fun reconcileHostedConflict(
        report: PendingReport,
        wireReportId: String,
        credentials: HostedDiagnosticsCredentials,
        expectedIdentity: DiagnosticsCaptureContext,
        operationGeneration: Long,
        requireAlwaysConsent: Boolean,
    ): DiagnosticsUploadDecision {
        val api = hostedApi ?: return DiagnosticsUploadDecision.KeptUnavailable
        val statusAttempt = identityTransitions.withCurrentGeneration(operationGeneration) {
            privacyBarrier.withTransport {
                when {
                    !report.canUploadUnder(expectedIdentity) -> HostedStatusAttempt.Revoked
                    !transportPolicy.permits(
                        report.binding,
                        expectedIdentity.noticeVersion,
                        requireAlwaysConsent,
                    ) -> HostedStatusAttempt.Revoked
                    reports.load(report.id) == null -> HostedStatusAttempt.ReportRemoved
                    else -> HostedStatusAttempt.Sent(
                        reportHostedStatusWithFallback(api, credentials, wireReportId),
                    )
                }
            }
        } ?: return DiagnosticsUploadDecision.KeptIdentityChanged
        val result = when (statusAttempt) {
            HostedStatusAttempt.Revoked -> return DiagnosticsUploadDecision.KeptConsentReviewRequired
            HostedStatusAttempt.ReportRemoved -> return DiagnosticsUploadDecision.KeptInvalid
            is HostedStatusAttempt.Sent -> statusAttempt.result
        }
        if (result is HostedDiagnosticsApiResult.Failure && result.errorCode == "invalid_installation_token") {
            runCatching { hostedInstallations?.recoverIfInvalid(credentials) }
        }
        if (result !is HostedDiagnosticsApiResult.Success) {
            markRetryable(report.id, "report_conflict")
            return DiagnosticsUploadDecision.KeptRetryable
        }
        val status = result.value
        val shortId = status.shortId?.takeIf(String::isNotBlank)
        if (status.reportId != wireReportId || shortId == null) {
            markRetryable(report.id, "report_conflict")
            return DiagnosticsUploadDecision.KeptRetryable
        }
        return when (status.state) {
            HostedDiagnosticsReportState.PROCESSING -> {
                val finalized = identityTransitions.withCurrentGeneration(operationGeneration) {
                    privacyBarrier.withTransport {
                        if (
                            !transportPolicy.permits(
                                report.binding,
                                expectedIdentity.noticeVersion,
                                requireAlwaysConsent,
                            ) || reports.load(report.id) == null
                        ) {
                            false
                        } else {
                            reports.markHostedProcessing(report.id, shortId)
                            true
                        }
                    }
                }
                if (finalized == true) {
                    DiagnosticsUploadDecision.HostedProcessing(shortId)
                } else {
                    DiagnosticsUploadDecision.KeptIdentityChanged
                }
            }
            HostedDiagnosticsReportState.READY -> {
                val finalized = identityTransitions.withCurrentGeneration(operationGeneration) {
                    privacyBarrier.withTransport {
                        if (
                            !transportPolicy.permits(
                                report.binding,
                                expectedIdentity.noticeVersion,
                                requireAlwaysConsent,
                            ) || reports.load(report.id) == null
                        ) {
                            false
                        } else {
                            reports.recordHostedReadyAndDelete(report.id, report.binding, shortId)
                            runCatching {
                                sentRecorder.record(
                                    report.binding.binding,
                                    shortId,
                                    nowMs(),
                                    status.state.wireValue,
                                )
                            }
                            true
                        }
                    }
                }
                if (finalized == true) {
                    DiagnosticsUploadDecision.Uploaded(shortId, status.state)
                } else {
                    DiagnosticsUploadDecision.KeptIdentityChanged
                }
            }
            HostedDiagnosticsReportState.REJECTED,
            HostedDiagnosticsReportState.DELETING,
            HostedDiagnosticsReportState.DELETED,
            -> {
                markPermanent(report.id, status.errorCode ?: status.state.wireValue)
                DiagnosticsUploadDecision.KeptInvalid
            }
            HostedDiagnosticsReportState.RECEIVING,
            HostedDiagnosticsReportState.UPLOADED,
            -> {
                markRetryable(report.id, "report_conflict")
                DiagnosticsUploadDecision.KeptRetryable
            }
        }
    }

    private suspend fun reportHostedStatusWithFallback(
        api: HostedDiagnosticsApi,
        preferred: HostedDiagnosticsCredentials,
        wireReportId: String,
    ): HostedDiagnosticsApiResult<HostedDiagnosticsReportStatusResponse> {
        var lastResult: HostedDiagnosticsApiResult<HostedDiagnosticsReportStatusResponse>? = null
        hostedInstallations?.credentialsForOutstanding(preferred).orEmpty().forEach { credentials ->
            val result = api.reportStatus(credentials.installationToken, wireReportId)
            if (result is HostedDiagnosticsApiResult.Success) return result
            lastResult = result
        }
        return checkNotNull(lastResult) { "hosted status requires installation credentials" }
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
                reports.setRetryAfterDeadlineForReport(
                    report.id,
                    report.binding.binding,
                    nowMs() + seconds * 1_000L,
                )
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
                reports.setRetryAfterDeadlineForReport(
                    report.id,
                    report.binding.binding,
                    nowMs() + seconds * 1_000L,
                )
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
            "invalid_installation_token",
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
            "upload_attempt_limit_exceeded",
            "unsupported_media_type",
            "size_mismatch",
        )
    }

    private sealed interface HostedCreateAttempt {
        data object IdentityChanged : HostedCreateAttempt
        data object Revoked : HostedCreateAttempt
        data object ReportRemoved : HostedCreateAttempt
        data class Sent(
            val result: HostedDiagnosticsApiResult<org.siloserver.silo.network.api.HostedDiagnosticsCreateReportResponse>,
        ) : HostedCreateAttempt
    }

    private sealed interface SelfHostedUploadAttempt {
        data object IdentityChanged : SelfHostedUploadAttempt
        data object Revoked : SelfHostedUploadAttempt
        data object ReportRemoved : SelfHostedUploadAttempt
        data class Sent(val result: DiagnosticsUploadResult) : SelfHostedUploadAttempt
    }

    private sealed interface HostedUploadAttempt {
        data object IdentityChanged : HostedUploadAttempt
        data object Revoked : HostedUploadAttempt
        data object ReportRemoved : HostedUploadAttempt
        data class Sent(
            val result: HostedDiagnosticsApiResult<HostedDiagnosticsReportStatusResponse>,
        ) : HostedUploadAttempt
    }

    private sealed interface HostedStatusAttempt {
        data object Revoked : HostedStatusAttempt
        data object ReportRemoved : HostedStatusAttempt
        data class Sent(
            val result: HostedDiagnosticsApiResult<HostedDiagnosticsReportStatusResponse>,
        ) : HostedStatusAttempt
    }
}

private fun DiagnosticsUploadAuthorization.matches(
    context: DiagnosticsCaptureContext,
    expectedGeneration: Long,
): Boolean =
    identityGeneration == expectedGeneration &&
        context.ownershipGeneration == expectedGeneration &&
        context.localServerId?.let { it == serverId } == true &&
        activeProfileId == context.profileId

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
