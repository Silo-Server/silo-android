package com.continuum.app.android.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileDetailActionsSourceTest {
    private val movieDetail = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/detail/MovieDetailContent.kt",
    ).readText()
    private val seriesDetail = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/detail/SeriesDetailContent.kt",
    ).readText()
    private val itemDetail = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/detail/ItemDetailScreen.kt",
    ).readText()
    private val viewModel = File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/detail/ItemDetailViewModel.kt",
    ).readText()

    @Test
    fun watchedHeroActionCallsRepositoryInsteadOfNoOp() {
        assertFalse(movieDetail.contains("onToggleWatched = { /* no-op"))
        assertFalse(seriesDetail.contains("onToggleWatched = { /* no-op"))
        assertTrue(itemDetail.contains("onToggleWatched = { viewModel.toggleWatched() }"))
        assertTrue(viewModel.contains("fun toggleWatched()"))
        assertTrue(viewModel.contains("personalDataRepository.setWatched(contentId, target)"))
    }
}
