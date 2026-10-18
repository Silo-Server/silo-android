package org.siloserver.silo.domain.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Replays the server's speed test vectors (`probe_cases` in
 * `docs/design/schemas/buffering-quality/v1/vectors.json`, vendored unchanged)
 * against [SpeedTestMeter]. Web and Apple replay the same cases.
 */
class BandwidthProbeVectorsTest {

    private val vectors: JsonObject by lazy {
        val raw = checkNotNull(
            javaClass.classLoader?.getResource("playback/buffering-quality/v1/vectors.json"),
        ) { "Missing test resource playback/buffering-quality/v1/vectors.json" }.readText()
        Json.parseToJsonElement(raw).jsonObject
    }

    @Test
    fun constantsMatchTheVendoredVectors() {
        val constants = vectors.getValue("probe_constants").jsonObject
        fun long(name: String) = constants.getValue(name).jsonPrimitive.long
        assertEquals(SpeedTestMeter.WINDOW_MS, long("PROBE_WINDOW_MS"))
        assertEquals(SpeedTestMode.QUICK.requestBytes, long("QUICK_PROBE_BYTES"))
        assertEquals(SpeedTestMode.QUICK.maxMs, long("QUICK_PROBE_MAX_MS"))
        assertEquals(SpeedTestMode.QUICK.settleWindows.toLong(), long("QUICK_SETTLE_WINDOWS"))
        assertEquals(SpeedTestMode.ACCURATE.requestBytes, long("ACCURATE_PROBE_BYTES"))
        assertEquals(SpeedTestMode.ACCURATE.maxMs, long("ACCURATE_PROBE_MAX_MS"))
        assertEquals(SpeedTestMode.ACCURATE.settleWindows.toLong(), long("ACCURATE_SETTLE_WINDOWS"))
        assertEquals(SpeedTestMeter.FIRST_BYTE_DEADLINE_MS, long("PROBE_FIRST_BYTE_DEADLINE_MS"))
        assertEquals(SpeedTestMeter.MIN_KBPS.toLong(), long("MIN_PROBE_KBPS"))
        assertEquals(SpeedTestMeter.MAX_KBPS.toLong(), long("MAX_PROBE_KBPS"))
    }

    @Test
    fun everyCaseReplays() {
        val cases = vectors.getValue("probe_cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty(), "vectors.json has no probe_cases")
        val failures = mutableListOf<String>()
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            try {
                replay(case)
            } catch (e: Throwable) {
                failures += "$name: ${e.message ?: e::class.simpleName}"
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${cases.size} probe cases failed:\n" + failures.joinToString("\n"))
        }
    }

    private fun replay(case: JsonObject) {
        val mode = when (val mode = case.getValue("mode").jsonPrimitive.content) {
            "quick" -> SpeedTestMode.QUICK
            "accurate" -> SpeedTestMode.ACCURATE
            else -> fail("unknown mode $mode")
        }
        val target = case["target_kbps"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
        val meter = SpeedTestMeter(mode, target)
        for (chunk in case.getValue("chunks").jsonArray) {
            val (at, bytes) = chunk.jsonArray.map { it.jsonPrimitive.long }
            meter.onBytes(at, bytes)
        }
        val end = case["end"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.long
        // Absent, the transfer ran past the test's limit.
        if (end != null) meter.onEnd(end) else meter.advanceTo(mode.maxMs)

        val expect = case.getValue("expect")
        val result = meter.result()
        if (expect is JsonNull) {
            assertEquals(null, result, "expected no measurement")
            return
        }
        val expected = expect.jsonObject
        assertEquals(
            SpeedTestResult(
                kbps = expected.getValue("kbps").jsonPrimitive.int,
                lowerBound = expected.getValue("lower_bound").jsonPrimitive.boolean,
                bytes = expected.getValue("bytes").jsonPrimitive.long,
                durationMs = expected.getValue("duration_ms").jsonPrimitive.int,
            ),
            result,
        )
        assertEquals(expected.getValue("stop").jsonPrimitive.long, meter.stopAtMs, "stop")
    }
}
