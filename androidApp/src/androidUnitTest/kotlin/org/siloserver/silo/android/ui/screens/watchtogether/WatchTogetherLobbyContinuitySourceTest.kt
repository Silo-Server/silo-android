package org.siloserver.silo.android.ui.screens.watchtogether

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTogetherLobbyContinuitySourceTest {
    private val lobby = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/watchtogether/WatchTogetherLobbyScreen.kt",
    ).readText()
    private val detail = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailScreen.kt",
    ).readText()
    private val movieDetail = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/MovieDetailContent.kt",
    ).readText()
    private val seriesDetail = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/SeriesDetailContent.kt",
    ).readText()

    @Test
    fun ordinaryBackBrowsesWithoutLeavingAndLeaveIsExplicit() {
        val navigationIcon = lobby.substringAfter("navigationIcon = {").substringBefore("actions = {")
        assertTrue(navigationIcon.contains("onClick = onBack"))
        assertFalse(navigationIcon.contains("viewModel.leave()"))
        assertTrue(lobby.contains("Text(\"Leave room\")"))
        assertTrue(lobby.contains("viewModel.leave()"))
    }

    @Test
    fun ownerControlsAndTitleSuggestionRemainReachable() {
        assertTrue(lobby.contains("viewModel.vote(s.id)"))
        assertTrue(lobby.contains("viewModel.promote(s.id)"))
        assertTrue(lobby.contains("viewModel.closeRoom()"))
        assertTrue(movieDetail.contains("Suggest to Watch Together"))
        assertTrue(seriesDetail.contains("Suggest to Watch Together"))
        assertTrue(detail.contains("suggestViewModel.suggest("))
    }
}
