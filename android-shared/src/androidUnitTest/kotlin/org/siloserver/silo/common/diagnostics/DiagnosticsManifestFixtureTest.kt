package org.siloserver.silo.common.diagnostics

import org.junit.Test
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSnapshot
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLine
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.validate
import org.siloserver.silo.model.diagnostics.validateShape
import org.siloserver.silo.network.SiloJson
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract test: every vendored VALID fixture must decode under the wire Json
 * config, validate clean, and survive a re-encode round trip. A failure here
 * means the Kotlin models drifted from the server contract.
 */
class DiagnosticsManifestFixtureTest {

    private val validDir = "diagnostics/v1/fixtures/valid"

    private fun manifestFixtures() =
        TestResources.listFiles(validDir, ".json").filter { it.name != "device.json" }

    @Test
    fun `fixture set contains the expected manifest fixtures`() {
        val names = manifestFixtures().map { it.name }
        assertTrue(
            names.containsAll(
                listOf(
                    "android-manual-no-profile.json",
                    "android-manual.json",
                    "android-tv-crash-ueh.json",
                    "ios-hang-metrickit.json",
                    "tvos-abnormal-exit.json",
                ),
            ),
            "unexpected valid fixture set: $names",
        )
    }

    @Test
    fun `every valid manifest fixture decodes and validates clean`() {
        val fixtures = manifestFixtures()
        assertTrue(fixtures.isNotEmpty(), "no manifest fixtures found in $validDir")
        for (file in fixtures) {
            val manifest = SiloJson.decodeFromString(DiagnosticsManifest.serializer(), file.readText())
            val problems = manifest.validate()
            assertTrue(problems.isEmpty(), "${file.name} should validate clean, got: $problems")
        }
    }

    @Test
    fun `every valid manifest fixture round-trips through re-encoding`() {
        for (file in manifestFixtures()) {
            val manifest = SiloJson.decodeFromString(DiagnosticsManifest.serializer(), file.readText())
            val reencoded = SiloJson.encodeToString(DiagnosticsManifest.serializer(), manifest)
            val decoded = SiloJson.decodeFromString(DiagnosticsManifest.serializer(), reencoded)
            assertEquals(manifest.report.type, decoded.report.type, "${file.name}: report.type")
            assertEquals(manifest.report.platform, decoded.report.platform, "${file.name}: report.platform")
            assertEquals(manifest.archive.entries, decoded.archive.entries, "${file.name}: archive.entries order")
            assertTrue(decoded.validate().isEmpty(), "${file.name}: re-decoded manifest must stay valid")
        }
    }

    @Test
    fun `device fixture decodes into snapshot with valid shape`() {
        val snapshot = SiloJson.decodeFromString(
            DiagnosticsDeviceSnapshot.serializer(),
            TestResources.text("$validDir/device.json"),
        )
        val problems = snapshot.validateShape()
        assertTrue(problems.isEmpty(), "device.json should have a valid shape, got: $problems")
    }

    @Test
    fun `every logline fixture line decodes and validates clean`() {
        val lines = TestResources.text("$validDir/loglines.jsonl")
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        assertTrue(lines.isNotEmpty(), "loglines.jsonl fixture is empty")
        for ((index, raw) in lines.withIndex()) {
            val line = SiloJson.decodeFromString(DiagnosticsLogLine.serializer(), raw)
            val problems = line.validate()
            assertTrue(problems.isEmpty(), "loglines.jsonl line $index should validate clean, got: $problems")
        }
    }
}
