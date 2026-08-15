package org.siloserver.silo.tv.ui.screens.settings.diagnostics

import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.common.diagnostics.DiagnosticsDestinationKind
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvDiagnosticsStateTest {
    // -----------------------------------------------------------------------
    // Destination
    //
    // These two choices existed in the UI but no D-pad press could reach them:
    // a hand-rolled focus ladder above them consumed Up at its own first row,
    // so focus could never leave the consent block upwards. The ladder is gone
    // — both choices now live behind the shared settings picker sheet, which is
    // reached by a normal focusable row.
    // -----------------------------------------------------------------------

    @Test
    fun bothDestinationsAreOfferedInHostedFirstOrder() {
        assertEquals(
            listOf(DiagnosticsDestinationKind.HOSTED, DiagnosticsDestinationKind.SELF_HOSTED),
            TvDiagnosticsDestinations,
        )
        assertEquals("Silo Diagnostics", tvDiagnosticsDestinationTitle(DiagnosticsDestinationKind.HOSTED))
        assertEquals("This Silo server", tvDiagnosticsDestinationTitle(DiagnosticsDestinationKind.SELF_HOSTED))
    }

    @Test
    fun selfHostedDestinationReadsAsTheConnectedServer() {
        assertEquals(
            "Living Room Silo",
            tvDiagnosticsDestinationName(DiagnosticsDestinationKind.SELF_HOSTED, "Living Room Silo"),
        )
        // An unnamed server must not render an empty value row.
        assertEquals(
            "This Silo server",
            tvDiagnosticsDestinationName(DiagnosticsDestinationKind.SELF_HOSTED, ""),
        )
        assertEquals(
            "Silo Diagnostics",
            tvDiagnosticsDestinationName(DiagnosticsDestinationKind.HOSTED, "Living Room Silo"),
        )
    }

    // -----------------------------------------------------------------------
    // Consent
    // -----------------------------------------------------------------------

    @Test
    fun hostedCollectorDoesNotOfferAlways() {
        assertEquals(
            listOf(DiagnosticsConsentMode.ASK, DiagnosticsConsentMode.NEVER),
            tvDiagnosticsConsentOptions(allowsAutomaticUpload = false),
        )
        assertEquals(
            DiagnosticsConsentMode.entries,
            tvDiagnosticsConsentOptions(allowsAutomaticUpload = true),
        )
    }

    @Test
    fun storedAlwaysReadsAsAskWhereAutomaticUploadIsNotAllowed() {
        // The row must not name a mode the picker cannot even show.
        assertEquals(
            DiagnosticsConsentMode.ASK,
            tvDiagnosticsEffectiveConsent(DiagnosticsConsentMode.ALWAYS, allowsAutomaticUpload = false),
        )
        assertEquals(
            DiagnosticsConsentMode.ALWAYS,
            tvDiagnosticsEffectiveConsent(DiagnosticsConsentMode.ALWAYS, allowsAutomaticUpload = true),
        )
        assertEquals(
            DiagnosticsConsentMode.NEVER,
            tvDiagnosticsEffectiveConsent(DiagnosticsConsentMode.NEVER, allowsAutomaticUpload = false),
        )
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
    fun reselectingAlwaysDoesNotReconfirm() {
        assertFalse(
            tvDiagnosticsConsentAction(
                current = DiagnosticsConsentMode.ALWAYS,
                requested = DiagnosticsConsentMode.ALWAYS,
            ).requiresConfirmation,
        )
    }

    // -----------------------------------------------------------------------
    // Section content
    // -----------------------------------------------------------------------

    @Test
    fun pendingHeaderCarriesTheCountIncludingZero() {
        assertEquals("Pending Reports (0)", tvDiagnosticsPendingHeader(0))
        assertEquals("Pending Reports (3)", tvDiagnosticsPendingHeader(3))
    }

    @Test
    fun wireReportTypesRenderAsTitles() {
        assertEquals("Crash", tvDiagnosticsReportTypeTitle(DiagnosticsReportType.CRASH))
        assertEquals("Crash", tvDiagnosticsReportTypeTitle(DiagnosticsReportType.NATIVE_CRASH))
        assertEquals("Not Responding", tvDiagnosticsReportTypeTitle(DiagnosticsReportType.ANR))
        assertEquals("Not Responding", tvDiagnosticsReportTypeTitle(DiagnosticsReportType.HANG))
        assertEquals("Unclean Shutdown", tvDiagnosticsReportTypeTitle(DiagnosticsReportType.ABNORMAL_EXIT))
        assertEquals("Manual Report", tvDiagnosticsReportTypeTitle(DiagnosticsReportType.MANUAL))
    }

    @Test
    fun statusRowUsesTheShortFeatureStateTitles() {
        assertEquals("Available", tvDiagnosticsStatusTitle(DiagnosticsAvailabilityUi.AVAILABLE))
        assertEquals("Disabled by server", tvDiagnosticsStatusTitle(DiagnosticsAvailabilityUi.DISABLED))
        assertEquals("Offline", tvDiagnosticsStatusTitle(DiagnosticsAvailabilityUi.OFFLINE))
    }

    @Test
    fun expiryCountsWholeDaysAndNamesAnElapsedReport() {
        val day = 24L * 60L * 60L * 1000L
        assertEquals("Expires in 30 days", tvDiagnosticsExpiryLabel(30 * day, 0))
        assertEquals("Expires in 1 day", tvDiagnosticsExpiryLabel(day, 0))
        // A part-day still has time left, so it must not read as expired.
        assertEquals("Expires in 1 day", tvDiagnosticsExpiryLabel(day / 2, 0))
        assertEquals("Expired", tvDiagnosticsExpiryLabel(0, day))
    }

    @Test
    fun promptDefaultsToDontSend() {
        val model = tvDiagnosticsPromptModel(
            DiagnosticsPrompt("report-1", DiagnosticsReportType.CRASH, "2026-07-22T00:00:00Z"),
        )

        assertEquals(TvDiagnosticsPromptFocus.DONT_SEND, model.initialFocus)
    }

    // -----------------------------------------------------------------------
    // Focus context
    //
    // Read-only rows are outside the focus graph, so the content at the ends of
    // the pane is only ever seen because a focused row asked for it. Both
    // halves of that — which row asks, and how much it may ask for — are pinned
    // here: getting either wrong strands content with no D-pad press able to
    // recover it, which is the defect this replaced.
    // -----------------------------------------------------------------------

    @Test
    fun theTopmostSectionWithAControlOwnsEntryFocus() {
        // PENDING REPORTS sits above CAPTURE and only has focusable rows while
        // reports are waiting, so entry focus moves between the two sections.
        assertTrue(tvDiagnosticsPendingOwnsFirstFocus(1))
        assertFalse(tvDiagnosticsPendingOwnsFirstFocus(0))
    }

    @Test
    fun aContextRequestNeverOverhangsBothViewportEdges() {
        // Compose reads a rect taller than the container as "already visible"
        // and scrolls by nothing, so an unclamped ask is an ask for no scroll
        // at all. Requesting a whole viewport on one side means "as much as
        // fits", never more.
        val reveal = tvListContextReveal(
            nodeHeightPx = 84,
            viewportPx = 768,
            abovePx = 768,
            belowPx = 0,
        )

        assertEquals(TvListContextReveal(topPx = -684f, bottomPx = 84f), reveal)
        assertEquals(768f, reveal!!.bottomPx - reveal.topPx)
    }

    @Test
    fun aFooterSizedRequestIsPassedThroughUntouched() {
        assertEquals(
            TvListContextReveal(topPx = 0f, bottomPx = 284f),
            tvListContextReveal(nodeHeightPx = 84, viewportPx = 768, abovePx = 0, belowPx = 200),
        )
    }

    @Test
    fun anUnmeasuredOrEmptyRequestAsksForNothing() {
        // Before first layout there is no rect worth sending, and a row with no
        // context to reveal must not fight Compose's own bring-into-view.
        assertNull(tvListContextReveal(nodeHeightPx = 0, viewportPx = 768, abovePx = 768, belowPx = 0))
        assertNull(tvListContextReveal(nodeHeightPx = 84, viewportPx = 0, abovePx = 768, belowPx = 0))
        assertNull(tvListContextReveal(nodeHeightPx = 84, viewportPx = 768, abovePx = 0, belowPx = 0))
        // A row taller than the viewport has no room to spare for anything else.
        assertNull(tvListContextReveal(nodeHeightPx = 800, viewportPx = 768, abovePx = 768, belowPx = 0))
    }

    // -----------------------------------------------------------------------
    // Prompt suppression
    // -----------------------------------------------------------------------

    @Test
    fun promptStaysHiddenOnEveryDiagnosticsSurface() {
        // The report detail is still a route.
        assertFalse(tvShouldShowDiagnosticsPrompt(TvRoute.DiagnosticsReport.ROUTE))
        // The settings surface is a pane inside Main, so it reports presence
        // instead — without this the prompt would reopen over its own list.
        assertFalse(
            tvShouldShowDiagnosticsPrompt(
                currentRoute = TvRoute.Main.route,
                diagnosticsSurfaceVisible = true,
            ),
        )
        assertTrue(tvShouldShowDiagnosticsPrompt(TvRoute.Main.route))
    }

    @Test
    fun surfacePresenceSurvivesOverlappingEnterAndLeave() {
        // A category swap can compose the next pane before the old one is
        // disposed; a plain boolean would latch false and let the prompt in.
        TvDiagnosticsSurfacePresence.enter()
        TvDiagnosticsSurfacePresence.enter()
        TvDiagnosticsSurfacePresence.leave()
        assertTrue(TvDiagnosticsSurfacePresence.isVisible)
        TvDiagnosticsSurfacePresence.leave()
        assertFalse(TvDiagnosticsSurfacePresence.isVisible)
        // Never goes negative, so a stray dispose cannot wedge it visible.
        TvDiagnosticsSurfacePresence.leave()
        TvDiagnosticsSurfacePresence.enter()
        assertTrue(TvDiagnosticsSurfacePresence.isVisible)
        TvDiagnosticsSurfacePresence.leave()
        assertFalse(TvDiagnosticsSurfacePresence.isVisible)
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
