package org.siloserver.silo.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The projection is what makes a per-field subtitle edit reach the server at
 * all: the contract has one composite object and no definitions for the
 * granular `subtitle.*` fields, so a field the projection drops is a
 * preference that silently never syncs.
 */
class SubtitleAppearanceProjectionTest {

    @Test
    fun `every field projects onto the composite`() {
        val projected = SubtitleAppearanceProjection.project(
            mapOf(
                PlaybackSettingsKeys.SubtitleFontSize to "xxlarge",
                PlaybackSettingsKeys.SubtitleFontFamily to "Avenir Next",
                PlaybackSettingsKeys.SubtitleTextColor to "#ffee00",
                PlaybackSettingsKeys.SubtitleBackgroundColor to "#101010",
                PlaybackSettingsKeys.SubtitleBackgroundStyle to "box",
                PlaybackSettingsKeys.SubtitleBackgroundOpacity to "40",
                PlaybackSettingsKeys.SubtitleTextOutline to "true",
                PlaybackSettingsKeys.SubtitleTextOutlineColor to "#001122",
                PlaybackSettingsKeys.SubtitlePosition to "lower-third",
            ),
        )

        assertEquals(SubtitleFontSizePreset.XXLarge, projected.fontSize)
        assertEquals("Avenir Next", projected.fontFamily)
        assertEquals("#ffee00", projected.fontColor)
        assertEquals("#101010", projected.backgroundColor)
        assertEquals(SubtitleBackgroundStylePreset.Box, projected.backgroundStyle)
        assertEquals(40, projected.backgroundOpacity)
        assertEquals(true, projected.textOutline)
        assertEquals("#001122", projected.textOutlineColor)
        assertEquals(SubtitlePositionPreset.LowerThird, projected.position)
    }

    @Test
    fun `projection is sparse over the base`() {
        val base = SubtitleAppearance.DEFAULT.copy(
            fontSize = SubtitleFontSizePreset.Small,
            fontColor = "#123456",
            backgroundOpacity = 20,
        )
        // Only one field set; the rest of the base must survive untouched,
        // matching the schema's "a stored value is a sparse override" rule.
        val projected = SubtitleAppearanceProjection.project(
            mapOf(PlaybackSettingsKeys.SubtitleTextOutline to "true"),
            base,
        )

        assertEquals(SubtitleFontSizePreset.Small, projected.fontSize)
        assertEquals("#123456", projected.fontColor)
        assertEquals(20, projected.backgroundOpacity)
        assertEquals(true, projected.textOutline)
    }

    @Test
    fun `a bad or absent field leaves the base value alone`() {
        val base = SubtitleAppearance.DEFAULT.copy(
            fontSize = SubtitleFontSizePreset.XLarge,
            fontColor = "#abcdef",
            position = SubtitlePositionPreset.Top,
        )
        val projected = SubtitleAppearanceProjection.project(
            mapOf(
                PlaybackSettingsKeys.SubtitleFontSize to "enormous",
                PlaybackSettingsKeys.SubtitleTextColor to "not-a-color",
                PlaybackSettingsKeys.SubtitleFontFamily to "   ",
                PlaybackSettingsKeys.SubtitleTextOutline to "yes",
                PlaybackSettingsKeys.SubtitlePosition to null,
            ),
            base,
        )

        assertEquals(SubtitleFontSizePreset.XLarge, projected.fontSize)
        assertEquals("#abcdef", projected.fontColor)
        assertEquals(base.fontFamily, projected.fontFamily)
        assertEquals(base.textOutline, projected.textOutline)
        assertEquals(SubtitlePositionPreset.Top, projected.position)
    }

    @Test
    fun `opacity is clamped rather than dropped`() {
        assertEquals(
            100,
            SubtitleAppearanceProjection.project(
                mapOf(PlaybackSettingsKeys.SubtitleBackgroundOpacity to "180"),
            ).backgroundOpacity,
        )
        assertEquals(
            0,
            SubtitleAppearanceProjection.project(
                mapOf(PlaybackSettingsKeys.SubtitleBackgroundOpacity to "-5"),
            ).backgroundOpacity,
        )
    }

    @Test
    fun `the legacy numeric position maps onto the enum`() {
        fun positionFor(raw: String) = SubtitleAppearanceProjection.project(
            mapOf(PlaybackSettingsKeys.SubtitlePosition to raw),
        ).position

        assertEquals(SubtitlePositionPreset.Top, positionFor("0"))
        assertEquals(SubtitlePositionPreset.LowerThird, positionFor("70"))
        assertEquals(SubtitlePositionPreset.Bottom, positionFor("100"))
    }

    @Test
    fun `flatten then project is the identity`() {
        val appearance = SubtitleAppearance(
            fontSize = SubtitleFontSizePreset.XLarge,
            fontFamily = "monospace",
            fontColor = "#00ff00",
            backgroundColor = "#220011",
            backgroundStyle = SubtitleBackgroundStylePreset.Outline,
            backgroundOpacity = 33,
            textOutline = true,
            textOutlineColor = "#334455",
            position = SubtitlePositionPreset.Top,
        )

        // Projecting over the DEFAULT base, not over the appearance itself:
        // a field flatten forgot would fall back to the default and be caught.
        assertEquals(
            appearance,
            SubtitleAppearanceProjection.project(
                SubtitleAppearanceProjection.flatten(appearance),
                SubtitleAppearance.DEFAULT,
            ),
        )
    }

    @Test
    fun `flatten covers every granular key`() {
        assertEquals(
            SubtitleAppearanceProjection.GRANULAR_KEYS.toSet(),
            SubtitleAppearanceProjection.flatten(SubtitleAppearance.DEFAULT).keys,
        )
    }

    @Test
    fun `flattened enum values are the schema's spellings`() {
        val flat = SubtitleAppearanceProjection.flatten(
            SubtitleAppearance.DEFAULT.copy(
                fontSize = SubtitleFontSizePreset.XXLarge,
                backgroundStyle = SubtitleBackgroundStylePreset.None,
                position = SubtitlePositionPreset.LowerThird,
            ),
        )
        assertEquals("xxlarge", flat[PlaybackSettingsKeys.SubtitleFontSize])
        assertEquals("none", flat[PlaybackSettingsKeys.SubtitleBackgroundStyle])
        assertEquals("lower-third", flat[PlaybackSettingsKeys.SubtitlePosition])
    }
}
