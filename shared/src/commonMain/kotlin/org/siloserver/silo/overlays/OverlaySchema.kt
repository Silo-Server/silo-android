package org.siloserver.silo.overlays

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Encodes/decodes [CardOverlayPrefs] to/from the JSON object stored in the
 * canonical `ui.card_overlays` profile setting. The shape MUST stay
 * compatible with web's `parseOverlayPrefs`, iOS `OverlaySchema`, and
 * tvOS, since clients share the setting.
 *
 * Serialization deliberately uses a hand-built [JsonObject] (not
 * `@Serializable` data-class encoding) so the wire format is identical
 * to Apple's `JSONSerialization` output: sorted keys, optional fields
 * omitted when null, enum raw strings.
 */
object OverlaySchema {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Build the default prefs document from the registry. Used the first
     * time a user opens overlay settings, when the server returns no
     * value, and when JSON parsing fails.
     */
    fun buildDefaults(): CardOverlayPrefs {
        val items = OverlayRegistry.all.associate { def ->
            def.id to OverlayItemConfig(
                enabled = def.defaultEnabled,
                position = def.defaultPosition,
                accentColor = null,
                showIcon = null,
            )
        }
        return CardOverlayPrefs(
            version = CardOverlayPrefs.CURRENT_VERSION,
            preset = PresetId.Classic,
            order = emptyList(),
            items = items,
        )
    }

    /**
     * Parse a JSON object string into a fully populated [CardOverlayPrefs].
     * Unknown overlay IDs and malformed entries are dropped — the
     * remaining fields fall back to registry defaults so the user's real
     * overrides survive a schema upgrade. V1 docs (flat
     * `Record<OverlayId, {enabled, position}>`) are migrated to V2.
     */
    fun parse(raw: String?): CardOverlayPrefs {
        if (raw.isNullOrEmpty()) return buildDefaults()
        val root = try {
            json.parseToJsonElement(raw)
        } catch (_: Throwable) {
            return buildDefaults()
        }
        val dict = root as? JsonObject ?: return buildDefaults()
        return if (looksLikeV2(dict)) parseV2(dict) else migrateFromV1(dict)
    }

    fun serialize(prefs: CardOverlayPrefs): String {
        val dict = toJsonObject(prefs)
        // Sorted keys keep the wire format stable so the user setting
        // doesn't churn the server-side change log on every save, and so
        // it matches Apple's `.sortedKeys` output byte-for-byte.
        return json.encodeToString(JsonObject.serializer(), sortObject(dict))
    }

    // MARK: - V2

    /**
     * Strict version check: a V2 document MUST carry `version: 2`. V1
     * documents have no `version` key at all, so they fall through to
     * [migrateFromV1].
     */
    private fun looksLikeV2(dict: JsonObject): Boolean =
        (dict["version"] as? JsonPrimitive)?.intOrNull == 2

    private fun parseV2(dict: JsonObject): CardOverlayPrefs {
        var prefs = buildDefaults()

        val presetRaw = (dict["preset"] as? JsonPrimitive)?.contentOrNull
        PresetId.fromRaw(presetRaw)?.let { prefs = prefs.copy(preset = it) }

        (dict["order"] as? JsonArray)?.let { orderRaw ->
            val order = orderRaw.mapNotNull { entry ->
                OverlayId.fromRaw((entry as? JsonPrimitive)?.contentOrNull)
            }
            prefs = prefs.copy(order = order)
        }

        (dict["items"] as? JsonObject)?.let { items ->
            val patched = prefs.items.toMutableMap()
            for (def in OverlayRegistry.all) {
                val patch = items[def.id.raw] as? JsonObject ?: continue
                val base = prefs.items[def.id] ?: continue
                patched[def.id] = applyPatch(base, patch)
            }
            prefs = prefs.copy(items = patched)
        }

        return prefs
    }

    // MARK: - V1 migration

    private fun migrateFromV1(dict: JsonObject): CardOverlayPrefs {
        val prefs = buildDefaults()
        val patched = prefs.items.toMutableMap()
        for (def in OverlayRegistry.all) {
            val patch = dict[def.id.raw] as? JsonObject ?: continue
            val base = prefs.items[def.id] ?: continue
            patched[def.id] = applyPatch(base, patch)
        }
        return prefs.copy(items = patched)
    }

    private fun applyPatch(base: OverlayItemConfig, patch: JsonObject): OverlayItemConfig {
        val enabled = (patch["enabled"] as? JsonPrimitive)?.booleanOrNull ?: base.enabled
        val position = OverlayPosition.fromRaw(
            (patch["position"] as? JsonPrimitive)?.contentOrNull,
        ) ?: base.position
        val accentRaw = (patch["accentColor"] as? JsonPrimitive)?.contentOrNull
        val accent = if (accentRaw != null && isHexColor(accentRaw)) accentRaw else null
        val showIcon = (patch["showIcon"] as? JsonPrimitive)?.booleanOrNull
        return OverlayItemConfig(
            enabled = enabled,
            position = position,
            accentColor = accent,
            showIcon = showIcon,
        )
    }

    private val hexRegex = Regex("^#[0-9a-fA-F]{6}$")

    private fun isHexColor(value: String): Boolean = hexRegex.matches(value)

    // MARK: - Encoding

    private fun toJsonObject(prefs: CardOverlayPrefs): JsonObject = buildJsonObject {
        put("version", JsonPrimitive(prefs.version))
        put("preset", JsonPrimitive(prefs.preset.raw))
        put(
            "order",
            buildJsonArray {
                for (id in prefs.order) add(JsonPrimitive(id.raw))
            },
        )
        put(
            "items",
            buildJsonObject {
                for ((id, cfg) in prefs.items) {
                    put(
                        id.raw,
                        buildJsonObject {
                            put("enabled", JsonPrimitive(cfg.enabled))
                            put("position", JsonPrimitive(cfg.position.raw))
                            cfg.accentColor?.let { put("accentColor", JsonPrimitive(it)) }
                            cfg.showIcon?.let { put("showIcon", JsonPrimitive(it)) }
                        },
                    )
                }
            },
        )
    }

    /**
     * Recursively re-key every object with its keys in ascending sort
     * order, matching Apple's `JSONSerialization` `.sortedKeys` so the
     * serialized string is byte-identical across platforms.
     */
    private fun sortObject(obj: JsonObject): JsonObject = buildJsonObject {
        for (key in obj.keys.sorted()) {
            when (val value = obj.getValue(key)) {
                is JsonObject -> put(key, sortObject(value))
                is JsonArray -> put(key, sortArray(value))
                else -> put(key, value)
            }
        }
    }

    private fun sortArray(arr: JsonArray): JsonArray = buildJsonArray {
        for (value in arr) {
            when (value) {
                is JsonObject -> add(sortObject(value))
                is JsonArray -> add(sortArray(value))
                else -> add(value)
            }
        }
    }
}
