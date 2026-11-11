package org.siloserver.silo.overlays

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull

/**
 * Settings manifest revision whose card-overlays schema first accepts the
 * edge positions, the plate and band presets, `style`, and image references.
 * Below it the server rejects a document that uses any of them.
 */
const val OVERLAY_STYLE_MANIFEST_REVISION = 25

/** Settings manifest revision that added the `resolution_hdr_audio` badge. */
const val OVERLAY_COMBINED_AUDIO_MANIFEST_REVISION = 26

/**
 * What a server can store in `ui.card_overlays` (web `OverlayServerSupport`):
 * its settings manifest revision when known, and the ids its stored value
 * already holds ([OverlaySchema.storedOverlayIds]).
 */
data class OverlayServerSupport(
    val manifestRevision: Int?,
    val storedIds: Set<String> = emptySet(),
)

/**
 * Encodes/decodes [CardOverlayPrefs] to/from the JSON object stored in the
 * canonical `ui.card_overlays` profile setting. Ports web's
 * `web/src/lib/overlays/schema.ts`; the contract is
 * `contracts/settings/v1/schemas/card-overlays.json`.
 *
 * Serialization uses a hand-built [JsonObject] with recursively sorted keys
 * so the wire format is byte-stable and matches Apple's
 * `JSONSerialization` `.sortedKeys` output: optional fields are omitted when
 * null, enums are their raw strings.
 *
 * Unlike web, which drops ids and fields it does not know, this parser keeps
 * them (see [CardOverlayPrefs]) so a save from Android never erases another
 * client's choices. The one deliberate rewrite is `top-right` → `right-edge`,
 * on known and unknown ids alike.
 */
object OverlaySchema {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    const val STYLE_SIZE_MIN = 0.75
    const val STYLE_SIZE_MAX = 2.0
    const val STYLE_BORDER_WIDTH_MAX = 3
    private const val MAX_VALUE_IMAGES = 32
    private const val MAX_VALUE_KEY_LENGTH = 64

    private val hexColor = Regex("^#[0-9a-fA-F]{6}$")
    private val hexColorWithAlpha = Regex("^#[0-9a-fA-F]{8}$")
    private val imageRef = Regex("^[0-9a-f]{64}\\.webp$")

    private val ROOT_FIELDS = setOf("version", "preset", "style", "order", "items")
    private val ITEM_FIELDS = setOf("enabled", "position", "accentColor", "showIcon", "image", "valueImages")
    private val STYLE_FIELDS = setOf(
        "size", "shape", "background", "textColor", "borderColor", "borderWidth", "fontWeight", "uppercase", "shadow",
    )

    /**
     * Root fields that are never kept. `watchStatus` was written by an early
     * build of settings revision 25 and is no longer in the contract, so a
     * server rejects a document that carries it (web drops it too).
     */
    private val RETIRED_ROOT_FIELDS = setOf("watchStatus")

    /**
     * Build the default prefs document from the registry. Used the first
     * time a user opens overlay settings, when the server returns no
     * value, and when JSON parsing fails.
     */
    fun buildDefaults(): CardOverlayPrefs {
        val items = OverlayRegistry.all.associate { def ->
            def.id to OverlayItemConfig(enabled = def.defaultEnabled, position = def.defaultPosition)
        }
        return CardOverlayPrefs(
            version = CardOverlayPrefs.CURRENT_VERSION,
            preset = PresetId.Classic,
            order = emptyList(),
            items = items,
        )
    }

    // MARK: - Parsing

    /**
     * Parse a stored `ui.card_overlays` value (a JSON object, or the legacy
     * JSON-string encoding of one) into a complete [CardOverlayPrefs]. Invalid
     * fields fall back to registry defaults field by field; anything that is
     * not an object yields [buildDefaults]. V1 docs (flat
     * `Record<OverlayId, {enabled, position}>`) are migrated to V2.
     */
    fun parse(raw: String?): CardOverlayPrefs {
        val obj = rootObject(raw) ?: return buildDefaults()
        return if (looksLikeV2(obj)) parseV2(obj) else migrateFromV1(obj)
    }

