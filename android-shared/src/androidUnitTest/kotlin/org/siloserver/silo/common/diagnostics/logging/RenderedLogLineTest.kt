package org.siloserver.silo.common.diagnostics.logging

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLevel
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLine
import org.siloserver.silo.network.SiloJson
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderedLogLineTest {

    private val now: Instant = Instant.parse("2026-07-19T18:22:31Z")

    @Test
    fun `normal line renders valid json with all fields`() {
        val attrs = buildJsonObject { put("sink", "HDMI") }
        val rendered = RenderedLogLine.render(
            run = "run_test_1",
            lvl = DiagnosticsLogLevel.I,
            cat = DiagnosticsLogCategory.PLAYBACK,
            tag = "AudioSink",
            msg = "passthrough engaged",
            attrs = attrs,
            now = now,
        )

        // Within budget: ring line IS the canonical line.
        assertTrue(rendered.ringUtf8.contentEquals(rendered.canonicalUtf8))
        assertEquals(now.toEpochMilli(), rendered.epochMs)
        assertEquals(DiagnosticsLogCategory.PLAYBACK, rendered.category)

        val parsed = SiloJson.decodeFromString(DiagnosticsLogLine.serializer(), rendered.ringUtf8.decodeToString())
        assertEquals("2026-07-19T18:22:31Z", parsed.ts)
        assertEquals("run_test_1", parsed.run)
        assertEquals(DiagnosticsLogLevel.I, parsed.lvl)
        assertEquals(DiagnosticsLogCategory.PLAYBACK, parsed.cat)
        assertEquals("AudioSink", parsed.tag)
        assertEquals("passthrough engaged", parsed.msg)
        assertEquals("HDMI", assertNotNull(parsed.attrs).getValue("sink").jsonPrimitive.content)
    }

    @Test
    fun `oversized msg re-renders ring line as valid json without attrs`() {
        val bigMsg = "x".repeat(500) // > RING_ENTRY_MAX_BYTES on its own
        val attrs = buildJsonObject { put("sink", "HDMI") }
        val rendered = RenderedLogLine.render(
            run = "run_test_2",
            lvl = DiagnosticsLogLevel.W,
            cat = DiagnosticsLogCategory.PLAYBACK,
            tag = "AudioSink",
            msg = bigMsg,
            attrs = attrs,
            now = now,
        )

        assertTrue(rendered.canonicalUtf8.size > RenderedLogLine.RING_ENTRY_MAX_BYTES)

        // Canonical keeps full fidelity.
        val canonical = SiloJson.decodeFromString(
            DiagnosticsLogLine.serializer(),
            rendered.canonicalUtf8.decodeToString(),
        )
        assertEquals(bigMsg, canonical.msg)
        assertNotNull(canonical.attrs)

        // Ring line is re-rendered (never post-truncated): still valid JSON,
        // shortened msg, attrs dropped.
        val ring = SiloJson.decodeFromString(DiagnosticsLogLine.serializer(), rendered.ringUtf8.decodeToString())
        assertTrue(ring.msg.length < bigMsg.length)
        assertTrue(ring.msg.encodeToByteArray().size <= 224, "ring msg must respect its byte budget")
        assertTrue(bigMsg.startsWith(ring.msg))
        assertNull(ring.attrs)
        assertTrue(rendered.ringUtf8.size <= RenderedLogLine.RING_ENTRY_MAX_BYTES)
        assertEquals(ring.ts, canonical.ts)
        assertEquals(ring.run, canonical.run)
    }
}
