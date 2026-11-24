package org.siloserver.silo.common.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.viewmodel.PersonalListUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class PersonalMediaFilterTest {
    private val items = listOf(
        BrowseItem(contentId = "e1", type = "episode", title = "Pilot"),
        BrowseItem(contentId = "m1", type = "movie", title = "Heat"),
        BrowseItem(contentId = "s1", type = "series", title = "Severance"),
    )

    @Test
    fun allKeepsTheServerOrderAndTvShowsKeepsEpisodes() {
        assertEquals(listOf("e1", "m1", "s1"), PersonalMediaFilter.All.apply(items).map { it.contentId })
        assertEquals(listOf("m1"), PersonalMediaFilter.Movies.apply(items).map { it.contentId })
        assertEquals(listOf("e1", "s1"), PersonalMediaFilter.TvShows.apply(items).map { it.contentId })
    }

    @Test
    fun narrowedCountWaitsForTheLastPage() {
        val partial = PersonalListUiState(items = items, total = 40, hasMore = true)
        assertEquals(40, PersonalMediaFilter.All.shownTotal(partial, 3))
        assertNull(PersonalMediaFilter.Movies.shownTotal(partial, 1))
        assertEquals(1, PersonalMediaFilter.Movies.shownTotal(partial.copy(hasMore = false), 1))
        assertNull(PersonalMediaFilter.All.shownTotal(partial, 3, othersHidden = true))
    }

    @Test
    fun opensOnAllThenOnTheLastChoice() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("personal_media_filter_test", Context.MODE_PRIVATE)
        assertEquals(PersonalMediaFilter.All, PersonalMediaFilterStore(prefs).filter.value)
        PersonalMediaFilterStore(prefs).select(PersonalMediaFilter.TvShows)
        assertEquals(PersonalMediaFilter.TvShows, PersonalMediaFilterStore(prefs).filter.value)
    }
}
