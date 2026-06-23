package com.continuum.app.tv.ui.screens.profiles

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvProfileSelectionScreenSourceTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/profiles/TvProfileSelectionScreen.kt",
    ).readText()

    @Test
    fun profilePickerUsesTvosHeaderCopyAndUtilityChips() {
        assertTrue(source.contains("\"Who's watching?\""))
        assertTrue(source.contains("\"Select your profile\""))
        assertFalse(
            source.contains("text = \"Profiles\""),
            "tvOS profile picker has no eyebrow above the title.",
        )
        assertTrue(source.contains("\"Change Server\""))
        assertTrue(source.contains("\"Sign Out\""))
        assertFalse(
            source.contains("\"Manage Profiles\""),
            "tvOS profile picker exposes Change Server and Sign Out, not a Manage Profiles button.",
        )
    }

    @Test
    fun utilityChipsAreInTopChromeNotTitleBand() {
        assertTrue(source.contains("private val ProfileUtilityChromeTop = 40.dp"))
        assertTrue(source.contains("private val ProfileHeaderTop = 92.dp"))
        assertTrue(source.contains("private val ProfileUtilityChangeServerWidth = 164.dp"))
        assertTrue(source.contains("private val ProfileUtilitySignOutWidth = 100.dp"))
    }

    @Test
    fun addProfileTileWrapsLikeTvosGridAndUsesDashedBorder() {
        assertTrue(
            source.contains("private const val ProfileGridColumns = 4"),
            "tvOS wraps Add Profile below the four profile tiles.",
        )
        assertTrue(
            source.contains("PathEffect.dashPathEffect"),
            "tvOS Add Profile uses a dashed rounded border.",
        )
    }

    @Test
    fun addProfileTileUsesTvosNeutralSurfaceAndDrawnLightweightPlus() {
        assertTrue(
            source.contains("private val ProfileTileSize = 140.dp"),
            "Android TV's 140dp tile corresponds to tvOS' 280pt Add Profile tile.",
        )
        assertTrue(
            source.contains("drawLine("),
            "tvOS uses a lightweight system plus; Android should draw thin plus strokes instead of the chunky Material add glyph.",
        )
        assertFalse(
            source.contains("imageVector = Icons.Filled.Add"),
            "The Material Add icon does not match tvOS' light-weight plus.",
        )
        assertTrue(
            source.contains("0.14f") && source.contains("0.06f"),
            "tvOS Add Profile uses a neutral 14% focused / 6% resting white surface.",
        )
    }
}
