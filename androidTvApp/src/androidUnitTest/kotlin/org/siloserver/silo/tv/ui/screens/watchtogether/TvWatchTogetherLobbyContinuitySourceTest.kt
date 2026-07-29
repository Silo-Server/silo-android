package org.siloserver.silo.tv.ui.screens.watchtogether

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvWatchTogetherLobbyContinuitySourceTest {
    private val lobby = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/watchtogether/TvWatchTogetherLobbyScreen.kt",
    ).readText()
    private val detail = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/detail/TvItemDetailScreen.kt",
    ).readText()

    @Test
    fun backAndBrowsePreserveRoomWhileLeaveIsExplicit() {
        val backHandler = lobby.substringAfter("BackHandler(enabled = true)")
            .substringBefore("Box(")
        assertTrue(backHandler.contains("onBack()"))
        assertFalse(backHandler.contains("viewModel.leave()"))
        assertTrue(lobby.contains("title = \"Browse titles\""))
        assertTrue(lobby.contains("title = \"Leave room\""))
        assertTrue(lobby.contains("viewModel.leave()"))
    }

    @Test
    fun existingOwnerAuthorityAndSuggestionPathRemain() {
        assertTrue(lobby.contains("CloseRoomButton(onClick = viewModel::closeRoom)"))
        assertTrue(lobby.contains("viewModel.vote(s.id)"))
        assertTrue(lobby.contains("viewModel.promote(s.id)"))
        assertTrue(detail.contains("Suggest to Watch Together"))
        assertTrue(detail.contains("suggestViewModel.suggest("))
    }
}
