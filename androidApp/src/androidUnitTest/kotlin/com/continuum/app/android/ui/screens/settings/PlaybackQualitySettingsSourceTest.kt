package com.continuum.app.android.ui.screens.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PlaybackQualitySettingsSourceTest {
    private val settingsSource = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/settings/PlaybackSettings.kt",
    ).readText()
    private val viewModelSource = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/settings/SettingsViewModel.kt",
    ).readText()

    @Test
    fun mobilePlaybackSettingsExpose4kAndPersist2160pWireValue() {
        assertTrue(settingsSource.contains("listOf(\"Auto\", \"Original\", \"4K\", \"1080p\", \"720p\", \"480p\")"))
        assertTrue(viewModelSource.contains("\"2160p\", \"4k\" -> \"4K\""))
        assertTrue(viewModelSource.contains("\"4K\" -> \"2160p\""))
    }
}
