package org.siloserver.silo.model.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The normative per-category log-attribute registry.
 *
 * This is an exact transcription of the server's enforced registry in
 * `silo-server/internal/diagnostics/contract/contract.go` (`attrRegistry`) —
 * NOT the JSON Schema's looser `additionalProperties`. Keys absent from this
 * map are silently dropped server-side, so logging them is a silent data
 * loss; the Apple client's registry drifted exactly this way, which is why
 * this one is enforced by a parity unit test and by [filter] at the call site.
 *
 * Value kinds are string and integer only — the server registry defines no
 * other type.
 */
object DiagnosticsAttrRegistry {

    enum class ValueType { STRING, INTEGER }

    val registry: Map<DiagnosticsLogCategory, Map<String, ValueType>> = mapOf(
        DiagnosticsLogCategory.PLAYBACK to mapOf(
            "sink" to ValueType.STRING,
            "fmt" to ValueType.STRING,
            "decoder" to ValueType.STRING,
            "width" to ValueType.INTEGER,
            "height" to ValueType.INTEGER,
            "hdr_mode" to ValueType.STRING,
            "bitrate_kbps" to ValueType.INTEGER,
            "dropped_frames" to ValueType.INTEGER,
            "audio_underruns" to ValueType.INTEGER,
        ),
        DiagnosticsLogCategory.FOCUS to mapOf(
            "target" to ValueType.STRING,
            "action" to ValueType.STRING,
        ),
        DiagnosticsLogCategory.NETWORK to mapOf(
            "method" to ValueType.STRING,
            "path" to ValueType.STRING,
            "status" to ValueType.INTEGER,
            "duration_ms" to ValueType.INTEGER,
        ),
        DiagnosticsLogCategory.LIFECYCLE to mapOf(
            "state" to ValueType.STRING,
        ),
        DiagnosticsLogCategory.CRASH to mapOf(
            "fingerprint" to ValueType.STRING,
            "source" to ValueType.STRING,
        ),
    )

    /** A typed attribute value at the logging call site. */
    sealed class Attr {
        data class Str(val value: String) : Attr()
        data class Int64(val value: Long) : Attr()
    }

    /**
     * Drops unregistered keys and wrong-typed values. When [strict] (debug
     * builds), a dropped key throws instead so the mistake fails fast in
     * development; release builds silently drop, matching the server.
     */
    fun filter(
        category: DiagnosticsLogCategory,
        attrs: Map<String, Attr>,
        strict: Boolean = false,
    ): JsonObject? {
        if (attrs.isEmpty()) return null
        val registered = registry[category]
        val out = LinkedHashMap<String, JsonPrimitive>(attrs.size)
        for ((key, value) in attrs) {
            val expected = registered?.get(key)
            if (expected == null) {
                if (strict) error("Unregistered diagnostics attribute ${category.wire}.$key")
                continue
            }
            when {
                expected == ValueType.STRING && value is Attr.Str -> out[key] = JsonPrimitive(value.value)
                expected == ValueType.INTEGER && value is Attr.Int64 -> out[key] = JsonPrimitive(value.value)
                else -> if (strict) {
                    error("Diagnostics attribute ${category.wire}.$key has wrong type (expected $expected)")
                }
            }
        }
        return if (out.isEmpty()) null else JsonObject(out)
    }
}
