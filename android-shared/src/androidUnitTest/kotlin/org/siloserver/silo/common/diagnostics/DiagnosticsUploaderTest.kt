package org.siloserver.silo.common.diagnostics

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DiagnosticsUploaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
            redactionTokens = DiagnosticsRedactionTokenProvider { listOf("secret-token") },
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
        override suspend fun record(binding: DiagnosticsBinding, shortId: String, sentAtEpochMs: Long) {
            shortIds += shortId
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

    private companion object {
        val BINDING = DiagnosticsBinding("server-1", "user-1")
        val PENDING_BINDING = PendingReportBinding("server-1", "user-1", "profile-1", 7)
        const val CAPTURED_AT = 1_700_000_000_000L
    }
}
