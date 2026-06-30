package com.continuum.app.android.ui.screens.profiles

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileQualitySourceTest {
    private val createViewModel = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/profiles/CreateProfileViewModel.kt",
    ).readText()
    private val editViewModel = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/profiles/EditProfileViewModel.kt",
    ).readText()
    private val createScreen = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/profiles/CreateProfileScreen.kt",
    ).readText()
    private val editScreen = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/profiles/EditProfileScreen.kt",
    ).readText()

    @Test
    fun mobileProfileFormsPersistCanonicalQualityWireValues() {
        assertTrue(createViewModel.contains("canonicalProfileQualityPreference(quality)"))
        assertTrue(editViewModel.contains("canonicalProfileQualityPreference(quality)"))
        assertFalse(createViewModel.contains("if (quality == \"Auto\") null else quality"))
        assertFalse(editViewModel.contains("if (quality == \"Auto\") null else quality"))
        assertTrue(createScreen.contains("displayProfileQualityPreference(state.qualityPreference)"))
        assertTrue(editScreen.contains("displayProfileQualityPreference(state.qualityPreference)"))
    }
}
