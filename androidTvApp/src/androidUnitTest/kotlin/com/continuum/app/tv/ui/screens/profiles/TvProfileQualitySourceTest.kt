package com.continuum.app.tv.ui.screens.profiles

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvProfileQualitySourceTest {
    private val createViewModel = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/profiles/TvCreateProfileViewModel.kt",
    ).readText()
    private val editViewModel = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/profiles/TvEditProfileViewModel.kt",
    ).readText()
    @Test
    fun tvProfileFormsPersistCanonicalQualityWireValues() {
        assertTrue(createViewModel.contains("canonicalProfileQualityPreference(quality)"))
        assertTrue(editViewModel.contains("canonicalProfileQualityPreference(quality)"))
        assertFalse(createViewModel.contains("if (quality == \"Auto\") null else quality"))
        assertFalse(editViewModel.contains("if (quality == \"Auto\") null else quality"))
    }
}
