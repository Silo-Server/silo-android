package com.continuum.app.tv.ui.screens.player

import com.continuum.app.model.settings.SubtitleBackgroundStylePreset
import kotlin.test.Test
import kotlin.test.assertEquals

class TvSubtitleAppearanceOptionsTest {
    @Test
    fun backgroundStylePickerLeadsWithNoBackground() {
        assertEquals(
            SubtitleBackgroundStylePreset.None to "No background",
            TvSubtitleAppearanceOptions.BACKGROUND_STYLES.first(),
        )
        assertEquals(
            "No background",
            TvSubtitleAppearanceOptions.backgroundStyleLabel(SubtitleBackgroundStylePreset.None),
        )
    }
}
