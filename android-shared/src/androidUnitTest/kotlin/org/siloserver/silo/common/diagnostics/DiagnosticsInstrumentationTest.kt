package org.siloserver.silo.common.diagnostics

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsInstrumentationTest {
    @AfterTest
    fun tearDown() {
        SiloLog.installSink(null)
    }

    @Test
    fun networkEvidenceHasNoQueryBodyOrIdentifier() {
        val lines = mutableListOf<String>()
        SiloLog.installSink { lines += it }

        DiagnosticsNetworkLogger.completed(
            method = "GET",
            rawPath = "/api/v1/items/private-item-id?access_token=secret",
            status = 200,
            durationMs = 42,
        )

        val line = lines.single()
        assertFalse(line.contains("?"))
        assertFalse(line.contains("body", ignoreCase = true))
        assertFalse(line.contains("private-item-id"))
        assertFalse(line.contains("secret"))
        assertTrue(line.contains("duration_ms"))
        assertTrue(line.contains("/api/v1/other"))
    }

    @Test
    fun networkRoutesPreserveOnlyAllowlistedStaticSegments() {
        assertEquals("/api/v1/other", safeDiagnosticsNetworkPath("/api/v1/playback/start?token=secret"))
        assertEquals("/api/v1/playback/route-events", safeDiagnosticsNetworkPath("/api/v1/playback/route-events"))
        assertEquals("/api/v1/playback/{id}/progress", safeDiagnosticsNetworkPath("/api/v1/playback/private-session/progress"))
        assertEquals(
            "/api/v1/other",
            safeDiagnosticsNetworkPath("/api/v1/playback/sessions/private-session/control/ws#fragment"),
        )
        assertEquals("/api/v1/items/{id}", safeDiagnosticsNetworkPath("/api/v1/items/status"))
        // Allowlisted resource, unmatched tail: names the resource so the
        // endpoint is actionable. Unknown resources stay anonymous (below).
        assertEquals("/api/v1/playback/other", safeDiagnosticsNetworkPath("/api/v1/playback/start/status"))
        // The per-item favorite/watchlist probes answer "not a favourite" with a
        // 404, so they are the loudest 4xx the client emits. A template resolves
        // them exactly; without one they were only ever "/api/v1/favorites/other".
        assertEquals("/api/v1/favorites/{id}", safeDiagnosticsNetworkPath("/api/v1/favorites/episode-tvdb-1-1-1"))
        assertEquals("/api/v1/watchlist/{id}", safeDiagnosticsNetworkPath("/api/v1/watchlist/series-tvdb-1"))
        assertEquals("/api/v1/other", safeDiagnosticsNetworkPath("/api/v1/private/private-id"))
        assertEquals("/other", safeDiagnosticsNetworkPath("/not-api/private-id"))
    }

    /**
     * Every root this change allowlisted, not just the two the original test
     * happened to cover. 116 of 121 recorded 4xx were unnameable because these
     * collapsed to "/api/v1/other"; a template that silently stops matching
     * puts them straight back there.
     */
    @Test
    fun everyNewlyAllowlistedRouteResolvesToItsTemplate() {
        mapOf(
            "/api/v1/favorites" to "/api/v1/favorites",
            "/api/v1/favorites/episode-tvdb-1-1-1" to "/api/v1/favorites/{id}",
            "/api/v1/history" to "/api/v1/history",
            "/api/v1/library-playback-prefs" to "/api/v1/library-playback-prefs",
            "/api/v1/library-playback-prefs/library-7" to "/api/v1/library-playback-prefs/{id}",
            "/api/v1/metadata/ai/status" to "/api/v1/metadata/ai/status",
            "/api/v1/onboarding/flow" to "/api/v1/onboarding/flow",
            "/api/v1/onboarding/state" to "/api/v1/onboarding/state",
            "/api/v1/onboarding/progress" to "/api/v1/onboarding/progress",
            "/api/v1/watch/series-tvdb-1" to "/api/v1/watch/{id}",
            "/api/v1/watchlist" to "/api/v1/watchlist",
            "/api/v1/watchlist/series-tvdb-1" to "/api/v1/watchlist/{id}",
        ).forEach { (rawPath, expected) ->
            assertEquals(expected, safeDiagnosticsNetworkPath(rawPath), rawPath)
        }
    }

    /**
     * The other half of the same bargain: an allowlisted resource names itself
     * even on an unmatched tail, but an unknown resource stays anonymous.
     */
    @Test
    fun anAllowlistedResourceNamesItselfWhileAnUnknownOneStaysAnonymous() {
        assertEquals("/api/v1/history/other", safeDiagnosticsNetworkPath("/api/v1/history/2026/08"))
        assertEquals("/api/v1/onboarding/other", safeDiagnosticsNetworkPath("/api/v1/onboarding/private-step/detail"))
        assertEquals("/api/v1/other", safeDiagnosticsNetworkPath("/api/v1/private-resource/private-id"))
    }

    @Test
    fun statsSnapshotsRequireDetailedCaptureAndFiveSecondCadence() {
        val cadence = DiagnosticsStatsCadence(intervalMs = 5_000)

        assertFalse(cadence.shouldEmit(detailedCapture = false, nowMs = 10_000))
        assertTrue(cadence.shouldEmit(detailedCapture = true, nowMs = 10_000))
        assertFalse(cadence.shouldEmit(detailedCapture = true, nowMs = 14_999))
        assertTrue(cadence.shouldEmit(detailedCapture = true, nowMs = 15_000))
    }

    @Test
    fun trackAndSubtitleTextAreNeverCaptured() {
        val lines = mutableListOf<String>()
        SiloLog.installSink { lines += it }

        DiagnosticsPlaybackLogger.tracksChanged()

        val line = lines.single()
        assertTrue(line.contains("tracks changed"))
        assertFalse(line.contains("onCues"))
        assertFalse(line.contains("cue.text"))
        assertFalse(line.contains("subtitle text"))
    }

    @Test
    fun unknownNavigationRouteCannotBecomeEvidence() {
        val lines = mutableListOf<String>()
        SiloLog.installSink { lines += it }

        DiagnosticsLifecycleLogger.route("private-title/123")

        val line = lines.single()
        assertFalse(line.contains("private-title"))
        assertTrue(line.contains("route:unknown"))
    }

    @Test
    fun appPerformanceSnapshotUsesOnlyRegisteredAggregateAttributes() {
        val lines = mutableListOf<String>()
        SiloLog.installSink { lines += it }

        DiagnosticsPerformanceLogger.snapshot(
            frames = PerformanceWindowSnapshot(120, 7, 22, 48, 2, 410),
            resources = DiagnosticsResourceSnapshot(82, 140, lowMemory = false, thermalStatus = 1),
            startupFirstFrameMs = 930,
        )

        val line = lines.single()
        assertTrue(line.contains("app performance snapshot"), line)
        assertTrue(line.contains("slow_frame_count"), line)
        assertTrue(line.contains("process_pss_mb"), line)
        assertFalse(line.contains("view_text"), line)
    }
}
