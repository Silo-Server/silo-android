package org.siloserver.silo.tv.ui.components

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.section.SectionItem
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TvMediaRowEpisodeSwapFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun episode(number: Int) = SectionItem(
        contentId = "episode-s1-e$number",
        type = "episode",
        title = "Episode $number",
        seriesId = "series-1",
        seriesTitle = "Series",
        seasonNumber = 1,
        episodeNumber = number,
    )

    @Test
    fun focusedCardKeepsFocusWhenItsSeriesMovesToTheNextEpisode() {
        var items by mutableStateOf(listOf(episode(3)))
        var searchFocused = false
        var rowFocused = false
        val focused = mutableListOf<String>()
        val firstCard = FocusRequester()

        composeRule.setContent {
            Column {
                // Stands in for the top bar's Search button, the first
                // focusable on screen and where lost focus would land.
                Box(
                    Modifier
                        .size(10.dp)
                        .testTag("search")
                        .onFocusChanged { searchFocused = it.isFocused }
                        .focusable(),
                )
                TvMediaRow(
                    title = "Continue Watching",
                    items = items,
                    onItemClick = {},
                    firstItemFocusRequester = remember { firstCard },
                    onItemFocused = { focused += it.contentId },
                    onRowFocusChanged = { rowFocused = it },
                    keyEpisodesBySeries = true,
                )
            }
        }
        composeRule.runOnIdle { firstCard.requestFocus() }
        composeRule.runOnIdle { assertEquals("episode-s1-e3", focused.last()) }

        composeRule.runOnIdle { items = listOf(episode(4)) }

        composeRule.runOnIdle {
            assertTrue(rowFocused, "focus left the row")
            assertFalse(searchFocused, "focus moved to the first focusable on screen")
            assertEquals("episode-s1-e4", focused.last())
        }
    }

    @Test
    fun episodesOfOneSeriesKeepDistinctKeys() {
        val keys = tvMediaRowSlotKeys(listOf(episode(1), episode(2)), keyEpisodesBySeries = true)

        assertEquals(2, keys.toSet().size)
    }
}
