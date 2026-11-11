package org.siloserver.silo.overlays

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OverlaySchemaTest {

    private fun ref(pair: String) = pair.repeat(32) + ".webp"

    private fun wire(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun parseNull_returnsBuildDefaults() {
        assertEquals(OverlaySchema.buildDefaults(), OverlaySchema.parse(null as String?))
    }

    @Test
    fun parseEmpty_returnsBuildDefaults() {
        assertEquals(OverlaySchema.buildDefaults(), OverlaySchema.parse(""))
    }

    @Test
    fun parseGarbage_returnsBuildDefaults() {
        assertEquals(OverlaySchema.buildDefaults(), OverlaySchema.parse("not json"))
        assertEquals(OverlaySchema.buildDefaults(), OverlaySchema.parse("[1,2,3]"))
    }

    @Test
    fun parse_acceptsTheLegacyJsonStringEncoding() {
        val inner = """{"version":2,"preset":"pill","order":[],"items":{}}"""
        val encoded = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(inner))
        assertEquals(PresetId.Pill, OverlaySchema.parse(encoded).preset)
    }

    @Test
    fun defaults_haveEveryRegistryOverlay() {
        val defaults = OverlaySchema.buildDefaults()
        assertEquals(OverlayRegistry.all.size, defaults.items.size)
        for (def in OverlayRegistry.all) {
            val cfg = defaults.items[def.id]
            assertTrue(cfg != null, "missing default for ${def.id}")
            assertEquals(def.defaultEnabled, cfg.enabled)
            assertEquals(def.defaultPosition, cfg.position)
            assertNull(cfg.accentColor)
            assertNull(cfg.showIcon)
        }
        assertEquals(PresetId.Classic, defaults.preset)
        assertEquals(CardOverlayPrefs.CURRENT_VERSION, defaults.version)
        assertTrue(defaults.order.isEmpty())
        assertNull(defaults.style)
    }

    @Test
    fun roundTrip_defaults() {
        val prefs = OverlaySchema.buildDefaults()
        assertEquals(prefs, OverlaySchema.parse(OverlaySchema.serialize(prefs)))
    }

    @Test
    fun roundTrip_customized() {
        val base = OverlaySchema.buildDefaults()
        val items = base.items.toMutableMap()
        items[OverlayId.RatingImdb] = OverlayItemConfig(
            enabled = true,
            position = OverlayPosition.LeftEdge,
            accentColor = "#f5c518",
            showIcon = true,
            image = ref("ab"),
        )
        items[OverlayId.Resolution] = OverlayItemConfig(
            enabled = false,
            position = OverlayPosition.BottomRight,
            showIcon = false,
            valueImages = mapOf("2160p" to ref("cd")),
        )
        val custom = base.copy(
            preset = PresetId.Plate,
            order = listOf("rating_imdb", "resolution", "hdr"),
            items = items,
            style = OverlayStyle(
                size = 1.25,
                shape = OverlayShape.Rounded,
                background = "#000000b3",
                textColor = "#ffffff",
                borderColor = "#ffffff26",
                borderWidth = 2,
                fontWeight = OverlayFontWeight.Extrabold,
                uppercase = false,
                shadow = true,
            ),
        )
        assertEquals(custom, OverlaySchema.parse(OverlaySchema.serialize(custom)))
    }

    @Test
    fun serialize_omitsNullOptionals_andSortsKeys() {
        val prefs = OverlaySchema.buildDefaults()
        val json = OverlaySchema.serialize(prefs)
        // Top-level keys are sorted: items, order, preset, version.
        val itemsIdx = json.indexOf("\"items\"")
        val orderIdx = json.indexOf("\"order\"")
        val presetIdx = json.indexOf("\"preset\"")
        val versionIdx = json.indexOf("\"version\"")
        assertTrue(itemsIdx in 0 until orderIdx)
        assertTrue(orderIdx < presetIdx)
        assertTrue(presetIdx < versionIdx)
        // No null optional leaked into the output.
        for (field in listOf("accentColor", "showIcon", "image", "valueImages", "style")) {
            assertFalse(json.contains(field), field)
        }
    }

    // web: "writes a document without new fields exactly as before"
    @Test
    fun serialize_documentWithoutNewFields_isUnchanged() {
        val stored = """{"version":2,"preset":"classic","order":[],"items":{"hdr":{"enabled":true,"position":"top-left"}}}"""
        val reparsed = wire(OverlaySchema.serialize(OverlaySchema.parse(stored)))
        assertEquals(listOf("items", "order", "preset", "version"), reparsed.keys.toList())
        assertEquals(wire("""{"enabled":true,"position":"top-left"}"""), reparsed.getValue("items").jsonObject["hdr"])
    }

    @Test
    fun serialize_writesWholeSizesAsIntegers() {
        val prefs = OverlaySchema.buildDefaults().copy(style = OverlayStyle(size = 2.0))
        assertTrue(OverlaySchema.serialize(prefs).contains("\"style\":{\"size\":2}"))
    }

    // web: "moves a badge stored in the top-right corner to the right edge"
    @Test
    fun parse_movesTopRightToTheRightEdge() {
        val prefs = OverlaySchema.parse(
            """{"version":2,"preset":"classic","order":[],"items":{"rating_imdb":{"enabled":true,"position":"top-right"}}}""",
        )
        assertEquals(OverlayPosition.RightEdge, prefs.items[OverlayId.RatingImdb]?.position)
        assertFalse(OverlaySchema.serialize(prefs).contains("top-right"))
    }

    // web: "keeps the style and images it accepts and drops the rest"
    @Test
    fun parse_keepsTheStyleAndImagesItAccepts_andDropsTheRest() {
        val prefs = OverlaySchema.parse(
            """
            {
              "version": 2,
              "preset": "plate",
              "watchStatus": "watched",
              "style": {"size": 1.5, "shape": "blob", "background": "#000000b3", "textColor": "red", "borderWidth": 9},
              "order": [],
              "items": {
                "resolution": {
                  "enabled": true,
                  "position": "left-edge",
                  "image": "not-a-ref",
                  "valueImages": {"2160p": "${ref("ab")}", "": "${ref("cd")}", "1080p": "nope"}
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals(PresetId.Plate, prefs.preset)
        // Retired, not passed through: a server rejects it.
        assertFalse("watchStatus" in prefs.extras)
        assertEquals(OverlayStyle(size = 1.5, background = "#000000b3"), prefs.style)
        val resolution = prefs.items.getValue(OverlayId.Resolution)
        assertEquals(OverlayPosition.LeftEdge, resolution.position)
        assertEquals(mapOf("2160p" to ref("ab")), resolution.valueImages)
        assertNull(resolution.image)
    }

    @Test
    fun parseStyle_validatesEachFieldLikeWeb() {
        fun style(json: String) = OverlaySchema.parseStyle(Json.parseToJsonElement(json))
        assertNull(style("{}"))
        assertNull(style("""{"size": 0.5, "borderWidth": 1.5, "shape": 1, "uppercase": "true"}"""))
        assertNull(style("[]"))
        assertEquals(OverlayStyle(size = 0.75), style("""{"size": 0.75}"""))
        assertEquals(OverlayStyle(size = 2.0), style("""{"size": 2}"""))
        assertNull(style("""{"size": 2.01}"""))
        assertNull(style("""{"size": true}"""))
        assertEquals(OverlayStyle(borderWidth = 3), style("""{"borderWidth": 3.0}"""))
        assertNull(style("""{"borderWidth": 4}"""))
        assertNull(style("""{"borderWidth": -1}"""))
        assertNull(style("""{"background": "#000000"}"""))
        assertNull(style("""{"textColor": "#00000000"}"""))
        assertNull(style("""{"borderColor": "#fff"}"""))
        assertEquals(
            OverlayStyle(
                shape = OverlayShape.Pill,
                background = "#AABBCCDD",
                textColor = "#abcdef",
                borderColor = "#00000000",
                fontWeight = OverlayFontWeight.Regular,
                uppercase = false,
                shadow = true,
            ),
            style(
                """{"shape":"pill","background":"#AABBCCDD","textColor":"#abcdef","borderColor":"#00000000",
                   "fontWeight":"regular","uppercase":false,"shadow":true}""",
            ),
        )
        assertNull(style("""{"fontWeight": "bold"}"""))
    }

    @Test
    fun parseItem_validatesImagesLikeWeb() {
        fun item(patch: String) = OverlaySchema.parse(
            """{"version":2,"preset":"classic","order":[],"items":{"hdr":$patch}}""",
        ).items.getValue(OverlayId.Hdr)

        assertEquals(ref("ab"), item("""{"enabled":true,"position":"top-left","image":"${ref("ab")}"}""").image)
        // Upper-case hex and other extensions are not refs.
        assertNull(item("""{"enabled":true,"position":"top-left","image":"${ref("AB")}"}""").image)
        assertNull(item("""{"enabled":true,"position":"top-left","image":"${"ab".repeat(32)}.png"}""").image)

        // At most 64 code points per key: an astral character is one.
        val astral64 = "🎬".repeat(64)
        val accepted = item("""{"enabled":true,"position":"top-left","valueImages":{"$astral64":"${ref("ab")}","${"a".repeat(65)}":"${ref("cd")}"}}""")
        assertEquals(mapOf(astral64 to ref("ab")), accepted.valueImages)

        // At most 32 entries, the first ones in document order.
        val many = (1..40).joinToString(",") { "\"k$it\":\"${ref("ef")}\"" }
        val capped = item("""{"enabled":true,"position":"top-left","valueImages":{$many}}""").valueImages!!
        assertEquals(32, capped.size)
        assertEquals((1..32).map { "k$it" }, capped.keys.toList())

        // Nothing valid is no map.
        assertNull(item("""{"enabled":true,"position":"top-left","valueImages":{"":"${ref("ab")}"}}""").valueImages)
    }

    @Test
    fun parse_isStrictAboutJsonTypes() {
        val prefs = OverlaySchema.parse(
            """{"version":2,"preset":"classic","order":[],"items":{"hdr":{"enabled":"false","position":"top-left","showIcon":"true","accentColor":123456}}}""",
        )
        val hdr = prefs.items.getValue(OverlayId.Hdr)
        assertEquals(true, hdr.enabled) // registry default kept
        assertNull(hdr.showIcon)
        assertNull(hdr.accentColor)
    }

    @Test
    fun looksLikeV2_followsWeb() {
        // No version, but a string preset and an items object: V2.
        val noVersion = OverlaySchema.parse("""{"preset":"vibrant","items":{"hdr":{"enabled":false,"position":"top-left"}}}""")
        assertEquals(PresetId.Vibrant, noVersion.preset)
        assertEquals(false, noVersion.items[OverlayId.Hdr]?.enabled)
        // An invalid preset still parses as V2 and falls back to classic.
        assertEquals(PresetId.Classic, OverlaySchema.parse("""{"version":2,"preset":"neon","order":[],"items":{}}""").preset)
    }

    @Test
    fun parseV2_dropsInvalidAccent() {
        val raw = """
            {
              "version": 2,
              "preset": "pill",
              "order": ["resolution"],
              "items": {
                "resolution": {"enabled": true, "position": "top-left", "accentColor": "nothex"},
                "rating_imdb": {"enabled": true, "position": "left-edge", "accentColor": "#abcdef", "showIcon": true}
              }
            }
        """.trimIndent()
        val prefs = OverlaySchema.parse(raw)
        assertEquals(PresetId.Pill, prefs.preset)
        assertNull(prefs.items[OverlayId.Resolution]?.accentColor)
        val imdb = prefs.items[OverlayId.RatingImdb]
        assertEquals("#abcdef", imdb?.accentColor)
        assertEquals(true, imdb?.showIcon)
    }

    @Test
    fun parseV1_migratesFlatDocument() {
        // V1 has no `version` key; it's a flat map of id -> {enabled, position}.
        val raw = """
            {
              "resolution": {"enabled": false, "position": "bottom-right"},
              "rating_imdb": {"enabled": true, "position": "top-right"}
            }
        """.trimIndent()
        val prefs = OverlaySchema.parse(raw)
        assertEquals(CardOverlayPrefs.CURRENT_VERSION, prefs.version)
        assertEquals(false, prefs.items[OverlayId.Resolution]?.enabled)
        assertEquals(OverlayPosition.BottomRight, prefs.items[OverlayId.Resolution]?.position)
        assertEquals(true, prefs.items[OverlayId.RatingImdb]?.enabled)
        assertEquals(OverlayPosition.RightEdge, prefs.items[OverlayId.RatingImdb]?.position)
        // Untouched overlays keep registry defaults.
        assertEquals(
            OverlayRegistry.def(OverlayId.Hdr)?.defaultEnabled,
            prefs.items[OverlayId.Hdr]?.enabled,
        )
    }

    // MARK: - Preservation

    private val foreignDocument = """
        {
          "version": 2,
          "preset": "classic",
          "layoutHint": {"a": 1},
          "style": {"size": 1.2, "glow": "soft"},
          "order": ["advisory_age", "resolution", "future_badge", "request_status"],
          "items": {
            "resolution": {"enabled": true, "position": "top-left", "pulse": true},
            "advisory_age": {"enabled": true, "position": "top-right", "accentColor": "#123456", "image": "${"ab".repeat(32)}.webp"},
            "request_status": {"enabled": false, "position": "bottom-left"},
            "future_badge": {"enabled": true, "position": "left-edge", "mode": {"x": [1, 2]}}
          }
        }
    """.trimIndent()

    @Test
    fun roundTrip_keepsIdsAndFieldsAndroidDoesNotRender() {
        val prefs = OverlaySchema.parse(foreignDocument)
        assertEquals(listOf("advisory_age", "resolution", "future_badge", "request_status"), prefs.order)
        assertEquals(listOf(OverlayId.Resolution), prefs.orderedIds)
        assertEquals(setOf("advisory_age", "request_status", "future_badge"), prefs.passthroughItems.keys)

        val written = wire(OverlaySchema.serialize(prefs))
        val expected = wire(foreignDocument)
        assertEquals(expected["layoutHint"], written["layoutHint"])
        assertEquals(expected["style"], written["style"])
        assertEquals(expected["order"], written["order"])
        val items = written.getValue("items").jsonObject
        assertEquals(wire("""{"enabled":true,"position":"top-left","pulse":true}"""), items["resolution"])
        // Verbatim, but for top-right, which moves to the right edge.
        assertEquals(
            wire("""{"enabled":true,"position":"right-edge","accentColor":"#123456","image":"${"ab".repeat(32)}.webp"}"""),
            items["advisory_age"],
        )
        assertEquals(expected.getValue("items").jsonObject["request_status"], items["request_status"])
        assertEquals(expected.getValue("items").jsonObject["future_badge"], items["future_badge"])
        // Parse and serialize again: a fixed point.
        assertEquals(prefs, OverlaySchema.parse(OverlaySchema.serialize(prefs)))
    }

    @Test
    fun preservation_keepsEqualityAndCopySane() {
        val a = OverlaySchema.parse(foreignDocument)
        val b = OverlaySchema.parse(foreignDocument)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == a.copy(passthroughItems = emptyMap()))
        val edited = a.copy(preset = PresetId.Pill)
        assertEquals(a.passthroughItems, edited.passthroughItems)
        assertEquals(a.extras, edited.extras)
    }

    @Test
    fun storedOverlayIds_listsTheIdsTheValueHolds() {
        assertEquals(
            setOf("resolution", "advisory_age", "request_status", "future_badge"),
            OverlaySchema.storedOverlayIds(foreignDocument),
        )
        assertEquals(setOf("hdr"), OverlaySchema.storedOverlayIds("""{"hdr":{"enabled":true,"position":"top-left"}}"""))
        assertEquals(emptySet(), OverlaySchema.storedOverlayIds("nope"))
        assertEquals(emptySet(), OverlaySchema.storedOverlayIds(null as String?))
    }

    // MARK: - overlayPrefsForServer (web schema.test.ts)

    private val bandPrefs = OverlaySchema.parse(
        """
        {
          "version": 2, "preset": "band", "style": {"size": 1.2}, "order": [],
          "items": {
            "rating_imdb": {"enabled": true, "position": "left-edge", "image": "${"ab".repeat(32)}.webp"},
            "show_status": {"enabled": true, "position": "right-edge"},
            "resolution_hdr_audio": {"enabled": true, "position": "top-left"}
          }
        }
        """.trimIndent(),
    )

    @Test
    fun forServer_belowRevision25_handsOnlyWhatItAccepts() {
        val storable = OverlaySchema.overlayPrefsForServer(bandPrefs, OverlayServerSupport(24, emptySet()))
        assertEquals("classic", storable.getValue("preset").jsonPrimitive.content)
        assertFalse("style" in storable)
        val items = storable.getValue("items").jsonObject
        assertEquals(wire("""{"enabled":true,"position":"top-left"}"""), items["rating_imdb"])
        assertEquals("top-right", items.getValue("show_status").jsonObject.getValue("position").jsonPrimitive.content)
        assertFalse("resolution_hdr_audio" in items)
        // The string entry point writes the same document.
        assertEquals(storable, wire(OverlaySchema.serialize(bandPrefs, OverlayServerSupport(24, emptySet()))))
    }

    @Test
    fun forServer_unknownRevision_isTreatedAsLegacy_butKeepsStoredIds() {
        val support = OverlayServerSupport(null, setOf("resolution_hdr_audio"))
        val storable = OverlaySchema.overlayPrefsForServer(bandPrefs, support)
        assertFalse(OverlaySchema.supportsOverlayStyles(support))
        assertEquals("classic", storable.getValue("preset").jsonPrimitive.content)
        assertTrue("resolution_hdr_audio" in storable.getValue("items").jsonObject)
        val none = OverlaySchema.overlayPrefsForServer(bandPrefs, OverlayServerSupport(null, emptySet()))
        assertFalse("resolution_hdr_audio" in none.getValue("items").jsonObject)
    }

    @Test
    fun forServer_atRevision25_dropsOnlyTheRevision26Badge() {
        val storable = OverlaySchema.overlayPrefsForServer(bandPrefs, OverlayServerSupport(25, emptySet()))
        val full = wire(OverlaySchema.serialize(bandPrefs))
        val expectedItems = JsonObject(full.getValue("items").jsonObject - "resolution_hdr_audio")
        assertEquals(JsonObject(full + ("items" to expectedItems)), storable)
    }

    @Test
    fun forServer_atRevision26_keepsEverything() {
        val storable = OverlaySchema.overlayPrefsForServer(bandPrefs, OverlayServerSupport(26, emptySet()))
        assertEquals(wire(OverlaySchema.serialize(bandPrefs)), storable)
    }

    @Test
    fun forServer_dropsUnsupportedIdsFromOrder_andKeepsPassthroughIds() {
        val prefs = OverlaySchema.parse(foreignDocument).let {
            it.copy(order = it.order + "resolution_hdr_audio")
        }
        val legacy = OverlaySchema.overlayPrefsForServer(prefs, OverlayServerSupport(24, emptySet()))
        assertEquals(
            listOf("advisory_age", "resolution", "future_badge", "request_status"),
            legacy.getValue("order").jsonArray.map { it.jsonPrimitive.content },
        )
        val items = legacy.getValue("items").jsonObject
        // Passthrough entries get the same legacy mapping, and keep unknown fields.
        assertEquals(wire("""{"enabled":true,"position":"top-right","accentColor":"#123456"}"""), items["advisory_age"])
        assertEquals(wire("""{"enabled":true,"position":"top-left","mode":{"x":[1,2]}}"""), items["future_badge"])
        assertEquals(wire("""{"enabled":true,"position":"top-left","pulse":true}"""), items["resolution"])
        // Root unknown fields stay; style (with its unknown fields) goes.
        assertTrue("layoutHint" in legacy)
        assertFalse("style" in legacy)
    }

    @Test
    fun isOverlaySupportedBy_followsIntroducedInManifest() {
        val id = OverlayId.ResolutionHdrAudio
        assertFalse(OverlaySchema.isOverlaySupportedBy(id, OverlayServerSupport(25)))
        assertTrue(OverlaySchema.isOverlaySupportedBy(id, OverlayServerSupport(26)))
        assertFalse(OverlaySchema.isOverlaySupportedBy(id, OverlayServerSupport(null)))
        assertTrue(OverlaySchema.isOverlaySupportedBy(id, OverlayServerSupport(null, setOf("resolution_hdr_audio"))))
        assertTrue(OverlaySchema.isOverlaySupportedBy(OverlayId.Hdr, OverlayServerSupport(null)))
        assertTrue(OverlaySchema.supportsOverlayStyles(OverlayServerSupport(OVERLAY_STYLE_MANIFEST_REVISION)))
        assertFalse(OverlaySchema.supportsOverlayStyles(OverlayServerSupport(24)))
    }

    // MARK: - withoutUnlistedImages (web schema.test.ts)

    @Test
    fun withoutUnlistedImages_dropsRefsTheLibraryNoLongerLists() {
        val prefs = OverlaySchema.parse(
            """
            {"version":2,"preset":"classic","order":[],"items":{"resolution":{"enabled":true,"position":"top-left",
              "image":"${ref("aa")}","valueImages":{"2160p":"${ref("bb")}","1080p":"${ref("cc")}"}}}}
            """.trimIndent(),
        )
        val rendered = OverlaySchema.withoutUnlistedImages(prefs, setOf(ref("bb")))
        val resolution = rendered.items.getValue(OverlayId.Resolution)
        assertNull(resolution.image)
        assertEquals(mapOf("2160p" to ref("bb")), resolution.valueImages)
        // Nothing to drop returns the same document, so remembered cards keep it.
        assertSame(rendered, OverlaySchema.withoutUnlistedImages(rendered, setOf(ref("bb"))))
        // Every value image gone is no map.
        assertNull(OverlaySchema.withoutUnlistedImages(prefs, emptySet()).items.getValue(OverlayId.Resolution).valueImages)
    }

    @Test
    fun v1Migration_keepsIdsOutsideTheRegistry() {
        val v1 = """{"resolution":{"enabled":false,"position":"top-left"},
            "advisory_age":{"enabled":true,"position":"top-right"}}"""
        val prefs = OverlaySchema.parse(v1)
        assertEquals(false, prefs.items.getValue(OverlayId.Resolution).enabled)
        val written = kotlinx.serialization.json.Json.parseToJsonElement(OverlaySchema.serialize(prefs))
        assertEquals(
            """{"enabled":true,"position":"right-edge"}""",
            (written as kotlinx.serialization.json.JsonObject)["items"]!!
                .let { it as kotlinx.serialization.json.JsonObject }["advisory_age"].toString(),
        )
    }
}
