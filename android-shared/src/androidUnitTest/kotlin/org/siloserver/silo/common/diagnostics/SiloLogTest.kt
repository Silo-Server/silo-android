package org.siloserver.silo.common.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLevel
import org.siloserver.silo.model.diagnostics.decodeDiagnosticsLogLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SiloLogTest {
    private val redactor = DiagnosticsRedactor(knownServerHosts = setOf("secret.example"))

    @After
    fun tearDown() {
        SiloLog.installSink(null)
        SiloLog.resetRendererForTests()
        ShadowLog.clear()
    }

    @Test
    fun rendererProducesCanonicalSanitizedJsonLine() {
        val renderer = DiagnosticsLogRenderer(
            redactor = redactor,
            strictAttributeRegistry = true,
            runId = "run-test-1",
            nowEpochMillis = { 1_774_113_749_412L },
        )

        val rendered = renderer.render(
            level = DiagnosticsLogLevel.INFO,
            category = DiagnosticsLogCategory.NETWORK,
            tag = "HTTPClient",
            message = "request completed for https://secret.example/a?token=raw",
            attributes = mapOf(
                "method" to SiloLogAttribute.Text("GET"),
                "path" to SiloLogAttribute.Url("https://secret.example/api/v1/items?q=secret"),
                "status" to SiloLogAttribute.Integer(200),
                "duration_ms" to SiloLogAttribute.Integer(84),
            ),
        )

        val decoded = decodeDiagnosticsLogLine(assertNotNull(rendered))
        assertEquals("2026-03-21T17:22:29.412Z", decoded.timestamp)
        assertEquals("run-test-1", decoded.run)
        assertEquals(DiagnosticsLogLevel.INFO, decoded.level)
        assertEquals(DiagnosticsLogCategory.NETWORK, decoded.category)
        assertFalse(rendered.contains("secret.example"), rendered)
        assertFalse(rendered.contains("token=raw"), rendered)
        assertFalse(rendered.contains("q=secret"), rendered)
        assertEquals(200L, decoded.attributes?.get("status")?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun debugRendererRejectsUnregisteredAndMismatchedAttributes() {
        val renderer = DiagnosticsLogRenderer(redactor, strictAttributeRegistry = true)

        assertFailsWith<IllegalArgumentException> {
            renderer.render(
                DiagnosticsLogLevel.INFO,
                DiagnosticsLogCategory.NETWORK,
                "HTTPClient",
                "completed",
                mapOf("body" to SiloLogAttribute.Text("secret")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            renderer.render(
                DiagnosticsLogLevel.INFO,
                DiagnosticsLogCategory.NETWORK,
                "HTTPClient",
                "completed",
                mapOf("status" to SiloLogAttribute.Text("200")),
            )
        }
    }

    /**
     * The whole point of naming the route: #199 added
     * `DiagnosticsFocusLogger.contentEntryFailed(route)` without registering
     * the attribute, so the warning meant to make silent focus failures visible
     * threw the moment it fired in a debug build. A strict render of exactly
     * that shape is the regression guard.
     */
    @Test
    fun strictRendererAcceptsTheFocusRouteAttribute() {
        val renderer = DiagnosticsLogRenderer(redactor, strictAttributeRegistry = true)

        val rendered = assertNotNull(
            renderer.render(
                DiagnosticsLogLevel.WARNING,
                DiagnosticsLogCategory.FOCUS,
                "TvShell",
                "content entry failed",
                mapOf(
                    "target" to SiloLogAttribute.Text("content"),
                    "action" to SiloLogAttribute.Text("entry"),
                    "route" to SiloLogAttribute.Text("main/movies"),
                ),
            ),
        )

        assertTrue(rendered.contains("main/movies"), rendered)
    }

    @Test
    fun strictRendererStillRejectsAFocusRouteOfTheWrongKind() {
        val renderer = DiagnosticsLogRenderer(redactor, strictAttributeRegistry = true)

        assertFailsWith<IllegalArgumentException> {
            renderer.render(
                DiagnosticsLogLevel.WARNING,
                DiagnosticsLogCategory.FOCUS,
                "TvShell",
                "content entry failed",
                mapOf("route" to SiloLogAttribute.Integer(7)),
            )
        }
    }

    @Test
    fun strictRendererAcceptsRegisteredSeekPerformanceAttributes() {
        val renderer = DiagnosticsLogRenderer(redactor, strictAttributeRegistry = true)

        val rendered = assertNotNull(
            renderer.render(
                DiagnosticsLogLevel.INFO,
                DiagnosticsLogCategory.PLAYBACK,
                "Media3Analytics",
                "player stats snapshot",
                mapOf(
                    "seek_count" to SiloLogAttribute.Integer(1),
                    "seek_last_ms" to SiloLogAttribute.Integer(275),
                    "seek_total_ms" to SiloLogAttribute.Integer(275),
                    "seek_max_ms" to SiloLogAttribute.Integer(275),
                ),
            ),
        )

        val attributes = Json.parseToJsonElement(rendered).jsonObject["attrs"]?.jsonObject
        assertEquals(setOf("seek_count", "seek_last_ms", "seek_total_ms", "seek_max_ms"), attributes?.keys)
    }

    @Test
    fun productionRendererDropsInvalidAttributes() {
        val renderer = DiagnosticsLogRenderer(redactor, strictAttributeRegistry = false)

        val rendered = assertNotNull(
            renderer.render(
                DiagnosticsLogLevel.INFO,
                DiagnosticsLogCategory.NETWORK,
                "HTTPClient",
                "completed",
                mapOf(
                    "body" to SiloLogAttribute.Text("secret"),
                    "status" to SiloLogAttribute.Text("wrong type"),
                    "method" to SiloLogAttribute.Text("GET"),
                ),
            ),
        )
        val attrs = Json.parseToJsonElement(rendered).jsonObject["attrs"]?.jsonObject
        assertEquals(setOf("method"), attrs?.keys)
        assertFalse(rendered.contains("secret"), rendered)
        assertFalse(rendered.contains("wrong type"), rendered)
    }

    @Test
    fun allLevelsForwardToLogcatAndOfferOnlySanitizedLines() {
        val lines = mutableListOf<String>()
        SiloLog.setRendererForTests(
            DiagnosticsLogRenderer(
                redactor = redactor,
                strictAttributeRegistry = true,
                runId = "run-test-2",
                nowEpochMillis = { 1_774_113_749_412L },
            ),
        )
        SiloLog.installSink { lines += it }

        SiloLog.v(DiagnosticsLogCategory.OTHER, "Test", "verbose")
        SiloLog.d(DiagnosticsLogCategory.OTHER, "Test", "debug")
        SiloLog.i(DiagnosticsLogCategory.LIFECYCLE, "Test", "active", mapOf("state" to SiloLogAttribute.Text("active")))
        SiloLog.w(DiagnosticsLogCategory.OTHER, "Test", "warning")
        SiloLog.e(
            DiagnosticsLogCategory.CRASH,
            "Test",
            "failed",
            attributes = mapOf("source" to SiloLogAttribute.Text("ueh")),
            throwable = IllegalStateException("Authorization: Bearer secret-token user@example.com"),
        )

        assertEquals(5, lines.size)
        assertEquals(listOf("V", "D", "I", "W", "E"), lines.map {
            Json.parseToJsonElement(it).jsonObject.getValue("lvl").jsonPrimitive.contentOrNull
        })
        assertFalse(lines.last().contains("secret-token"), lines.last())
        assertFalse(lines.last().contains("user@example.com"), lines.last())
        assertTrue(lines.last().contains("IllegalStateException"), lines.last())

        val logcat = ShadowLog.getLogsForTag("Test")
        assertEquals(5, logcat.size)
        assertEquals("failed", logcat.last().msg)
        assertTrue(logcat.last().throwable is IllegalStateException)
    }

    @Test
    fun onlyExplicitBreadcrumbsReachThePersistentSink() {
        val lines = mutableListOf<String>()
        SiloLog.setRendererForTests(
            DiagnosticsLogRenderer(
                redactor = redactor,
                strictAttributeRegistry = true,
                runId = "run-breadcrumb",
            ),
        )
        SiloLog.installSink(null)
        SiloLog.installBreadcrumbSink { lines += it }
        try {
            SiloLog.i(DiagnosticsLogCategory.LIFECYCLE, "Test", "ordinary")
            SiloLog.breadcrumb(DiagnosticsLogCategory.LIFECYCLE, "Test", "foreground")
        } finally {
            SiloLog.installBreadcrumbSink(null)
        }

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("foreground"))
    }
}
