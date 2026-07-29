package org.siloserver.silo.tv.ui.screens.watchtogether

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvWatchTogetherSurfaceSourceTest {
    private val itemDetailScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
    ).readText()
    private val appNavigation = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt",
    ).readText()
    private val entryDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherEntryDialog.kt",
    ).readText()
    private val joinDialog = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvJoinCodeDialog.kt",
    ).readText()
    private val qrPanel = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/auth/QrCodePanel.kt",
    ).readText()
    private val playerScreen = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt",
    ).readText()

    @Test
    fun detailOverflowOpensTheEntryDialog() {
        assertTrue(itemDetailScreen.contains("CLIENT_WATCH_TOGETHER_SURFACE_ENABLED"))
        assertTrue(itemDetailScreen.contains("key = \"watch-together\""))
        assertTrue(itemDetailScreen.contains("watchTogetherOpen = true"))
        assertTrue(itemDetailScreen.contains("TvWatchTogetherEntryDialog("))
        assertTrue(itemDetailScreen.contains("TvJoinCodeDialog("))
    }

    @Test
    fun aResolvedRoomReachesTheNavigationCallback() {
        assertTrue(itemDetailScreen.contains("onWatchTogether(room)"))
        assertTrue(itemDetailScreen.contains("watchTogetherViewModel.consumeResult()"))
        assertTrue(appNavigation.contains("tvWatchTogetherDestination(snapshot)"))
    }

    @Test
    fun audiobooksDoNotOfferARoom() {
        assertTrue(
            itemDetailScreen.contains(
                "CLIENT_WATCH_TOGETHER_SURFACE_ENABLED && !isAudiobookItemType(detail.type)",
            ),
        )
    }

    @Test
    fun activeRoomExposesSuggestionActionFromDetail() {
        assertTrue(itemDetailScreen.contains("TvSuggestToRoomViewModel"))
        assertTrue(itemDetailScreen.contains("key = \"suggest-to-room\""))
        assertTrue(itemDetailScreen.contains("Suggest to Watch Together"))
        assertTrue(itemDetailScreen.contains("suggestViewModel.suggest("))
    }

    @Test
    fun seriesSuggestionUsesItsPlayableEpisodeIdentity() {
        assertTrue(itemDetailScreen.contains("contentType = playType"))
        assertTrue(itemDetailScreen.contains("posterUrl = nextUp?.stillUrl ?: detail.posterUrl"))
    }

    @Test
    fun inFlightRoomMutationCannotBeDismissed() {
        assertTrue(entryDialog.contains("canDismissRoomEntry(isBusy)"))
        assertTrue(joinDialog.contains("canDismissRoomEntry(isBusy)"))
    }

    @Test
    fun qrQuietZoneIsModuleAware() {
        assertTrue(qrPanel.contains("EncodeHintType.MARGIN to 4"))
        assertTrue(!qrPanel.contains("quietZone: Dp"))
    }

    @Test
    fun roomCloseConfirmationTakesDpadFocusFromThePlayer() {
        val dialog = playerScreen.substringAfter("private fun TvRoomCloseConfirmDialog(")
        assertTrue(dialog.contains("rememberTvDialogInitialFocus(closeActionFocus)"))
        assertTrue(dialog.contains(".focusRequester(closeActionFocus)"))
        assertTrue(!dialog.contains("closeActionFocus.requestFocus()"))
        assertTrue(dialog.contains("Popup("))
        assertTrue(dialog.contains("PopupProperties("))
        assertTrue(dialog.contains("focusable = true"))
        assertTrue(dialog.contains("onDismissRequest = onCancel"))
    }
}
