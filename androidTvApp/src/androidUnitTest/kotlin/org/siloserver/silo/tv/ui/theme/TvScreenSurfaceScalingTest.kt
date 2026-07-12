package org.siloserver.silo.tv.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvScreenSurfaceScalingTest {
    private val watchTogetherEntryDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherEntryDialog.kt",
    ).readText()

    private val cardOverlaySettings = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings/TvCardOverlaySettingsScreen.kt",
    ).readText()

    private val manageSessions = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings/TvManageSessionsScreen.kt",
    ).readText()

    private val searchScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/search/TvSearchScreen.kt",
    ).readText()

    private val requestsScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/requests/TvRequestsScreen.kt",
    ).readText()

    private val pairDeviceScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/auth/TvPairDeviceScreen.kt",
    ).readText()

    private val profileForm = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/profiles/TvProfileForm.kt",
    ).readText()

    private val itemDetailScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
    ).readText()

    private val legacyEpisodeRow = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvEpisodeRow.kt",
    )

    @Test
    fun watchTogetherEntryDialogUsesCompactTvPanel() {
        assertTrue(watchTogetherEntryDialog.contains(".width(340.dp)"))
        assertFalse(watchTogetherEntryDialog.contains(".width(520.dp)"))
    }

    @Test
    fun cardOverlayDetailSectionsUseTvOsMappedWidth() {
        assertTrue(cardOverlaySettings.contains(".widthIn(max = 480.dp)"))
        assertTrue(cardOverlaySettings.contains(".padding(horizontal = 20.dp)"))
        assertTrue(cardOverlaySettings.contains("verticalArrangement = Arrangement.spacedBy(8.dp)"))
        assertFalse(cardOverlaySettings.contains(".widthIn(max = 900.dp)"))
        assertFalse(cardOverlaySettings.contains(".padding(horizontal = 40.dp)"))
    }

    @Test
    fun cardOverlayDetailControlsUseTvOsMappedScale() {
        assertTrue(cardOverlaySettings.contains("start = 50.dp"))
        assertTrue(cardOverlaySettings.contains("end = 50.dp"))
        assertTrue(cardOverlaySettings.contains("verticalArrangement = Arrangement.spacedBy(18.dp)"))
        assertTrue(cardOverlaySettings.contains("horizontalArrangement = Arrangement.spacedBy(25.dp)"))
        assertTrue(cardOverlaySettings.contains("GridCells.Adaptive(minSize = 60.dp)"))
        assertTrue(cardOverlaySettings.contains(".height(130.dp)"))
        assertTrue(cardOverlaySettings.contains(".size(36.dp)"))
        assertTrue(cardOverlaySettings.contains(".widthIn(min = 80.dp)"))
        assertFalse(cardOverlaySettings.contains("start = 100.dp"))
        assertFalse(cardOverlaySettings.contains("end = 100.dp"))
        assertFalse(cardOverlaySettings.contains("GridCells.Adaptive(minSize = 120.dp)"))
        assertFalse(cardOverlaySettings.contains(".height(220.dp)"))
        assertFalse(cardOverlaySettings.contains(".size(64.dp)"))
        assertFalse(cardOverlaySettings.contains(".widthIn(min = 160.dp)"))
    }

    @Test
    fun sessionRowsUseTvOsMappedListGeometry() {
        assertTrue(manageSessions.contains(".widthIn(max = 480.dp)"))
        assertTrue(manageSessions.contains(".height(48.dp)"))
        assertTrue(manageSessions.contains(".padding(horizontal = 14.dp, vertical = 8.dp)"))
        assertFalse(manageSessions.contains(".widthIn(max = 960.dp)"))
        assertFalse(manageSessions.contains(".height(96.dp)"))
    }

    @Test
    fun searchAndRequestInputsUseTvOsMappedWidths() {
        assertTrue(searchScreen.contains(".width(520.dp)"))
        assertTrue(requestsScreen.contains("modifier = Modifier.widthIn(max = 410.dp)"))
        assertFalse(searchScreen.contains(".width(760.dp)"))
        assertFalse(requestsScreen.contains("modifier = Modifier.widthIn(max = 820.dp)"))
    }

    @Test
    fun authAndProfileFormsUseTvOsMappedWidths() {
        assertTrue(pairDeviceScreen.contains(".widthIn(max = 360.dp)"))
        assertTrue(profileForm.contains("private val ProfileEditorPreviewWidth = 180.dp"))
        assertTrue(profileForm.contains("private val ProfileEditorColumnWidth = 584.dp"))
        assertFalse(pairDeviceScreen.contains(".widthIn(max = 720.dp)"))
        assertFalse(profileForm.contains(".width(840.dp)"))
    }

    @Test
    fun legacyOversizedEpisodeRowIsRemoved() {
        assertFalse(legacyEpisodeRow.exists())
    }

    @Test
    fun detailVersionPlaceholderUsesTvOsMappedHairline() {
        assertTrue(itemDetailScreen.contains(".border(0.6.dp, Color.White.copy(alpha = 0.16f), shape)"))
        assertFalse(itemDetailScreen.contains(".border(1.2.dp, Color.White.copy(alpha = 0.16f), shape)"))
    }
}
