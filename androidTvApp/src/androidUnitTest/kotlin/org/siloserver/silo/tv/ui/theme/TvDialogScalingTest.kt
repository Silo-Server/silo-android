package org.siloserver.silo.tv.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvDialogScalingTest {
    private val settingsScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings/TvSettingsScreen.kt",
    ).readText()

    private val textInputDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvTextInputDialog.kt",
    ).readText()

    private val pinEntryDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvPinEntryDialog.kt",
    ).readText()

    private val holdSeekIndicator = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvHoldSeekIndicator.kt",
    ).readText()

    private val optionDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvOptionDialog.kt",
    ).readText()

    private val ratingDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvRatingDialog.kt",
    ).readText()

    private val mediaInfoDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvMediaInfoDialog.kt",
    ).readText()

    private val aiTranslateDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvAiTranslateDialog.kt",
    ).readText()

    private val subtitleSearchDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvSubtitleSearchDialog.kt",
    ).readText()

    private val audiobookOverlay = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/audiobook/TvAudiobookOverlay.kt",
    ).readText()

    private val watchTogetherEntryDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherEntryDialog.kt",
    ).readText()

    private val joinCodeDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvJoinCodeDialog.kt",
    ).readText()

    @Test
    fun settingsConfirmDialogUsesTvOsMappedCompactPanel() {
        assertTrue(settingsScreen.contains(".width(320.dp)"))
        assertTrue(settingsScreen.contains(".clip(RoundedCornerShape(10.dp))"))
        assertTrue(settingsScreen.contains(".padding(20.dp)"))
        assertTrue(settingsScreen.contains("verticalArrangement = Arrangement.spacedBy(8.dp)"))
        assertTrue(settingsScreen.contains("style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 22.sp)"))
        assertTrue(settingsScreen.contains("style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp)"))
        assertFalse(settingsScreen.contains(".width(640.dp)"))
        assertFalse(settingsScreen.contains(".padding(40.dp)"))
    }

    @Test
    fun textInputDialogUsesTvOsMappedCompactPanel() {
        assertTrue(textInputDialog.contains(".width(320.dp)"))
        assertTrue(textInputDialog.contains(".padding(24.dp)"))
        assertTrue(textInputDialog.contains("verticalArrangement = Arrangement.spacedBy(12.dp)"))
        assertTrue(textInputDialog.contains(".height(56.dp)"))
        assertTrue(textInputDialog.contains("OutlinedTextField("))
        assertTrue(textInputDialog.contains("KeyboardOptions("))
        assertFalse(textInputDialog.contains(".width(640.dp)"))
        assertFalse(textInputDialog.contains(".height(82.dp)"))
        assertFalse(textInputDialog.contains("TvAnsiKeyboard("))
    }

    @Test
    fun pinEntryDialogUsesTvOsMappedCompactKeypad() {
        assertTrue(pinEntryDialog.contains(".width(310.dp)"))
        assertTrue(pinEntryDialog.contains(".padding(horizontal = 28.dp, vertical = 22.dp)"))
        assertTrue(pinEntryDialog.contains("Row(horizontalArrangement = Arrangement.spacedBy(12.dp))"))
        assertTrue(pinEntryDialog.contains(".size(12.dp)"))
        assertTrue(pinEntryDialog.contains("Column(verticalArrangement = Arrangement.spacedBy(8.dp))"))
        assertTrue(pinEntryDialog.contains("Modifier.size(48.dp)"))
        assertTrue(pinEntryDialog.contains("fontSize = 32.sp"))
        assertFalse(pinEntryDialog.contains(".width(520.dp)"))
        assertFalse(pinEntryDialog.contains("Modifier.size(96.dp)"))
    }

    @Test
    fun playerTransientOverlaysAvoidLegacyOversizedPanels() {
        assertTrue(holdSeekIndicator.contains("verticalArrangement = Arrangement.spacedBy(8.dp)"))
        assertTrue(holdSeekIndicator.contains("modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)"))
        assertTrue(holdSeekIndicator.contains("fontSize = 22.sp"))
        assertTrue(holdSeekIndicator.contains("fontSize = 14.sp"))
        assertTrue(holdSeekIndicator.contains(".width(260.dp)"))
        assertFalse(holdSeekIndicator.contains("fontSize = 44.sp"))
        assertFalse(holdSeekIndicator.contains(".width(520.dp)"))

        assertTrue(aiTranslateDialog.contains(".width(340.dp)"))
        assertTrue(subtitleSearchDialog.contains(".width(340.dp)"))
        assertFalse(aiTranslateDialog.contains(".width(520.dp)"))
        assertFalse(subtitleSearchDialog.contains(".width(560.dp)"))
    }

    @Test
    fun popupDialogHostsUseTvOsMappedOuterInsetsAndChrome() {
        assertTrue(optionDialog.contains(".padding(start = 36.dp, top = 60.dp, end = 36.dp, bottom = 42.dp)"))
        assertTrue(ratingDialog.contains(".padding(start = 36.dp, top = 60.dp, end = 36.dp, bottom = 42.dp)"))
        assertTrue(aiTranslateDialog.contains(".padding(start = 36.dp, top = 50.dp, end = 36.dp, bottom = 42.dp)"))
        assertTrue(subtitleSearchDialog.contains(".padding(start = 36.dp, top = 50.dp, end = 36.dp, bottom = 42.dp)"))
        assertTrue(watchTogetherEntryDialog.contains(".padding(start = 36.dp, top = 50.dp, end = 36.dp, bottom = 42.dp)"))
        assertTrue(joinCodeDialog.contains(".padding(start = 36.dp, top = 40.dp, end = 36.dp, bottom = 30.dp)"))

        listOf(
            optionDialog,
            ratingDialog,
            aiTranslateDialog,
            subtitleSearchDialog,
            watchTogetherEntryDialog,
            joinCodeDialog,
        )
            .forEach { source ->
                assertTrue(source.contains("RoundedCornerShape(14.dp)"))
                assertTrue(source.contains(".border(0.6.dp"))
                assertFalse(source.contains("RoundedCornerShape(28.dp)"))
                assertFalse(source.contains(".border(1.2.dp"))
            }
    }

    @Test
    fun optionDialogRowsUseCompactTvOsMappedScale() {
        assertTrue(optionDialog.contains(".width(320.dp)"))
        assertTrue(optionDialog.contains(".padding(horizontal = 12.dp, vertical = 12.dp)"))
        assertTrue(optionDialog.contains("verticalArrangement = Arrangement.spacedBy(8.dp)"))
        assertTrue(optionDialog.contains(".heightIn(max = 240.dp)"))
        assertTrue(optionDialog.contains("verticalArrangement = Arrangement.spacedBy(4.dp)"))
        assertTrue(optionDialog.contains("scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)"))
        assertTrue(optionDialog.contains(".heightIn(min = 40.dp)"))
        assertTrue(optionDialog.contains(".padding(horizontal = 12.dp, vertical = 7.dp)"))
        assertTrue(optionDialog.contains("fontSize = 15.sp"))
        assertTrue(optionDialog.contains("fontSize = 13.sp"))
        assertFalse(optionDialog.contains(".width(340.dp)"))
        assertFalse(optionDialog.contains(".heightIn(min = 52.dp)"))
        assertFalse(optionDialog.contains("fontSize = 18.sp"))
        assertFalse(optionDialog.contains("fontSize = 16.sp"))
        assertFalse(optionDialog.contains("focusedScale = 1.025f"))
    }

    @Test
    fun mediaInfoDialogUsesCompactTvSideSheet() {
        assertTrue(mediaInfoDialog.contains(".fillMaxWidth(0.36f)"))
        assertTrue(mediaInfoDialog.contains(".fillMaxHeight(0.88f)"))
        assertTrue(mediaInfoDialog.contains("RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)"))
        assertTrue(mediaInfoDialog.contains(".border(0.6.dp"))
        assertTrue(mediaInfoDialog.contains(".padding(horizontal = 20.dp, vertical = 22.dp)"))
        assertTrue(mediaInfoDialog.contains("verticalArrangement = Arrangement.spacedBy(9.dp)"))
        assertTrue(mediaInfoDialog.contains("fontSize = 18.sp"))
        assertTrue(mediaInfoDialog.contains("fontSize = 13.5.sp"))
        assertTrue(mediaInfoDialog.contains("val scrollState = rememberScrollState()"))
        assertTrue(mediaInfoDialog.contains(".verticalScroll(scrollState)"))
        assertTrue(mediaInfoDialog.contains("canScrollBackward = scrollState.value > 0"))
        assertTrue(mediaInfoDialog.contains("canScrollForward = scrollState.value < scrollState.maxValue"))
        assertFalse(mediaInfoDialog.contains(".fillMaxWidth(0.46f)"))
        assertFalse(mediaInfoDialog.contains(".fillMaxHeight()"))
        assertFalse(mediaInfoDialog.contains("RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp)"))
        assertFalse(mediaInfoDialog.contains(".border(1.dp"))
        assertFalse(mediaInfoDialog.contains(".padding(horizontal = 28.dp, vertical = 34.dp)"))
        assertFalse(mediaInfoDialog.contains("MaterialTheme.typography.headlineSmall"))
        assertFalse(mediaInfoDialog.contains("MaterialTheme.typography.bodyLarge"))
    }

    @Test
    fun audiobookOverlayUsesCompactTvSideSheet() {
        assertTrue(audiobookOverlay.contains(".width(320.dp)"))
        assertTrue(audiobookOverlay.contains(".padding(horizontal = 18.dp, vertical = 22.dp)"))
        assertFalse(audiobookOverlay.contains(".width(520.dp)"))
        assertFalse(audiobookOverlay.contains(".padding(horizontal = 28.dp, vertical = 32.dp)"))
    }
}
