package org.siloserver.silo.model.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubtitleAppearanceTest {

    @Test
    fun subtitleFontSizePresetsUseTheStandardScale() {
        assertEquals(36.0, SubtitleFontSizePreset.Small.pointSize)
        assertEquals(44.0, SubtitleFontSizePreset.Medium.pointSize)
        assertEquals(56.0, SubtitleFontSizePreset.Large.pointSize)
        assertEquals(68.0, SubtitleFontSizePreset.XLarge.pointSize)
        assertEquals(82.0, SubtitleFontSizePreset.XXLarge.pointSize)
    }

    @Test
    fun defaultSubtitleAppearanceKeepsTheStandardLargePreset() {
        assertEquals(SubtitleFontSizePreset.Large, SubtitleAppearance.DEFAULT.fontSize)
        assertEquals(56.0, SubtitleAppearance.DEFAULT.fontSize.pointSize)
    }

    @Test
    fun defaultSubtitleAppearanceMatchesTheTvReferenceStyle() {
        assertEquals("#ffffff", SubtitleAppearance.DEFAULT.fontColor)
        assertEquals(SubtitleAppearance.SANS_SERIF, SubtitleAppearance.DEFAULT.fontFamily)
        assertEquals(SubtitleBackgroundStylePreset.Shadow, SubtitleAppearance.DEFAULT.backgroundStyle)
        assertEquals(false, SubtitleAppearance.DEFAULT.textOutline)
        assertEquals("#000000", SubtitleAppearance.DEFAULT.textOutlineColor)
        assertEquals(SubtitlePositionPreset.Bottom, SubtitleAppearance.DEFAULT.position)
    }

    @Test
    fun decodingMissingBackgroundStyleUsesTheDefaultStyle() {
        val decoded = SubtitleAppearance.decode(
            """
            {
              "fontSize": "large",
              "fontFamily": "sans-serif",
              "fontColor": "#ffffff",
              "backgroundColor": "#000000",
              "backgroundOpacity": 75,
              "textOutline": false,
              "textOutlineColor": "#000000",
              "position": "bottom"
            }
            """.trimIndent(),
        )

        assertEquals(SubtitleBackgroundStylePreset.Shadow, decoded.backgroundStyle)
    }

    @Test
    fun textOpacityIsRemovedFromTheWireObjectBelowRevision14() {
        val wire = wireObject(SubtitleAppearance.DEFAULT.copy(textOpacity = 40))

        val forOldServer = SubtitleAppearance.wireObjectForRevision(wire, manifestRevision = 13)

        assertFalse(SubtitleAppearance.TEXT_OPACITY_FIELD in forOldServer)
        // Every other field still reaches the server.
        assertEquals(wire - SubtitleAppearance.TEXT_OPACITY_FIELD, forOldServer.toMap())
    }

    @Test
    fun textOpacityIsKeptInTheWireObjectFromRevision14() {
        val wire = wireObject(SubtitleAppearance.DEFAULT.copy(textOpacity = 40))

        assertSame(wire, SubtitleAppearance.wireObjectForRevision(wire, manifestRevision = 14))
        assertTrue(SubtitleAppearance.supportsTextOpacity(14))
        assertFalse(SubtitleAppearance.supportsTextOpacity(13))
    }

    private fun wireObject(appearance: SubtitleAppearance): JsonObject =
        Json.parseToJsonElement(appearance.toJsonString()).jsonObject
}
