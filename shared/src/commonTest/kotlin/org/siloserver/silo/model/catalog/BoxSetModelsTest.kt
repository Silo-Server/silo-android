package org.siloserver.silo.model.catalog

import kotlin.test.*
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.SharedPlural

class BoxSetModelsTest {
    private fun movie(id: String, played: Boolean = false, position: Double? = null) = BrowseItem(
        contentId = id, type = "movie", title = id,
        userState = MediaItemUserState(played = played), positionSeconds = position,
    )

    private val boxSet = CatalogBoxSet("1241", "3", "Harry Potter Collection", movieCount = 8, firstYear = 2001, lastYear = 2011)

    @Test fun playStartsTheFirstUnplayedMovieInCollectionOrder() {
        val items = listOf(movie("hp1", played = true), movie("hp2"), movie("hp3", position = 600.0))
        assertEquals("hp2", boxSetNextMovie(items)?.contentId)
        assertNull(boxSetNextMovie(items)?.boxSetResumePosition())
    }

    @Test fun anInProgressMovieResumesWhereItStopped() {
        val items = listOf(movie("hp1", played = true), movie("hp2", position = 600.0), movie("hp3"))
        val next = assertNotNull(boxSetNextMovie(items))
        assertEquals("hp2", next.contentId)
        assertEquals(600.0, next.boxSetResumePosition())
        assertEquals(UiText.Key(SharedString.BoxSetContinue, listOf("hp2")), boxSet.copy(playedCount = 1).playLabel(next))
    }

    @Test fun aFullyWatchedBoxSetStartsOverFromTheFirstMovie() {
        val items = listOf(movie("hp1", played = true), movie("hp2", played = true))
        val next = assertNotNull(boxSetNextMovie(items))
        assertEquals("hp1", next.contentId)
        assertEquals(UiText.Key(SharedString.BoxSetPlay, listOf("hp1")), boxSet.copy(movieCount = 2, playedCount = 2).playLabel(next))
        assertNull(boxSetNextMovie(emptyList()))
    }

    @Test fun captionsAndFacts() {
        val years = UiText.Key(SharedString.BoxSetYears, listOf(UiText.Number(2001), UiText.Number(2011)))
        val films = UiText.Plural(SharedPlural.BoxSetFilms, 8)
        assertEquals(years, boxSet.yearsLabel())
        assertEquals(UiText.Number(2001), boxSet.copy(lastYear = 2001).yearsLabel())
        assertNull(boxSet.copy(firstYear = null, lastYear = 0).yearsLabel())
        assertEquals(years, boxSet.cardCaption())
        assertEquals(films, boxSet.copy(firstYear = null, lastYear = null).cardCaption())
        assertEquals(
            UiText.Plural(SharedPlural.BoxSetWatchedCard, 8, listOf(UiText.Number(5))),
            boxSet.copy(playedCount = 5).cardCaption(),
        )
        assertEquals(UiText.Plural(SharedPlural.BoxSetFilms, 1), boxSetFilmCount(1))
        assertEquals(
            listOf(films, years, UiText.Plural(SharedPlural.BoxSetWatchedFact, 8, listOf(UiText.Number(5)))),
            boxSet.copy(playedCount = 5).heroFacts(),
        )
        assertNull(boxSet.watchedFraction)
        assertEquals(0.5f, boxSet.copy(playedCount = 4).watchedFraction)
    }

    @Test fun searchCardsOfOneCollectionInTwoLibrariesStayDistinct() {
        val first = boxSet.toSearchCard()
        val second = boxSet.copy(libraryId = "9").toSearchCard()
        assertNotEquals(first.contentId, second.contentId)
        assertEquals(boxSet, first.boxSetCard)
        assertEquals(BOX_SET_TYPE, first.type)
    }

    @Test fun onlyBoxSetCardsOpenABoxSet() {
        assertNull(movie("m1").boxSetCard)
        // A box_set object on another type is not a box set card.
        assertNull(movie("m1").copy(boxSet = boxSet).boxSetCard)
    }

    @Test fun theMoviePageRowShowsAWindowThatHoldsTheCurrentFilm() {
        // Eight films, four film slots: the film fits from the start, so the row starts there.
        assertEquals(0 until 4, boxSetRowWindow(count = 8, currentIndex = 2, filmSlots = 4))
        // Mid-set, past the first slots: the film before it leads.
        assertEquals(4 until 8, boxSetRowWindow(count = 8, currentIndex = 5, filmSlots = 4))
        assertEquals(3 until 7, boxSetRowWindow(count = 8, currentIndex = 4, filmSlots = 4))
        // The last film: the window ends at the end of the set.
        assertEquals(4 until 8, boxSetRowWindow(count = 8, currentIndex = 7, filmSlots = 4))
        // Every film fits.
        assertEquals(0 until 3, boxSetRowWindow(count = 3, currentIndex = 2, filmSlots = 5))
        // The current film is not in the list.
        assertEquals(0 until 4, boxSetRowWindow(count = 8, currentIndex = -1, filmSlots = 4))
    }
}
