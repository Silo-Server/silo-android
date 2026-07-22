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
