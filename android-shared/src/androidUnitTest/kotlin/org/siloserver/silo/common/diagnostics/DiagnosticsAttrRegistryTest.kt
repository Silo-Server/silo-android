package org.siloserver.silo.common.diagnostics

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.siloserver.silo.model.diagnostics.DiagnosticsAttrRegistry
import org.siloserver.silo.model.diagnostics.DiagnosticsAttrRegistry.Attr
import org.siloserver.silo.model.diagnostics.DiagnosticsAttrRegistry.ValueType
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.network.SiloJson
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Anti-drift parity test: the Kotlin attr registry must exactly match the
 * vendored `attr-registry.json` (the server's enforced registry). The Apple
 * client's registry drifted silently once — this test is why the Android one
 * can't.
 */
class DiagnosticsAttrRegistryTest {

    @Test
    fun `kotlin registry exactly matches the vendored attr-registry json`() {
        val root = SiloJson.parseToJsonElement(TestResources.text("diagnostics/v1/attr-registry.json")).jsonObject
        val categories = root.getValue("categories").jsonObject

        val expected: Map<DiagnosticsLogCategory, Map<String, ValueType>> =
            categories.entries.associate { (categoryName, keys) ->
                val category = DiagnosticsLogCategory.entries.firstOrNull { it.wire == categoryName }
                    ?: fail("attr-registry.json category '$categoryName' has no DiagnosticsLogCategory")
                category to keys.jsonObject.entries.associate { (key, spec) ->
                    key to when (val type = spec.jsonObject.getValue("type").jsonPrimitive.content) {
                        "string" -> ValueType.STRING
                        "integer" -> ValueType.INTEGER
                        else -> fail("attr-registry.json $categoryName.$key has unmodeled type '$type'")
                    }
                }
            }

        assertEquals(
            expected,
            DiagnosticsAttrRegistry.registry,
            "DiagnosticsAttrRegistry.registry drifted from vendored attr-registry.json — " +
                "re-vendor and reconcile against the server registry",
        )
    }

    @Test
    fun `filter drops unregistered keys silently when not strict`() {
        val out = DiagnosticsAttrRegistry.filter(
            DiagnosticsLogCategory.PLAYBACK,
            mapOf(
                "sink" to Attr.Str("HDMI"),
                "not_registered" to Attr.Str("leaks"),
            ),
            strict = false,
        )
        assertNotNull(out)
        assertEquals(setOf("sink"), out.keys)
        assertEquals("HDMI", out.getValue("sink").jsonPrimitive.content)
    }

    @Test
    fun `filter throws for unregistered keys when strict`() {
        assertFailsWith<IllegalStateException> {
            DiagnosticsAttrRegistry.filter(
                DiagnosticsLogCategory.PLAYBACK,
                mapOf("not_registered" to Attr.Str("leaks")),
                strict = true,
            )
        }
    }

    @Test
    fun `filter drops wrong-typed values`() {
        val out = DiagnosticsAttrRegistry.filter(
            DiagnosticsLogCategory.PLAYBACK,
            mapOf(
                "width" to Attr.Str("not-an-int"), // registered as INTEGER
                "sink" to Attr.Int64(7), // registered as STRING
                "height" to Attr.Int64(2160),
            ),
            strict = false,
        )
        assertNotNull(out)
        assertEquals(setOf("height"), out.keys)
        assertEquals(2160L, out.getValue("height").jsonPrimitive.content.toLong())
    }

    @Test
    fun `filter throws for wrong-typed values when strict`() {
        assertFailsWith<IllegalStateException> {
            DiagnosticsAttrRegistry.filter(
                DiagnosticsLogCategory.PLAYBACK,
                mapOf("width" to Attr.Str("not-an-int")),
                strict = true,
            )
        }
    }

    @Test
    fun `filter returns null when nothing survives`() {
        assertNull(DiagnosticsAttrRegistry.filter(DiagnosticsLogCategory.PLAYBACK, emptyMap()))
        assertNull(
            DiagnosticsAttrRegistry.filter(
                DiagnosticsLogCategory.LIFECYCLE,
                mapOf("not_registered" to Attr.Str("x")),
                strict = false,
            ),
        )
        assertNull(
            // OTHER has no registered keys at all.
            DiagnosticsAttrRegistry.filter(
                DiagnosticsLogCategory.OTHER,
                mapOf("state" to Attr.Str("x")),
                strict = false,
            ),
        )
    }
}
