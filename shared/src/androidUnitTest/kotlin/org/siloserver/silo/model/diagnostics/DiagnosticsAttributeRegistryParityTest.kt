package org.siloserver.silo.model.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Parity gate for the hand-maintained [REGISTERED_ATTRIBUTES] mirror.
 *
 * The canonical attribute registry is owned by the server contract and vendored
 * here as `diagnostics/v1/attr-registry.json`. Every other client enforces the
 * same invariant (Go `TestAttrRegistryStaysInSync`, Swift
 * `DiagnosticsAttributeRegistryParityTests`); without this test the Kotlin copy
 * can silently drift from the fixture, which is exactly what happened before.
 */
class DiagnosticsAttributeRegistryParityTest {
    @Test
    fun registeredAttributesMatchVendoredAttrRegistry() {
        val canonical = canonicalRegistry()
        val mirrored = REGISTERED_ATTRIBUTES.entries.associate { (category, attributes) ->
            category.wireName() to attributes.mapValues { (_, kind) -> kind.wireType() }
        }

        assertTrue(canonical.isNotEmpty(), "attr-registry.json declared no categories")
        // assertEquals on the whole map compares both directions at once:
        // missing categories, extra categories, missing keys, extra keys, and
        // every value type.
        assertEquals(canonical, mirrored, "REGISTERED_ATTRIBUTES drifted from diagnostics/v1/attr-registry.json")
    }

    @Test
    fun registryBackedTypeValidationCoversNewlyRegisteredKeys() {
        val line = decodeDiagnosticsLogLine(
            """{"ts":"2026-08-11T00:00:02Z","run":"run-1","lvl":"I","cat":"lifecycle","tag":"Startup",""" +
                """"msg":"phase","attrs":{"phase":"cold_start","duration_ms":42}}""",
        )
        line.validate()

        assertFailsWith<DiagnosticsValidationException> {
            line.copy(attributes = mapOf("duration_ms" to SiloJson.encodeToJsonElement("42"))).validate()
        }
        assertFailsWith<DiagnosticsValidationException> {
            line.copy(attributes = mapOf("phase" to SiloJson.encodeToJsonElement(7))).validate()
        }
    }

    private fun canonicalRegistry(): Map<String, Map<String, String>> {
        val root = SiloJson.parseToJsonElement(fixture("attr-registry.json")).jsonObject
        val categories = checkNotNull(root["categories"]) { "attr-registry.json has no categories" }.jsonObject
        return categories.entries.associate { (category, keys) ->
            category to (keys as JsonObject).entries.associate { (key, spec) ->
                key to spec.jsonObject.getValue("type").jsonPrimitive.content
            }
        }
    }

    private fun DiagnosticsLogCategory.wireName(): String =
        SiloJson.encodeToJsonElement(DiagnosticsLogCategory.serializer(), this).jsonPrimitive.content

    private fun DiagnosticsAttributeKind.wireType(): String = name.lowercase()

    private fun fixture(relativePath: String): String {
        val resourceName = "diagnostics/v1/$relativePath"
        val resource = checkNotNull(javaClass.classLoader?.getResource(resourceName)) {
            "Missing test resource $resourceName"
        }
        return resource.readText()
    }
}
