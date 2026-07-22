package org.siloserver.silo.common.diagnostics

import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashProvenance
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashSource
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsManifestDraft
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType

/**
 * Representative manifest drafts, modeled on the vendored
 * `android-tv-crash-ueh.json` / `android-manual.json` contract fixtures.
 */
internal object TestDrafts {

    fun crashDraft(
        serverInstanceId: String = "srv_home_01",
        consent: DiagnosticsManifest.Consent = DiagnosticsManifest.Consent(DiagnosticsConsentMode.PROMPT, 1),
        droppedLines: Long = 12,
    ): DiagnosticsManifestDraft = DiagnosticsManifestDraft(
        report = DiagnosticsManifest.Report(
            type = DiagnosticsReportType.CRASH,
            capturedAt = "2026-07-19T18:22:31Z",
            captureSessionId = "run_android_tv_ueh_001",
            appVersion = "1.4.2",
            appBuild = "20841",
            platform = DiagnosticsPlatform.ANDROID_TV,
            osVersion = "11 (API 30)",
            profileId = "prof_living_room",
        ),
        destination = DiagnosticsManifest.Destination(serverInstanceId),
        consent = consent,
        crash = DiagnosticsManifest.Crash(
            summary = "NullPointerException in PlaybackSessionManager.start",
            stackExcerpt = "java.lang.NullPointerException: player was null\n" +
                "\tat org.siloserver.silo.PlaybackSessionManager.start(PlaybackSessionManager.kt:42)",
            thread = "main",
            foreground = true,
            source = DiagnosticsCrashSource.UEH,
            provenance = DiagnosticsCrashProvenance.PRE_FAILURE,
            occurredAt = "2026-07-19T18:22:31Z",
        ),
        deviceSummary = DiagnosticsManifest.DeviceSummary(
            manufacturer = "NVIDIA",
            model = "SHIELD Android TV",
            os = "11",
            formFactor = "tv",
        ),
        playbackSessionIds = listOf("ps_9f2a"),
        logSummary = DiagnosticsManifest.LogSummary(
            lines = 0, // recomputed by the bundle builder
            bytesGz = 0,
            droppedLines = droppedLines,
            categories = emptyList(),
            debugLogging = false,
        ),
    )

    fun manualDraft(
        serverInstanceId: String = "srv_home_01",
    ): DiagnosticsManifestDraft = DiagnosticsManifestDraft(
        report = DiagnosticsManifest.Report(
            type = DiagnosticsReportType.MANUAL,
            capturedAt = "2026-07-19T23:40:00Z",
            captureSessionId = "run_android_manual_005",
            appVersion = "1.4.2",
            appBuild = "20841",
            platform = DiagnosticsPlatform.ANDROID,
            osVersion = "15 (API 35)",
            profileId = "prof_tablet",
        ),
        destination = DiagnosticsManifest.Destination(serverInstanceId),
        consent = DiagnosticsManifest.Consent(DiagnosticsConsentMode.MANUAL, 1),
        crash = null,
        deviceSummary = DiagnosticsManifest.DeviceSummary(
            manufacturer = "Google",
            model = "Pixel Tablet",
            os = "15",
            formFactor = "tablet",
        ),
        playbackSessionIds = emptyList(),
        logSummary = DiagnosticsManifest.LogSummary(
            lines = 0,
            bytesGz = 0,
            droppedLines = 0,
            categories = listOf(DiagnosticsLogCategory.BROWSE, DiagnosticsLogCategory.NETWORK),
            debugLogging = false,
        ),
    )
}
