package org.siloserver.silo.android.ui.screens.browse

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.catalog.BrowseItem
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CatalogGridLoadMoreTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun page(prefix: String) = (1..3).map { BrowseItem(contentId = "$prefix-$it", type = "movie", title = "$prefix $it") }

    /**
     * The phone library grid re-sorts with the old page still up, and turns
     * away a next page until the new one is in. A grid already at its end
     * then has to ask again once the new page replaces the old one, though
     * neither hasMore nor isLoadingMore changed.
     */
    @Test
    fun aGridAtItsEndAsksForTheNextPageAgainWhenItsListIsReplaced() {
        var items by mutableStateOf(page("old"))
        var reloading = true
        var nextPagesRequested = 0
        composeRule.setContent {
            val gridState = rememberLazyGridState()
            // Three cells: the grid is at its end from the start.
            LazyVerticalGrid(columns = GridCells.Fixed(3), state = gridState) {
                items(items, key = { it.contentId }) { Box(Modifier.size(10.dp)) }
            }
            LoadMoreNearEnd(
                gridState = gridState,
                items = items,
                hasMore = true,
                isLoadingMore = false,
                enabled = true,
                // LibrariesViewModel.loadMoreCatalog turns calls away mid-reload.
                onLoadMore = { if (!reloading) nextPagesRequested++ },
            )
        }
        composeRule.waitForIdle()
        assertEquals(0, nextPagesRequested)

        reloading = false
        items = page("resorted")
        composeRule.waitForIdle()
        assertEquals(1, nextPagesRequested)
    }

    /**
     * The library also counts its reload as loading. A reload that returns
     * the same page leaves the list key unchanged, so the end of loading
     * alone has to make a grid at its end ask again.
     */
    @Test
    fun aGridAtItsEndAsksAgainWhenLoadingEndsWithTheSamePage() {
        val items = page("same")
        var loading by mutableStateOf(true)
        var nextPagesRequested = 0
        composeRule.setContent {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(columns = GridCells.Fixed(3), state = gridState) {
                items(items, key = { it.contentId }) { Box(Modifier.size(10.dp)) }
            }
            LoadMoreNearEnd(
                gridState = gridState,
                items = items,
                hasMore = true,
                isLoadingMore = loading,
                enabled = true,
                onLoadMore = { nextPagesRequested++ },
            )
        }
        composeRule.waitForIdle()
        assertEquals(0, nextPagesRequested)

        loading = false
        composeRule.waitForIdle()
        assertEquals(1, nextPagesRequested)
    }
}
