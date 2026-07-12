package org.siloserver.silo.tv.ui.screens.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSettingsUsabilityTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings/TvSettingsScreen.kt",
    ).readText()

    @Test
    fun settingsContentClearsTopMenuAndFocusedCardScale() {
        assertTrue(source.contains("start = 44.dp"))
        assertTrue(source.contains("top = Spacing.safeArea"))
        assertTrue(source.contains("end = 44.dp"))
    }

    @Test
    fun settingsUseTvOsSplitRailDetailLayout() {
        assertTrue(source.contains("SettingsRail("))
        assertTrue(source.contains("SettingsDetailPane("))
        assertTrue(source.contains("selectedCategory"))
        assertTrue(source.contains("enum class TvSettingsCategory"))
    }

    @Test
    fun settingsNoLongerCarryOldStackedSubScreens() {
        assertFalse(source.contains("private fun SettingsRootMenu("))
        assertFalse(source.contains("private fun TvPlaybackSettingsScreen("))
        assertFalse(source.contains("private fun TvSubtitleSettingsScreen("))
        assertFalse(source.contains("private fun TvSettingsSubScreenScaffold("))
    }

    @Test
    fun splitSettingsUseCompactTvDensity() {
        assertTrue(source.contains("modifier = Modifier.width(200.dp)"))
        assertTrue(source.contains("private val RowMaxWidth = 520.dp"))
        assertTrue(source.contains("private val RowHeight = 38.dp"))
        assertTrue(source.contains(".focusGroup()"))
    }
}
