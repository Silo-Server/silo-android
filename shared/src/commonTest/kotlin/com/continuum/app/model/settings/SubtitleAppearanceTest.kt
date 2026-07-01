package com.continuum.app.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleAppearanceTest {

    @Test
    fun subtitleFontSizePresetsAreCalibratedOneStepSmaller() {
        assertEquals(36.0, SubtitleFontSizePreset.Small.pointSize)
        assertEquals(44.0, SubtitleFontSizePreset.Medium.pointSize)
        assertEquals(56.0, SubtitleFontSizePreset.Large.pointSize)
        assertEquals(68.0, SubtitleFontSizePreset.XLarge.pointSize)
        assertEquals(82.0, SubtitleFontSizePreset.XXLarge.pointSize)
    }

    @Test
    fun defaultSubtitleAppearanceKeepsLargePresetAtSaferRenderedSize() {
        assertEquals(SubtitleFontSizePreset.Large, SubtitleAppearance.DEFAULT.fontSize)
        assertEquals(56.0, SubtitleAppearance.DEFAULT.fontSize.pointSize)
    }

    @Test
    fun defaultSubtitleAppearanceUsesNoBackground() {
        assertEquals(SubtitleBackgroundStylePreset.None, SubtitleAppearance.DEFAULT.backgroundStyle)
    }
}
