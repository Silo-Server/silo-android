package org.siloserver.silo.common.diagnostics

import org.junit.Test
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.validate
import org.siloserver.silo.network.SiloJson
import kotlin.test.assertTrue

/**
 * Contract test: every vendored INVALID fixture must be rejected client-side —
 * either at decode time (enum/shape violations under the wire Json config) or
 * by [validate] (semantic violations the models can still represent).
 */
class DiagnosticsManifestInvalidFixtureTest {

    private val invalidDir = "diagnostics/v1/fixtures/invalid"

    @Test
    fun `fixture set contains the expected invalid fixtures`() {
        val names = TestResources.listFiles(invalidDir, ".json").map { it.name }
        assertTrue(
            names.containsAll(
                listOf(
                    "archive-entry-outside-allowlist.json",
                    "bad-schema-version.json",
                    "missing-consent.json",
                    "platform-macos-reserved.json",
                    "stack-excerpt-over-8kib.json",
                    "unknown-report-type.json",
                ),
            ),
            "unexpected invalid fixture set: $names",
        )
    }

    @Test
    fun `every invalid fixture fails decode or validation`() {
        val fixtures = TestResources.listFiles(invalidDir, ".json")
        assertTrue(fixtures.isNotEmpty(), "no invalid fixtures found in $invalidDir")
        for (file in fixtures) {
            val decoded = runCatching {
                SiloJson.decodeFromString(DiagnosticsManifest.serializer(), file.readText())
            }
            if (decoded.isSuccess) {
                val problems = decoded.getOrThrow().validate()
                assertTrue(
                    problems.isNotEmpty(),
                    "${file.name} decoded successfully but validate() found no problems — " +
                        "an invalid contract fixture would be accepted",
                )
            }
        }
    }
}