    /** [parse] for a value already decoded from the settings API. */
    fun parse(raw: JsonElement?): CardOverlayPrefs {
        val obj = rootObject(raw) ?: return buildDefaults()
        return if (looksLikeV2(obj)) parseV2(obj) else migrateFromV1(obj)
    }

    /**
     * The overlay ids a stored `ui.card_overlays` value actually contains,
     * before parsing fills in the rest of the registry (web
     * `storedOverlayIds`). The server validated that value, so it accepts
     * every one of them.
     */
    fun storedOverlayIds(raw: String?): Set<String> = storedIds(rootObject(raw))

    fun storedOverlayIds(raw: JsonElement?): Set<String> = storedIds(rootObject(raw))

    private fun storedIds(obj: JsonObject?): Set<String> {
        if (obj == null) return emptySet()
        val items = if (looksLikeV2(obj)) obj["items"] else obj
        return (items as? JsonObject)?.keys ?: emptySet()
    }

    private fun rootObject(raw: String?): JsonObject? {
        if (raw.isNullOrEmpty()) return null
        val element = try {
            json.parseToJsonElement(raw)
        } catch (_: Throwable) {
            return null
        }
        return rootObject(element)
    }

    /** An object, or a JSON string holding one (the legacy encoding). */
    private fun rootObject(element: JsonElement?): JsonObject? = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> if (element.isString) rootObject(element.content) else null
        else -> null
    }

    /**
     * Web's heuristic: v1 docs are a flat id → config map. V2 docs have
     * `version: 2`, or at least a string `preset` and an `items` object (an
     * array counts, as `typeof` reports it as one).
     */
    private fun looksLikeV2(obj: JsonObject): Boolean {
        val version = obj["version"].numberOrNull()
        if (version == 2.0) return true
        val items = obj["items"]
        return obj["preset"].stringOrNull() != null && (items is JsonObject || items is JsonArray)
    }

    private fun parseV2(obj: JsonObject): CardOverlayPrefs {
        val sourceItems = obj["items"] as? JsonObject
        val order = (obj["order"] as? JsonArray)?.mapNotNull { it.stringOrNull() } ?: emptyList()
        return CardOverlayPrefs(
            version = CardOverlayPrefs.CURRENT_VERSION,
            preset = PresetId.fromRaw(obj["preset"].stringOrNull()) ?: PresetId.Classic,
            order = order,
            items = buildItems(sourceItems),
            style = parseStyle(obj["style"]),
            passthroughItems = passthroughItems(sourceItems),
            extras = JsonObject(obj.filterKeys { it !in ROOT_FIELDS && it !in RETIRED_ROOT_FIELDS }),
        )
    }

    private fun migrateFromV1(obj: JsonObject): CardOverlayPrefs =
        buildDefaults().copy(items = buildItems(obj))

    /**
     * Every registry id gets either its stored entry patched onto the
     * registry default or the default itself (web `buildItems`).
     */
    private fun buildItems(source: JsonObject?): Map<OverlayId, OverlayItemConfig> =
        OverlayRegistry.all.associate { def ->
            val base = OverlayItemConfig(enabled = def.defaultEnabled, position = def.defaultPosition)
            val patch = source?.get(def.id.raw) as? JsonObject
            def.id to (if (patch != null) applyItemPatch(base, patch) else base)
        }

    /** Stored entries for ids outside the registry, verbatim but for `top-right`. */
    private fun passthroughItems(source: JsonObject?): Map<String, JsonObject> {
        if (source == null) return emptyMap()
        return buildMap {
            for ((id, entry) in source) {
                if (OverlayId.fromRaw(id) != null || entry !is JsonObject) continue
                put(id, movedOffTopRight(entry))
            }
        }
    }

    private fun movedOffTopRight(entry: JsonObject): JsonObject {
        if (entry["position"].stringOrNull() != OverlayPosition.LEGACY_TOP_RIGHT) return entry
        return JsonObject(entry + ("position" to JsonPrimitive(OverlayPosition.RightEdge.raw)))
    }

    private fun applyItemPatch(base: OverlayItemConfig, patch: JsonObject): OverlayItemConfig =
        OverlayItemConfig(
            enabled = patch["enabled"].booleanOrNullStrict() ?: base.enabled,
            position = OverlayPosition.fromStored(patch["position"].stringOrNull()) ?: base.position,
            accentColor = patch["accentColor"].stringOrNull()?.takeIf(::isHexColor),
            showIcon = patch["showIcon"].booleanOrNullStrict(),
            image = patch["image"].stringOrNull()?.takeIf(::isOverlayImageRef),
            valueImages = parseValueImages(patch["valueImages"]),
            extras = JsonObject(patch.filterKeys { it !in ITEM_FIELDS }),
        )

    private fun parseValueImages(value: JsonElement?): Map<String, String>? {
        val obj = value as? JsonObject ?: return null
        val entries = LinkedHashMap<String, String>()
        for ((key, ref) in obj) {
            if (entries.size == MAX_VALUE_IMAGES) break
            val refString = ref.stringOrNull() ?: continue
            // The contract's maxLength counts code points, not UTF-16 units.
            if (key.isEmpty() || codePointCount(key) > MAX_VALUE_KEY_LENGTH || !isOverlayImageRef(refString)) continue
            entries[key] = refString
        }
        return entries.ifEmpty { null }
    }

    /**
     * The `style` fields the contract accepts (web `parseOverlayStyle`): an
     * invalid field is dropped on its own rather than failing the whole
     * style, and an empty style is no style. Unknown fields are kept.
     */
    fun parseStyle(value: JsonElement?): OverlayStyle? {
        val raw = value as? JsonObject ?: return null
        val size = raw["size"].numberOrNull()?.takeIf { it in STYLE_SIZE_MIN..STYLE_SIZE_MAX }
        val borderWidth = raw["borderWidth"].numberOrNull()
            ?.takeIf { it == kotlin.math.floor(it) && it in 0.0..STYLE_BORDER_WIDTH_MAX.toDouble() }
            ?.toInt()
        val style = OverlayStyle(
            size = size,
            shape = OverlayShape.fromRaw(raw["shape"].stringOrNull()),
            background = raw["background"].stringOrNull()?.takeIf(::isHexColorWithAlpha),
            textColor = raw["textColor"].stringOrNull()?.takeIf(::isHexColor),
            borderColor = raw["borderColor"].stringOrNull()?.takeIf(::isHexColorWithAlpha),
            borderWidth = borderWidth,
            fontWeight = OverlayFontWeight.fromRaw(raw["fontWeight"].stringOrNull()),
            uppercase = raw["uppercase"].booleanOrNullStrict(),
            shadow = raw["shadow"].booleanOrNullStrict(),
            extras = JsonObject(raw.filterKeys { it !in STYLE_FIELDS }),
        )
        return style.takeUnless { it.isEmpty }
    }

    fun isHexColor(value: String): Boolean = hexColor.matches(value)

    fun isHexColorWithAlpha(value: String): Boolean = hexColorWithAlpha.matches(value)

    /** An uploaded image's content reference, as the overlay-config endpoint lists it. */
    fun isOverlayImageRef(value: String): Boolean = imageRef.matches(value)

    // MARK: - Server support

    /**
     * Whether the server accepts [id] in `ui.card_overlays` (web
     * `isOverlaySupportedBy`). Validation rejects the whole document over one
     * unknown id, so while the revision is unknown only ids the server already
     * stored count as supported.
     */
    fun isOverlaySupportedBy(id: OverlayId, support: OverlayServerSupport): Boolean =
        isSupported(id.raw, OverlayRegistry.def(id)?.introducedInManifest, support)

    private fun isSupported(raw: String, since: Int?, support: OverlayServerSupport): Boolean {
        if (since == null) return true
        val revision = support.manifestRevision
        if (revision != null) return revision >= since
        return raw in support.storedIds
    }

    /** A wire id: a registry id by its manifest revision, a passthrough id always (the server stored it). */
    private fun isRawIdSupported(raw: String, support: OverlayServerSupport): Boolean {
        val id = OverlayId.fromRaw(raw) ?: return true
        return isOverlaySupportedBy(id, support)
    }

    /**
     * Whether the server accepts the revision-25 fields: edge positions, the
     * plate and band presets, style and image references. An unknown
     * revision does not.
     */
    fun supportsOverlayStyles(support: OverlayServerSupport): Boolean {
        val revision = support.manifestRevision ?: return false
        return revision >= OVERLAY_STYLE_MANIFEST_REVISION
    }

    /**
     * The document cards render (web `withoutUnlistedImages`): image refs the
     * library ([OverlayImage.ref] values) no longer lists are dropped, so a
     * deleted image costs no request per card. Returns [prefs] itself when
     * nothing is dropped, so remembered cards keep it. Settings screens keep
     * the stored refs so they can be cleared. Passthrough entries are never
     * rendered and are left alone.
     */
    fun withoutUnlistedImages(prefs: CardOverlayPrefs, listed: Set<String>): CardOverlayPrefs {
        var changed = false
        val items = prefs.items.mapValues { (_, item) ->
            val valueImages = item.valueImages.orEmpty()
            val kept = valueImages.filterValues { it in listed }
            val dropImage = item.image != null && item.image !in listed
            if (!dropImage && kept.size == valueImages.size) return@mapValues item
            changed = true
            item.copy(image = if (dropImage) null else item.image, valueImages = kept.ifEmpty { null })
        }
        return if (changed) prefs.copy(items = items) else prefs
    }

    /**
     * The document as [support]'s server can store it (web
     * `overlayPrefsForServer`), as the wire JSON object. Ids it does not accept
     * are dropped from `items` and `order`. Below settings revision 25 (or
     * with an unknown revision) the edge rails move back to the corner on the
     * same side (`left-edge` → `top-left`, `right-edge` → `top-right`),
     * `image`, `valueImages` and `style` are dropped, and `plate` / `band`
     * become `classic`. It is a [JsonObject] rather than a [CardOverlayPrefs]
     * because the legacy `top-right` position is not an [OverlayPosition].
     */
    fun overlayPrefsForServer(prefs: CardOverlayPrefs, support: OverlayServerSupport): JsonObject {
        val document = toJsonObject(prefs)
        val styles = supportsOverlayStyles(support)
        val items = (document.getValue("items") as JsonObject)
            .filterKeys { isRawIdSupported(it, support) }
            .mapValues { (_, entry) -> if (styles) entry else legacyItem(entry as JsonObject) }
        val order = (document.getValue("order") as JsonArray)
            .filter { isRawIdSupported((it as JsonPrimitive).content, support) }
        val root = document.toMutableMap()
        root["items"] = JsonObject(items)
        root["order"] = JsonArray(order)
        if (!styles) {
            root.remove("style")
            if (prefs.preset == PresetId.Plate || prefs.preset == PresetId.Band) {
                root["preset"] = JsonPrimitive(PresetId.Classic.raw)
            }
        }
        return JsonObject(root)
    }

    private fun legacyItem(entry: JsonObject): JsonObject {
        val next = entry.toMutableMap()
        next.remove("image")
        next.remove("valueImages")
        when (entry["position"].stringOrNull()) {
            OverlayPosition.LeftEdge.raw -> next["position"] = JsonPrimitive(OverlayPosition.TopLeft.raw)
            OverlayPosition.RightEdge.raw -> next["position"] = JsonPrimitive(OverlayPosition.LEGACY_TOP_RIGHT)
        }
        return JsonObject(next)
    }

    // MARK: - Encoding

    /**
     * The full document in the current contract (settings revision 26 and
     * later), byte-stable with sorted keys. Every field, passthrough id and
     * unknown field the document holds is written; nothing is adapted to a
     * server. Use it for local comparison and for servers known to be current;
     * a write to a server goes through `serialize(prefs, support)`.
     */
    fun serialize(prefs: CardOverlayPrefs): String = encode(toJsonObject(prefs))

    /**
     * The document [support]'s server accepts ([overlayPrefsForServer]),
     * byte-stable with sorted keys. This is what a save PUTs.
     */
    fun serialize(prefs: CardOverlayPrefs, support: OverlayServerSupport): String =
        encode(overlayPrefsForServer(prefs, support))

    // Sorted keys keep the wire format stable so the user setting doesn't
    // churn the server-side change log on every save, and so it matches
    // Apple's `.sortedKeys` output byte-for-byte.
    private fun encode(obj: JsonObject): String = json.encodeToString(JsonObject.serializer(), sortObject(obj))

    private fun toJsonObject(prefs: CardOverlayPrefs): JsonObject = buildJsonObject {
        // Unknown fields first, so a known field always wins a name clash.
        for ((key, value) in prefs.extras) put(key, value)
        put("version", JsonPrimitive(prefs.version))
        put("preset", JsonPrimitive(prefs.preset.raw))
        prefs.style?.takeUnless { it.isEmpty }?.let { put("style", styleJson(it)) }
        put("order", buildJsonArray { for (id in prefs.order) add(JsonPrimitive(id)) })
        put(
            "items",
            buildJsonObject {
                for ((id, entry) in prefs.passthroughItems) put(id, entry)
                for ((id, cfg) in prefs.items) put(id.raw, itemJson(cfg))
            },
        )
    }

    private fun itemJson(cfg: OverlayItemConfig): JsonObject = buildJsonObject {
        for ((key, value) in cfg.extras) put(key, value)
        put("enabled", JsonPrimitive(cfg.enabled))
        put("position", JsonPrimitive(cfg.position.raw))
        cfg.accentColor?.let { put("accentColor", JsonPrimitive(it)) }
        cfg.showIcon?.let { put("showIcon", JsonPrimitive(it)) }
        cfg.image?.let { put("image", JsonPrimitive(it)) }
        cfg.valueImages?.takeIf { it.isNotEmpty() }?.let { images ->
            put("valueImages", buildJsonObject { for ((key, ref) in images) put(key, JsonPrimitive(ref)) })
        }
    }

    private fun styleJson(style: OverlayStyle): JsonObject = buildJsonObject {
        for ((key, value) in style.extras) put(key, value)
        style.size?.let { put("size", numberJson(it)) }
        style.shape?.let { put("shape", JsonPrimitive(it.raw)) }
        style.background?.let { put("background", JsonPrimitive(it)) }
        style.textColor?.let { put("textColor", JsonPrimitive(it)) }
        style.borderColor?.let { put("borderColor", JsonPrimitive(it)) }
        style.borderWidth?.let { put("borderWidth", JsonPrimitive(it)) }
        style.fontWeight?.let { put("fontWeight", JsonPrimitive(it.raw)) }
        style.uppercase?.let { put("uppercase", JsonPrimitive(it)) }
        style.shadow?.let { put("shadow", JsonPrimitive(it)) }
    }

    /** A whole number as an integer (`1`, not `1.0`), as JavaScript writes it. */
    private fun numberJson(value: Double): JsonPrimitive =
        if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong())
        else JsonPrimitive(value)

    /**
     * Recursively re-key every object with its keys in ascending sort
     * order, matching Apple's `JSONSerialization` `.sortedKeys` so the
     * serialized string is byte-identical across platforms.
     */
    private fun sortObject(obj: JsonObject): JsonObject = buildJsonObject {
        for (key in obj.keys.sorted()) put(key, sortElement(obj.getValue(key)))
    }

    private fun sortElement(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> sortObject(value)
        is JsonArray -> JsonArray(value.map(::sortElement))
        else -> value
    }

    // MARK: - JSON helpers (typeof-strict, as web's checks are)

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.booleanOrNullStrict(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonElement?.numberOrNull(): Double? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun codePointCount(value: String): Int {
        var count = 0
        var i = 0
        while (i < value.length) {
            val high = value[i]
            i += if (high.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return count
    }
}
