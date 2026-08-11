package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsArchive
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsConsent
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode as ManifestConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsDestination
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsErrorCode
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsLogSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReport
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResponse
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResult
import org.siloserver.silo.network.api.DiagnosticsApi
import org.siloserver.silo.network.api.HostedDiagnosticsApi
import org.siloserver.silo.network.api.HostedDiagnosticsApiResult
import org.siloserver.silo.network.api.HostedDiagnosticsAvailability
import org.siloserver.silo.network.api.HostedDiagnosticsCapabilities
import org.siloserver.silo.network.api.HostedDiagnosticsCreateReportRequest
import org.siloserver.silo.network.api.HostedDiagnosticsCreateReportResponse
import org.siloserver.silo.network.api.HostedDiagnosticsInstallationRequest
import org.siloserver.silo.network.api.HostedDiagnosticsInstallationResponse
import org.siloserver.silo.network.api.HostedDiagnosticsReportState
import org.siloserver.silo.network.api.HostedDiagnosticsReportStatusResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsUploaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun hostedWireIdCanonicalizesTheLocalUuidWithoutChangingItsIdentity() {
        assertEquals(
            "01234567-89ab-4def-8123-456789abcdef",
            "0123456789ab4def8123456789abcdef".toHostedWireReportIdOrNull(),
        )
        assertNull("not-a-local-report-id".toHostedWireReportIdOrNull())
    }

    @Test
    fun profileSwitchDuringBuildPreventsPost() = runTest {
        val fixture = fixture()
        fixture.builder.onBuild = {
            fixture.identity.current = fixture.identity.current?.copy(profileId = "other")
        }

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptIdentityChanged, decision)
        assertEquals(0, fixture.api.uploadCalls)
        assertNotNull(fixture.store.load(fixture.report.id))
    }

    @Test
    fun consentNoticeChangeDuringBuildPreventsPost() = runTest {
        val fixture = fixture()
        fixture.builder.onBuild = {
            fixture.identity.current = fixture.identity.current?.copy(noticeVersion = 3)
        }

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptConsentReviewRequired, decision)
        assertEquals(0, fixture.api.uploadCalls)
        val report = assertNotNull(fixture.store.load(fixture.report.id))
        assertEquals(PendingReportStatus.PENDING, report.state.status)
    }

    @Test
    fun promptApprovedNoticeChangeBeforePreflightPreventsPost() = runTest {
        val fixture = fixture()
        fixture.identity.current = fixture.identity.current?.copy(noticeVersion = 3)

        val decision = fixture.uploader.upload(fixture.report.id, expectedNoticeVersion = 2)

        assertEquals(DiagnosticsUploadDecision.KeptConsentReviewRequired, decision)
        assertEquals(0, fixture.api.uploadCalls)
    }

    @Test
    fun automaticUploadRequestsReviewWhenAlwaysConsentIsDemotedDuringBuild() = runTest {
        val fixture = fixture()
        fixture.builder.onBuild = { fixture.consent.mode = DiagnosticsConsentMode.ASK }

        val decision = fixture.uploader.uploadAutomatically(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptConsentReviewRequired, decision)
        assertEquals(0, fixture.api.uploadCalls)
        assertNotNull(fixture.store.load(fixture.report.id))
    }

    @Test
    fun automaticUploadRequestsReviewWhenConsentIsAlreadyAsk() = runTest {
        val fixture = fixture()
        fixture.consent.mode = DiagnosticsConsentMode.ASK

        val decision = fixture.uploader.uploadAutomatically(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptConsentReviewRequired, decision)
        assertEquals(0, fixture.api.uploadCalls)
    }

    @Test
    fun successfulUploadUsesCapturedProfileRecordsHistoryAndDeletesReport() = runTest {
        val fixture = fixture()
        fixture.api.result = DiagnosticsUploadResult.Success(DiagnosticsUploadResponse("report-1", "ABC123"))

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.Uploaded("ABC123"), decision)
        assertEquals("profile-1", fixture.api.capturedProfileId)
        assertEquals(listOf("ABC123"), fixture.sent.shortIds)
        assertNull(fixture.store.load(fixture.report.id))
    }

    @Test
    fun anotherEligibleProfileOnTheSameAccountCanSendWithCapturedAttribution() = runTest {
        val fixture = fixture()
        fixture.identity.current = fixture.identity.current?.copy(
            profileId = "profile-2",
            ownershipGeneration = 8,
        )
        fixture.api.result = DiagnosticsUploadResult.Success(DiagnosticsUploadResponse("report-1", "ABC123"))

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.Uploaded("ABC123"), decision)
        assertEquals("profile-1", fixture.api.capturedProfileId)
    }

    @Test
    fun manualReportCanBeExplicitlySentUnderNeverConsent() = runTest {
        val fixture = fixture()
        fixture.consent.mode = DiagnosticsConsentMode.NEVER
        fixture.api.result = DiagnosticsUploadResult.Success(DiagnosticsUploadResponse("report-1", "ABC123"))

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.Uploaded("ABC123"), decision)
        assertEquals(ManifestConsentMode.MANUAL, fixture.api.capturedManifest?.consent?.mode)
    }

    @Test
    fun retryAfterIsPersistedAndPreventsAnotherNetworkAttempt() = runTest {
        val fixture = fixture()
        fixture.api.result = DiagnosticsUploadResult.Failure(
            code = DiagnosticsErrorCode.QUOTA_EXCEEDED,
            httpStatus = 429,
            retryAfterSeconds = 120,
        )

        assertEquals(DiagnosticsUploadDecision.KeptRetryable, fixture.uploader.upload(fixture.report.id))
        assertEquals(1, fixture.api.uploadCalls)
        assertNotNull(fixture.store.retryAfterDeadline(BINDING))

        assertEquals(DiagnosticsUploadDecision.KeptRetryable, fixture.uploader.upload(fixture.report.id))
        assertEquals(1, fixture.api.uploadCalls)
    }

    @Test
    fun successfulInFlightUploadDoesNotClearANewerRetryAfterDeadline() = runTest {
        val fixture = fixture()
        fixture.api.result = DiagnosticsUploadResult.Success(DiagnosticsUploadResponse("report-1", "ABC123"))
        fixture.api.onUpload = {
            fixture.store.setRetryAfterDeadline(BINDING, CAPTURED_AT + 121_000)
        }

        assertEquals(DiagnosticsUploadDecision.Uploaded("ABC123"), fixture.uploader.upload(fixture.report.id))
        assertEquals(CAPTURED_AT + 121_000, fixture.store.retryAfterDeadline(BINDING))
    }

    @Test
    fun nonRetryableResponseDoesNotPersistRetryAfter() = runTest {
        val fixture = fixture()
        fixture.api.result = DiagnosticsUploadResult.Failure(
            code = DiagnosticsErrorCode.TOO_LARGE,
            httpStatus = 413,
            retryAfterSeconds = 120,
        )

        assertEquals(DiagnosticsUploadDecision.KeptTooLarge, fixture.uploader.upload(fixture.report.id))
        assertNull(fixture.store.retryAfterDeadline(BINDING))
    }

    @Test
    fun reportCapturedBeforeProcessRestartUploadsForTheSameIdentity() = runTest {
        val fixture = fixture()
        fixture.identity.current = fixture.identity.current?.copy(ownershipGeneration = 0)
        fixture.api.result = DiagnosticsUploadResult.Success(DiagnosticsUploadResponse("report-1", "ABC123"))

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.Uploaded("ABC123"), decision)
        assertEquals(1, fixture.api.uploadCalls)
        assertNull(fixture.store.load(fixture.report.id))
    }

    @Test
    fun unsupportedSchemaMarksServerUpdateRequired() = runTest {
        val fixture = fixture()
        fixture.api.result = DiagnosticsUploadResult.Failure(DiagnosticsErrorCode.UNSUPPORTED_SCHEMA, 400)

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptServerUpdateRequired, decision)
        val report = assertNotNull(fixture.store.load(fixture.report.id))
        assertEquals(PendingReportStatus.PERMANENT_FAILURE, report.state.status)
        assertEquals("unsupported_schema", report.state.errorCode)
    }

    @Test
    fun bundleOverServerLimitNeverPosts() = runTest {
        val fixture = fixture(maxBundleBytes = 4)
        fixture.builder.bundleBytes = ByteArray(5)

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptTooLarge, decision)
        assertEquals(0, fixture.api.uploadCalls)
    }

    @Test
    fun everyStableServerErrorHasExplicitPolicy() = runTest {
        val cases = mapOf(
            "busy" to DiagnosticsUploadDecision.KeptRetryable,
            "quota_exceeded" to DiagnosticsUploadDecision.KeptRetryable,
            "rate_limited" to DiagnosticsUploadDecision.KeptRetryable,
            "internal_error" to DiagnosticsUploadDecision.KeptRetryable,
            "too_large" to DiagnosticsUploadDecision.KeptTooLarge,
            "unsupported_schema" to DiagnosticsUploadDecision.KeptServerUpdateRequired,
            "storage_unavailable" to DiagnosticsUploadDecision.KeptUnavailable,
            "disabled" to DiagnosticsUploadDecision.KeptUnavailable,
            "diagnostics_disabled" to DiagnosticsUploadDecision.KeptUnavailable,
            "destination_mismatch" to DiagnosticsUploadDecision.KeptIdentityChanged,
            "profile_mismatch" to DiagnosticsUploadDecision.KeptIdentityChanged,
            "child_profile_forbidden" to DiagnosticsUploadDecision.KeptIdentityChanged,
            "invalid_bundle" to DiagnosticsUploadDecision.KeptInvalid,
            "invalid_archive" to DiagnosticsUploadDecision.KeptInvalid,
            "invalid_manifest" to DiagnosticsUploadDecision.KeptInvalid,
            "archive_mismatch" to DiagnosticsUploadDecision.KeptInvalid,
            "stale_report" to DiagnosticsUploadDecision.KeptInvalid,
            "stale_consent" to DiagnosticsUploadDecision.KeptConsentReviewRequired,
            "unauthorized" to DiagnosticsUploadDecision.KeptInvalid,
            "api_key_not_allowed" to DiagnosticsUploadDecision.KeptInvalid,
            "forbidden" to DiagnosticsUploadDecision.KeptInvalid,
        )

        cases.forEach { (code, expected) ->
            val fixture = fixture()
            val httpStatus = if (code in setOf("busy", "internal_error")) 503 else 400
            fixture.api.result = DiagnosticsUploadResult.Failure(
                code = DiagnosticsErrorCode.fromWire(code),
                httpStatus = httpStatus,
            )

            assertEquals(expected, fixture.uploader.upload(fixture.report.id), code)
            val state = assertNotNull(fixture.store.load(fixture.report.id)).state
            val expectedStatus = if (
                expected in setOf(
                    DiagnosticsUploadDecision.KeptRetryable,
                    DiagnosticsUploadDecision.KeptUnavailable,
                    DiagnosticsUploadDecision.KeptIdentityChanged,
                    DiagnosticsUploadDecision.KeptConsentReviewRequired,
                )
            ) {
                PendingReportStatus.RETRYABLE
            } else {
                PendingReportStatus.PERMANENT_FAILURE
            }
            assertEquals(expectedStatus, state.status, code)
            assertEquals(DiagnosticsErrorCode.fromWire(code).wire, state.errorCode, code)
        }
    }

    @Test
    fun staleConsentDemotesAlwaysAndKeepsTheReportForReview() = runTest {
        val fixture = fixture()
        fixture.api.result = DiagnosticsUploadResult.Failure(DiagnosticsErrorCode.STALE_CONSENT, 409)

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptConsentReviewRequired, decision)
        assertEquals(listOf(BINDING to 2), fixture.staleConsent.demotions)
        assertNotNull(fixture.store.load(fixture.report.id))
    }

    @Test
    fun staleConsentPersistenceFailureRemainsRetryable() = runTest {
        val fixture = fixture()
        fixture.staleConsent.failure = IllegalStateException("storage unavailable")
        fixture.api.result = DiagnosticsUploadResult.Failure(DiagnosticsErrorCode.STALE_CONSENT, 409)

        val decision = fixture.uploader.upload(fixture.report.id)

        assertEquals(DiagnosticsUploadDecision.KeptRetryable, decision)
        assertEquals(PendingReportStatus.RETRYABLE, assertNotNull(fixture.store.load(fixture.report.id)).state.status)
    }

    @Test
    fun hostedProcessingIsRetainedAndPolledBeforeCapabilityOrAutomaticConsentGates() = runTest {
        val fixture = hostedFixture()

        assertEquals(
            DiagnosticsUploadDecision.KeptConsentReviewRequired,
            fixture.uploader.uploadAutomatically(fixture.report.id),
        )
        assertEquals(0, fixture.api.capabilitiesCalls)
        assertTrue(fixture.api.createdRequests.isEmpty())

        assertEquals(DiagnosticsUploadDecision.KeptRetryable, fixture.uploader.upload(fixture.report.id))
        val processing = assertNotNull(fixture.store.load(fixture.report.id))
        assertEquals("ABC123", processing.state.hostedRemoteShortId)
        assertEquals("processing", processing.state.errorCode)
        assertTrue(fixture.sent.shortIds.isEmpty(), "processing is not a durable success for the user")

        fixture.api.capabilities = fixture.api.capabilities.copy(status = HostedDiagnosticsAvailability.DISABLED)
        fixture.api.reportStatusResultOverride = HostedDiagnosticsApiResult.Success(
            fixture.api.status(fixture.report, HostedDiagnosticsReportState.READY),
        )
        assertEquals(
            DiagnosticsUploadDecision.Uploaded("ABC123", "ready"),
            fixture.uploader.uploadAutomatically(fixture.report.id),
        )
        assertEquals(1, fixture.api.capabilitiesCalls, "an accepted report must poll before live capability gating")
        assertEquals(listOf("ready"), fixture.sent.states)
        assertNull(fixture.store.load(fixture.report.id))
        assertEquals(fixture.report.binding.binding, fixture.store.hostedReadyBinding(fixture.report.id))
    }

    @Test
    fun hostedProcessingReadyRaceWithDeleteKeepsIntentUntilRemoteErasure() = runTest {
        val fixture = hostedFixture()
        assertEquals(DiagnosticsUploadDecision.KeptRetryable, fixture.uploader.upload(fixture.report.id))
        val statusStarted = CompletableDeferred<Unit>()
        val releaseStatus = CompletableDeferred<Unit>()
        fixture.api.beforeReportStatus = {
            statusStarted.complete(Unit)
            releaseStatus.await()
        }
        fixture.api.reportStatusResultOverride = HostedDiagnosticsApiResult.Success(
            fixture.api.status(fixture.report, HostedDiagnosticsReportState.READY),
        )

        val polling = async { fixture.uploader.uploadAutomatically(fixture.report.id) }
        statusStarted.await()
        fixture.store.stageHostedDeletionAndDelete(fixture.report.id)
        assertEquals(listOf(fixture.report.id), fixture.store.hostedDeletionIntents())
        releaseStatus.complete(Unit)

        assertEquals(DiagnosticsUploadDecision.Uploaded("ABC123", "ready"), polling.await())
        assertNull(fixture.store.load(fixture.report.id))
        assertEquals(listOf(fixture.report.id), fixture.store.hostedDeletionIntents())
        val deleter = DefaultHostedDiagnosticsReportDeleter(fixture.api, fixture.installations)
        assertTrue(deleter.delete(fixture.report.id))
        fixture.store.completeHostedDeletion(fixture.report.id)
        assertTrue(fixture.store.hostedDeletionIntents().isEmpty())
        assertEquals(listOf(requireNotNull(fixture.report.id.toHostedWireReportIdOrNull())), fixture.api.deleteReportIds)
        assertEquals(
            DiagnosticsUploadDecision.KeptInvalid,
            fixture.uploader.uploadAutomatically(fixture.report.id),
            "evidence covered by a winning deletion must never become re-uploadable",
        )
    }

    @Test
    fun hostedDeleteRetainsIntentWhenUuidIsLiveUnderAnotherInstallation() = runTest {
        val fixture = hostedFixture(
            credentials = HostedDiagnosticsCredentials("old-installation", "old-installation-token"),
        )
        fixture.api.deleteResult = HostedDiagnosticsApiResult.Failure(
            httpStatus = 404,
            errorCode = "report_not_found",
            message = "report is not owned by this installation",
        )
        val deleter = DefaultHostedDiagnosticsReportDeleter(fixture.api, fixture.installations)

        assertFalse(deleter.delete(fixture.report.id))

        assertEquals(listOf("old-installation-token"), fixture.api.deleteInstallationTokens)
        assertEquals(listOf(requireNotNull(fixture.report.id.toHostedWireReportIdOrNull())), fixture.api.deleteReportIds)
    }

    @Test
    fun hostedExactCreateEnvelopeIsFrozenUntilStaleConsentThenReframedFromSanitizedEvidence() = runTest {
        val fixture = hostedFixture(
            artifacts = mapOf(
                "device.json" to """{"token":"old-source-token","safe":"kept"}""".encodeToByteArray(),
            ),
            redactionValues = listOf("old-source-token"),
        )
        fixture.api.createReportNetworkErrorsRemaining = 1

        assertEquals(DiagnosticsUploadDecision.KeptRetryable, fixture.uploader.upload(fixture.report.id))
        val originalEnvelope = (fixture.store.loadHostedEnvelope(fixture.report.id) as HostedEnvelopeLoadResult.Available).bundle
        assertTrue(originalEnvelope.sanitizedEntries.values.none { it.decodeToString().contains("old-source-token") })
        assertEquals(1, fixture.redactionTokens.calls)

        fixture.redactionTokens.values = listOf("rotated-token-that-must-not-rebuild-evidence")
        fixture.api.capabilities = fixture.api.capabilities.copy(
            consentNoticeVersion = 2,
            maxBundleBytes = 1,
            maxManifestBytes = 1,
        )
        fixture.api.createReportFailure = HostedDiagnosticsApiResult.Failure(
            httpStatus = 409,
            errorCode = "stale_consent",
            message = "consent notice changed",
        )

        assertEquals(DiagnosticsUploadDecision.KeptConsentReviewRequired, fixture.uploader.upload(fixture.report.id))
        val retriedEnvelope = (fixture.store.loadHostedEnvelope(fixture.report.id) as HostedEnvelopeLoadResult.Available).bundle
        assertContentEquals(originalEnvelope.manifestBytes, retriedEnvelope.manifestBytes)
        assertContentEquals(originalEnvelope.bytes, retriedEnvelope.bytes)
        assertEquals(fixture.api.createdRequests[0], fixture.api.createdRequests[1])
        assertEquals(1, fixture.redactionTokens.calls, "an ambiguous exact retry must not read rotating secrets again")
        assertTrue(assertNotNull(fixture.store.load(fixture.report.id)).state.hostedConsentRefreshRequired)

        fixture.api.createReportFailure = null
        fixture.api.capabilities = fixture.api.capabilities.copy(
            maxBundleBytes = 10L * 1_024 * 1_024,
            maxManifestBytes = 64L * 1_024,
        )
        assertEquals(DiagnosticsUploadDecision.KeptRetryable, fixture.uploader.upload(fixture.report.id))
        val reframed = (fixture.store.loadHostedEnvelope(fixture.report.id) as HostedEnvelopeLoadResult.Available).bundle
        assertEquals(2, reframed.manifest.consent.noticeVersion)
        assertFalse(reframed.manifest.archive.sha256 == originalEnvelope.manifest.archive.sha256)
        assertTrue(reframed.sanitizedEntries.values.none { it.decodeToString().contains("rotated-token") })
        assertEquals(1, fixture.redactionTokens.calls, "reframing must use only the cached sanitized members")
        assertEquals(2, fixture.api.createdRequests[2].manifest.consent.noticeVersion)

        fixture.api.reportStatusResultOverride = HostedDiagnosticsApiResult.Success(
            fixture.api.status(fixture.report, HostedDiagnosticsReportState.READY),
        )
        assertEquals(
            DiagnosticsUploadDecision.Uploaded("ABC123", "ready"),
            fixture.uploader.uploadAutomatically(fixture.report.id),
        )
        assertNull(fixture.store.load(fixture.report.id))
    }

    @Test
    fun ambiguousHostedPutFailuresRetryTheExactCreateEnvelopeWithAFreshToken() = runTest {
        listOf(
            HostedDiagnosticsApiResult.Failure(401, "invalid_upload_token", "upload claim expired"),
            HostedDiagnosticsApiResult.Failure(409, "upload_cancelled", "stale claim was recovered"),
            HostedDiagnosticsApiResult.Failure(202, "invalid_response", "accepted receipt was malformed"),
        ).forEach { ambiguousFailure ->
            val fixture = hostedFixture()
            fixture.api.nextUploadToken = "expired-upload-token"
            fixture.api.uploadFailure = ambiguousFailure

            assertEquals(
                DiagnosticsUploadDecision.KeptRetryable,
                fixture.uploader.upload(fixture.report.id),
                ambiguousFailure.errorCode,
            )
            assertNotNull(fixture.store.load(fixture.report.id))

            fixture.api.uploadFailure = null
            fixture.api.nextUploadToken = "fresh-upload-token"
            fixture.api.reportStatusResultOverride = HostedDiagnosticsApiResult.Success(
                fixture.api.status(fixture.report, HostedDiagnosticsReportState.READY),
            )
            assertEquals(
                DiagnosticsUploadDecision.Uploaded("ABC123", "ready"),
                fixture.uploader.upload(fixture.report.id),
                ambiguousFailure.errorCode,
            )
            assertEquals(fixture.api.createdRequests[0], fixture.api.createdRequests[1])
            assertEquals(listOf("expired-upload-token", "fresh-upload-token"), fixture.api.uploadTokens)
            assertNull(fixture.store.load(fixture.report.id))
        }
    }

    @Test
    fun invalidHostedInstallationPreservesOwnershipCredentialAndRetryEnvelope() = runTest {
        val fixture = hostedFixture(
            credentials = HostedDiagnosticsCredentials("stale-installation", "stale-installation-token"),
        )
        fixture.api.invalidInstallationToken = "stale-installation-token"

        assertEquals(
            DiagnosticsUploadDecision.KeptRetryable,
            fixture.uploader.upload(fixture.report.id),
        )
        assertEquals(listOf("stale-installation-token"), fixture.api.createReportTokens)
        assertEquals(0, fixture.api.installationCreateCalls)
        assertNotNull(fixture.store.load(fixture.report.id)?.state?.hostedEnvelopeGeneration)
    }

    @Test
    fun hostedRejectedAndInternalWireStatesRetainLocalEvidence() = runTest {
        listOf(
            HostedDiagnosticsReportState.REJECTED to "privacy_artifact_rejected",
            HostedDiagnosticsReportState.UPLOADED to "invalid_response",
        ).forEach { (remoteState, expectedCode) ->
            val fixture = hostedFixture()
            assertEquals(DiagnosticsUploadDecision.KeptRetryable, fixture.uploader.upload(fixture.report.id))
            fixture.api.reportStatusResultOverride = HostedDiagnosticsApiResult.Success(
                fixture.api.status(
                    fixture.report,
                    remoteState,
                    errorCode = if (remoteState == HostedDiagnosticsReportState.REJECTED) expectedCode else null,
                ),
            )

            assertEquals(DiagnosticsUploadDecision.KeptInvalid, fixture.uploader.uploadAutomatically(fixture.report.id))
            val retained = assertNotNull(fixture.store.load(fixture.report.id))
            assertEquals(PendingReportStatus.PERMANENT_FAILURE, retained.state.status)
            assertEquals(expectedCode, retained.state.errorCode)
        }
    }

    @Test
    fun hostedExplicitReceiptIdentityMismatchIsPermanentAndRetainsLocalEvidence() = runTest {
        val fixture = hostedFixture()
        fixture.api.uploadReceiptOverride = HostedDiagnosticsReportStatusResponse(
            reportId = "11111111-1111-4111-8111-111111111111",
            shortId = "ABC123",
            state = HostedDiagnosticsReportState.PROCESSING,
        )

        assertEquals(DiagnosticsUploadDecision.KeptInvalid, fixture.uploader.upload(fixture.report.id))
        val retained = assertNotNull(fixture.store.load(fixture.report.id))
        assertEquals(PendingReportStatus.PERMANENT_FAILURE, retained.state.status)
        assertEquals("invalid_response", retained.state.errorCode)
        assertTrue(fixture.api.statusReportIds.isEmpty(), "a mismatched success receipt must not be polled")
    }

    @Test
    fun hostedPrivacyPolicyErrorsArePermanentButNeverDeleteLocalEvidence() = runTest {
        listOf(
            "hosted_consent_required",
            "privacy_artifact_rejected",
            "upload_attempt_limit_exceeded",
        ).forEach { errorCode ->
            val fixture = hostedFixture()
            fixture.api.createReportFailure = HostedDiagnosticsApiResult.Failure(
                httpStatus = 422,
                errorCode = errorCode,
                message = "collector rejected the envelope",
            )

            assertEquals(DiagnosticsUploadDecision.KeptInvalid, fixture.uploader.upload(fixture.report.id), errorCode)
            val retained = assertNotNull(fixture.store.load(fixture.report.id))
            assertEquals(PendingReportStatus.PERMANENT_FAILURE, retained.state.status)
            assertEquals(errorCode, retained.state.errorCode)
        }
    }

    @Test
    fun hostedManifestAndCompressionLimitsMapToTooLargeAndRetainLocalEvidence() = runTest {
        listOf(
            413 to "manifest_too_large",
            422 to "compression_ratio_exceeded",
        ).forEach { (httpStatus, errorCode) ->
            val fixture = hostedFixture()
            fixture.api.createReportFailure = HostedDiagnosticsApiResult.Failure(
                httpStatus = httpStatus,
                errorCode = errorCode,
                message = "collector size policy rejected the envelope",
            )

            assertEquals(DiagnosticsUploadDecision.KeptTooLarge, fixture.uploader.upload(fixture.report.id), errorCode)
            val retained = assertNotNull(fixture.store.load(fixture.report.id))
            assertEquals(PendingReportStatus.PERMANENT_FAILURE, retained.state.status)
            assertEquals(errorCode, retained.state.errorCode)
        }
    }

    private fun hostedFixture(
        artifacts: Map<String, ByteArray> = mapOf("device.json" to "{}".encodeToByteArray()),
        redactionValues: List<String> = listOf("source-access"),
        credentials: HostedDiagnosticsCredentials = HostedDiagnosticsCredentials(
            "installation-1",
            "installation-token",
        ),
    ): HostedFixture {
        val store = FilePendingReportStore(temporaryFolder.newFolder(), nowMs = { CAPTURED_AT })
        val binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "anonymous-hosted-device")
        val hostedManifest = manifest().copy(
            report = manifest().report.copy(profileId = null),
            destination = DiagnosticsDestination(HOSTED_DIAGNOSTICS_COLLECTOR_ID),
            consent = DiagnosticsConsent(ManifestConsentMode.MANUAL, 1),
            playbackSessionIds = emptyList(),
        )
        val report = store.save(
            PendingReportCapture(
                binding = PendingReportBinding(
                    serverInstanceId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
                    accountUserId = binding.accountUserId,
                    profileId = null,
                    ownershipGeneration = 7,
                    destinationKind = DiagnosticsDestinationKind.HOSTED,
                ),
                manifest = hostedManifest,
                artifacts = artifacts,
                fingerprint = "hosted-fingerprint",
                capturedAtEpochMs = CAPTURED_AT,
            ),
        )
        val hostedApi = FakeHostedDiagnosticsApi()
        val environment = ExitReportEnvironment(
            appVersion = "1.0",
            appBuild = "1",
            platform = DiagnosticsPlatform.ANDROID,
            osVersion = "36",
            deviceSummary = hostedManifest.deviceSummary,
        )
        val installations = HostedDiagnosticsInstallationManager(
            InMemoryHostedCredentialStore(credentials),
            hostedApi,
            environment,
        )
        val sent = FakeSentRecorder()
        val redactionTokens = RecordingRedactionTokenProvider(redactionValues)
        val staleConsent = FakeStaleConsentHandler()
        val uploader = DefaultDiagnosticsUploader(
            reports = store,
            identity = FakeIdentityResolver(
                DiagnosticsCaptureContext(
                    binding = binding,
                    profileId = null,
                    profileEligible = true,
                    noticeVersion = 1,
                    status = DiagnosticsAvailabilityStatus.AVAILABLE,
                    ownershipGeneration = 7,
                    sourceProfileId = "adult-source-profile",
                    destinationKind = DiagnosticsDestinationKind.HOSTED,
                ),
            ),
            bundleBuilder = FileDiagnosticsBundleBuilder(),
            api = FakeDiagnosticsApi(),
            hostedApi = hostedApi,
            hostedInstallations = installations,
            hostedCapabilities = HostedDiagnosticsCapabilitiesRepository(InMemoryHostedCapabilitiesStore(), hostedApi),
            redactionTokens = redactionTokens,
            sentRecorder = sent,
            consentProvider = FakeConsentProvider(DiagnosticsConsentMode.ASK),
            staleConsentHandler = staleConsent,
            nowMs = { CAPTURED_AT + 1_000 },
        )
        return HostedFixture(store, report, hostedApi, installations, redactionTokens, sent, staleConsent, uploader)
    }

    private fun fixture(maxBundleBytes: Long = 1_024 * 1_024): Fixture {
        val store = FilePendingReportStore(
            noBackupFilesDir = temporaryFolder.newFolder(),
            nowMs = { CAPTURED_AT },
        )
        val report = store.save(
            PendingReportCapture(
                binding = PENDING_BINDING,
                manifest = manifest(),
                artifacts = mapOf("device.json" to "{}".encodeToByteArray()),
                fingerprint = "fingerprint",
                capturedAtEpochMs = CAPTURED_AT,
            ),
        )
        val identity = FakeIdentityResolver(context(maxBundleBytes))
        val builder = FakeBundleBuilder()
        val api = FakeDiagnosticsApi()
        val sent = FakeSentRecorder()
        val consent = FakeConsentProvider()
        val staleConsent = FakeStaleConsentHandler()
        val uploader = DefaultDiagnosticsUploader(
            reports = store,
            identity = identity,
            bundleBuilder = builder,
            api = api,
            redactionTokens = DiagnosticsRedactionTokenProvider { _ -> listOf("secret-token") },
            sentRecorder = sent,
            consentProvider = consent,
            staleConsentHandler = staleConsent,
            nowMs = { CAPTURED_AT + 1_000 },
        )
        return Fixture(store, report, identity, builder, api, sent, consent, staleConsent, uploader)
    }

    private fun context(maxBundleBytes: Long) = DiagnosticsCaptureContext(
        binding = BINDING,
        profileId = "profile-1",
        profileEligible = true,
        noticeVersion = 2,
        status = DiagnosticsAvailabilityStatus.AVAILABLE,
        ownershipGeneration = 7,
        acceptedSchemaVersions = setOf(1),
        maxBundleBytes = maxBundleBytes,
        maxManifestBytes = 64 * 1_024,
    )

    private fun manifest() = DiagnosticsManifest(
        schemaVersion = 1,
        report = DiagnosticsReport(
            type = DiagnosticsReportType.MANUAL,
            capturedAt = "2026-07-22T00:00:00Z",
            captureSessionId = "capture-1",
            appVersion = "1.0",
            appBuild = "1",
            platform = DiagnosticsPlatform.ANDROID_TV,
            osVersion = "36",
            profileId = "profile-1",
        ),
        destination = DiagnosticsDestination("server-1"),
        consent = DiagnosticsConsent(ManifestConsentMode.MANUAL, 2),
        deviceSummary = DiagnosticsDeviceSummary("NVIDIA", "Shield", "Android 36", "tv"),
        playbackSessionIds = emptyList(),
        logSummary = DiagnosticsLogSummary(0, 0, 0, listOf(DiagnosticsLogCategory.OTHER), false),
        archive = DiagnosticsArchive(listOf("manifest.json", "device.json"), 0, 0, "0".repeat(64)),
    )

    private class FakeIdentityResolver(var current: DiagnosticsCaptureContext?) : DiagnosticsIdentityResolver {
        override suspend fun resolve(requirePersistentCapture: Boolean): DiagnosticsCaptureContext? = current
    }

    private class FakeBundleBuilder : DiagnosticsBundleBuilder {
        var onBuild: () -> Unit = {}
        var bundleBytes = byteArrayOf(1, 2, 3)
        override fun build(report: PendingReport, redactionTokens: List<String>): DiagnosticsBundle {
            onBuild()
            return DiagnosticsBundle(
                report.manifest,
                Json.encodeToString(report.manifest).encodeToByteArray(),
                bundleBytes,
            )
        }
    }

    private class FakeDiagnosticsApi : DiagnosticsApi {
        var result: DiagnosticsUploadResult = DiagnosticsUploadResult.NetworkError(IllegalStateException("offline"))
        var onUpload: () -> Unit = {}
        var uploadCalls = 0
        var capturedProfileId: String? = null
        var capturedManifest: DiagnosticsManifest? = null
        override suspend fun getStatus() = error("unused")
        override suspend fun upload(
            manifestJson: ByteArray,
            bundleBytes: ByteArray,
            capturedProfileId: String?,
        ): DiagnosticsUploadResult {
            onUpload()
            uploadCalls += 1
            this.capturedProfileId = capturedProfileId
            capturedManifest = org.siloserver.silo.model.diagnostics.decodeDiagnosticsManifest(
                manifestJson.decodeToString(),
            )
            return result
        }
    }

    private class FakeSentRecorder : DiagnosticsSentRecorder {
        val shortIds = mutableListOf<String>()
        val states = mutableListOf<String>()
        override suspend fun record(binding: DiagnosticsBinding, shortId: String, sentAtEpochMs: Long, state: String) {
            shortIds += shortId
            states += state
        }
    }

    private class InMemoryHostedCredentialStore(
        private var credentials: HostedDiagnosticsCredentials?,
    ) : HostedDiagnosticsCredentialStore {
        override suspend fun load(): HostedDiagnosticsCredentials? = credentials
        override suspend fun save(credentials: HostedDiagnosticsCredentials) {
            this.credentials = credentials
        }
        override suspend fun clear() {
            credentials = null
        }
    }

    private class InMemoryHostedCapabilitiesStore : HostedDiagnosticsCapabilitiesStore {
        private var capabilities: HostedDiagnosticsCapabilities? = null
        override suspend fun load(): HostedDiagnosticsCapabilities? = capabilities
        override suspend fun save(capabilities: HostedDiagnosticsCapabilities) {
            this.capabilities = capabilities
        }
    }

    private class FakeHostedDiagnosticsApi : HostedDiagnosticsApi {
        var createdRequest: HostedDiagnosticsCreateReportRequest? = null
        var invalidInstallationToken: String? = null
        var createReportFailure: HostedDiagnosticsApiResult.Failure? = null
        var createReportNetworkErrorsRemaining: Int = 0
        var uploadFailure: HostedDiagnosticsApiResult.Failure? = null
        var uploadReceiptOverride: HostedDiagnosticsReportStatusResponse? = null
        var reportStatusResultOverride: HostedDiagnosticsApiResult<HostedDiagnosticsReportStatusResponse>? = null
        var beforeReportStatus: suspend () -> Unit = {}
        var deleteResult: HostedDiagnosticsApiResult<Unit> = HostedDiagnosticsApiResult.Success(Unit)
        var nextUploadToken: String = "upload-token"
        var installationCreateCalls: Int = 0
        var capabilitiesCalls: Int = 0
        val createdRequests = mutableListOf<HostedDiagnosticsCreateReportRequest>()
        val createReportTokens = mutableListOf<String>()
        val createReportIds = mutableListOf<String>()
        val uploadReportIds = mutableListOf<String>()
        val uploadTokens = mutableListOf<String>()
        val statusReportIds = mutableListOf<String>()
        val deleteInstallationTokens = mutableListOf<String>()
        val deleteReportIds = mutableListOf<String>()
        var capabilities = HostedDiagnosticsCapabilities(
            status = HostedDiagnosticsAvailability.AVAILABLE,
            collectorId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
            acceptedSchemaVersions = listOf(1),
            maxBundleBytes = 10L * 1_024 * 1_024,
            maxManifestBytes = 64L * 1_024,
            retentionDays = 30,
            consentNoticeVersion = 1,
        )

        override suspend fun capabilities(): HostedDiagnosticsApiResult<HostedDiagnosticsCapabilities> {
            capabilitiesCalls += 1
            return HostedDiagnosticsApiResult.Success(capabilities)
        }
        override suspend fun createInstallation(request: HostedDiagnosticsInstallationRequest):
            HostedDiagnosticsApiResult<HostedDiagnosticsInstallationResponse> {
            installationCreateCalls += 1
            return HostedDiagnosticsApiResult.Success(
                HostedDiagnosticsInstallationResponse("installation-1", "installation-token"),
            )
        }
        override suspend fun createReport(
            installationToken: String,
            request: HostedDiagnosticsCreateReportRequest,
        ): HostedDiagnosticsApiResult<HostedDiagnosticsCreateReportResponse> {
            createReportTokens += installationToken
            createReportIds += request.reportId
            createdRequests += request
            if (createReportNetworkErrorsRemaining > 0) {
                createReportNetworkErrorsRemaining -= 1
                return HostedDiagnosticsApiResult.NetworkError(IllegalStateException("create response was lost"))
            }
            createReportFailure?.let { return it }
            if (installationToken == invalidInstallationToken) {
                return HostedDiagnosticsApiResult.Failure(
                    httpStatus = 401,
                    errorCode = "invalid_installation_token",
                    message = "installation token is invalid",
                )
            }
            createdRequest = request
            return HostedDiagnosticsApiResult.Success(
                HostedDiagnosticsCreateReportResponse(request.reportId, "ABC123", nextUploadToken, "2026-08-18T00:00:00Z"),
            )
        }
        override suspend fun uploadBundle(
            installationToken: String,
            reportId: String,
            uploadToken: String,
            bundle: ByteArray,
        ): HostedDiagnosticsApiResult<HostedDiagnosticsReportStatusResponse> {
            uploadReportIds += reportId
            uploadTokens += uploadToken
            uploadFailure?.let { return it }
            return HostedDiagnosticsApiResult.Success(
                uploadReceiptOverride ?: HostedDiagnosticsReportStatusResponse(
                    reportId,
                    "ABC123",
                    HostedDiagnosticsReportState.PROCESSING,
                ),
            )
        }
        override suspend fun reportStatus(
            installationToken: String,
            reportId: String,
        ): HostedDiagnosticsApiResult<HostedDiagnosticsReportStatusResponse> {
            statusReportIds += reportId
            beforeReportStatus()
            reportStatusResultOverride?.let { return it }
            return HostedDiagnosticsApiResult.Success(
                HostedDiagnosticsReportStatusResponse(reportId, "ABC123", HostedDiagnosticsReportState.PROCESSING),
            )
        }
        override suspend fun deleteReport(
            installationToken: String,
            reportId: String,
        ): HostedDiagnosticsApiResult<Unit> {
            deleteInstallationTokens += installationToken
            deleteReportIds += reportId
            return deleteResult
        }

        fun status(
            report: PendingReport,
            state: HostedDiagnosticsReportState,
            errorCode: String? = null,
        ) = HostedDiagnosticsReportStatusResponse(
            reportId = requireNotNull(report.id.toHostedWireReportIdOrNull()),
            shortId = "ABC123",
            state = state,
            errorCode = errorCode,
        )
    }

    private class RecordingRedactionTokenProvider(
        var values: List<String>,
    ) : DiagnosticsRedactionTokenProvider {
        var calls: Int = 0

        override suspend fun tokens(destinationKind: DiagnosticsDestinationKind): List<String> {
            calls += 1
            return values
        }
    }

    private class FakeConsentProvider(
        var mode: DiagnosticsConsentMode = DiagnosticsConsentMode.ALWAYS,
    ) : DiagnosticsUploadConsentProvider {
        override suspend fun consent(binding: DiagnosticsBinding, noticeVersion: Int): DiagnosticsConsentMode = mode
    }

    private class FakeStaleConsentHandler : DiagnosticsStaleConsentHandler {
        val demotions = mutableListOf<Pair<DiagnosticsBinding, Int>>()
        var failure: Throwable? = null
        override suspend fun demote(binding: DiagnosticsBinding, noticeVersion: Int) {
            failure?.let { throw it }
            demotions += binding to noticeVersion
        }
    }

    private data class Fixture(
        val store: FilePendingReportStore,
        val report: PendingReport,
        val identity: FakeIdentityResolver,
        val builder: FakeBundleBuilder,
        val api: FakeDiagnosticsApi,
        val sent: FakeSentRecorder,
        val consent: FakeConsentProvider,
        val staleConsent: FakeStaleConsentHandler,
        val uploader: DefaultDiagnosticsUploader,
    )

    private data class HostedFixture(
        val store: FilePendingReportStore,
        val report: PendingReport,
        val api: FakeHostedDiagnosticsApi,
        val installations: HostedDiagnosticsInstallationManager,
        val redactionTokens: RecordingRedactionTokenProvider,
        val sent: FakeSentRecorder,
        val staleConsent: FakeStaleConsentHandler,
        val uploader: DefaultDiagnosticsUploader,
    )

    private companion object {
        val BINDING = DiagnosticsBinding("server-1", "user-1")
        val PENDING_BINDING = PendingReportBinding("server-1", "user-1", "profile-1", 7)
        const val CAPTURED_AT = 1_700_000_000_000L
    }
}
