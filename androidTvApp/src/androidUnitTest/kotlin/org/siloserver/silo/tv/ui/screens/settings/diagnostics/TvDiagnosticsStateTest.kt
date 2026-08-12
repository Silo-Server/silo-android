package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.common.diagnostics.DiagnosticsPrompt
import org.siloserver.silo.common.diagnostics.DiagnosticsReportSummary
import org.siloserver.silo.common.diagnostics.DiagnosticsUiState
import org.siloserver.silo.common.diagnostics.PendingReportStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.tv.ui.navigation.TvRoute
import org.siloserver.silo.tv.ui.navigation.tvShouldShowDiagnosticsPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvDiagnosticsStateTest {
    @Test
    fun unsuccessfulFocusRequestIsRetryable() {
        assertEquals(
            TvDiagnosticsCrashFocusRequestResult.RETRY,
            tvDiagnosticsCrashFocusRequestResult(Result.success(false)),
        )
    }

    @Test
    fun detachedFocusRequesterFailureIsRetryable() {
        assertEquals(
            TvDiagnosticsCrashFocusRequestResult.RETRY,
            tvDiagnosticsCrashFocusRequestResult(
                Result.failure(IllegalStateException("Focus requester is detached")),
            ),
        )
    }

    @Test
    fun selectedConsentIsTheInitialCrashReportFocus() {
        assertEquals(
            TvDiagnosticsCrashFocus.ALWAYS,
            initialTvDiagnosticsCrashFocus(DiagnosticsConsentMode.ALWAYS),
        )
    }

    @Test
    fun hostedCollectorSkipsAlwaysInTheFocusGraph() {
        assertEquals(
            TvDiagnosticsCrashFocus.NEVER,
            nextTvDiagnosticsCrashFocus(
                current = TvDiagnosticsCrashFocus.ASK,
                direction = TvDiagnosticsFocusDirection.Down,
                debugLoggingEnabled = true,
                allowAlways = false,
            ),
        )
        assertEquals(
            TvDiagnosticsCrashFocus.ASK,
            initialTvDiagnosticsCrashFocus(DiagnosticsConsentMode.ALWAYS, allowAlways = false),
        )
    }

    @Test
    fun downTraversesConsentChoicesThenDebugLogging() {
        assertEquals(
            TvDiagnosticsCrashFocus.DEBUG_LOGGING,
            nextTvDiagnosticsCrashFocus(
                current = TvDiagnosticsCrashFocus.NEVER,
                direction = TvDiagnosticsFocusDirection.Down,
                debugLoggingEnabled = true,
            ),
        )
    }

    @Test
    fun disabledDebugLoggingIsSkipped() {
        assertEquals(
            null,
            nextTvDiagnosticsCrashFocus(
                current = TvDiagnosticsCrashFocus.NEVER,
                direction = TvDiagnosticsFocusDirection.Down,
                debugLoggingEnabled = false,
            ),
        )
    }

    @Test
    fun firstChoiceHoldsAtUpperBoundary() {
        assertEquals(
            TvDiagnosticsCrashFocus.ASK,
            nextTvDiagnosticsCrashFocus(
                current = TvDiagnosticsCrashFocus.ASK,
                direction = TvDiagnosticsFocusDirection.Up,
                debugLoggingEnabled = true,
            ),
        )
    }

    @Test
    fun downFromLastEnabledChoiceFallsThroughToCaptureSection() {
        assertEquals(
            null,
            nextTvDiagnosticsCrashFocus(
                current = TvDiagnosticsCrashFocus.DEBUG_LOGGING,
                direction = TvDiagnosticsFocusDirection.Down,
                debugLoggingEnabled = true,
            ),
        )
    }

    @Test
    fun aControlOutsideTheCurrentOrderHasNoNeighbour() {
        // Debug logging is not in the order under consent NEVER. Treating the
        // lookup miss as index 0 would send Down UPWARDS, to "Always send".
        TvDiagnosticsFocusDirection.entries.forEach { direction ->
            assertEquals(
                null,
                nextTvDiagnosticsCrashFocus(
                    current = TvDiagnosticsCrashFocus.DEBUG_LOGGING,
                    direction = direction,
                    debugLoggingEnabled = false,
                ),
            )
        }
    }

    @Test
    fun repeatedDownIsConsumedWithoutMovingToAnotherLayer() {
        assertEquals(
            TvDiagnosticsCrashFocusKeyResult(target = null, consume = true),
            tvDiagnosticsCrashFocusKeyResult(
                current = TvDiagnosticsCrashFocus.DEBUG_LOGGING,
                direction = TvDiagnosticsFocusDirection.Down,
                debugLoggingEnabled = true,
                isRepeat = true,
            ),
        )
    }

    @Test
    fun freshDownFromLastEnabledChoiceStillFallsThrough() {
        assertEquals(
            TvDiagnosticsCrashFocusKeyResult(target = null, consume = false),
            tvDiagnosticsCrashFocusKeyResult(
                current = TvDiagnosticsCrashFocus.DEBUG_LOGGING,
                direction = TvDiagnosticsFocusDirection.Down,
                debugLoggingEnabled = true,
                isRepeat = false,
            ),
        )
    }

    @Test
    fun promptDefaultsToDontSend() {
        val model = tvDiagnosticsPromptModel(
            DiagnosticsPrompt("report-1", DiagnosticsReportType.CRASH, "2026-07-22T00:00:00Z"),
        )

        assertEquals(TvDiagnosticsPromptFocus.DONT_SEND, model.initialFocus)
    }

    @Test
    fun alwaysNeedsSecondConfirmation() {
        val action = tvDiagnosticsConsentAction(
            current = DiagnosticsConsentMode.ASK,
            requested = DiagnosticsConsentMode.ALWAYS,
        )

        assertTrue(action.requiresConfirmation)
    }

    @Test
    fun reportRouteHidesPromptSoReviewIsVisible() {
        assertFalse(tvShouldShowDiagnosticsPrompt(TvRoute.DiagnosticsReport.ROUTE))
        assertFalse(tvShouldShowDiagnosticsPrompt(TvRoute.Diagnostics.route))
        assertTrue(tvShouldShowDiagnosticsPrompt(TvRoute.Main.route))
    }

    @Test
    fun disabledServerPreservesReviewAndDeleteWithoutSend() {
        val model = tvDiagnosticsScreenModel(
            DiagnosticsUiState(
                availability = DiagnosticsAvailabilityUi.DISABLED,
                profileEligible = true,
                pending = listOf(REPORT),
            ),
        )

        assertTrue(model.showPending)
        assertTrue(model.canDelete)
        assertFalse(model.canUpload)
    }

    private companion object {
        val REPORT = DiagnosticsReportSummary(
            id = "report-1",
            type = DiagnosticsReportType.CRASH,
            capturedAt = "2026-07-22T00:00:00Z",
            capturedAtEpochMs = 1_000,
            expiresAtEpochMs = 2_000,
            evidenceBytes = 512,
            destinationServerInstanceId = "server-1",
            capturedProfileId = "adult-1",
            archiveEntries = listOf("manifest.json", "device.json"),
            uploadStatus = PendingReportStatus.PENDING,
            uploadErrorCode = null,
        )
    }
}
