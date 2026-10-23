package org.siloserver.silo.model.catalog

import kotlin.test.*

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
        assertEquals("Continue · hp2", boxSet.copy(playedCount = 1).playLabel(next))
    }

    @Test fun aFullyWatchedBoxSetStartsOverFromTheFirstMovie() {
        val items = listOf(movie("hp1", played = true), movie("hp2", played = true))
        val next = assertNotNull(boxSetNextMovie(items))
        assertEquals("hp1", next.contentId)
        assertEquals("Play hp1", boxSet.copy(movieCount = 2, playedCount = 2).playLabel(next))
        assertNull(boxSetNextMovie(emptyList()))
    }

    @Test fun captionsAndFacts() {
        assertEquals("2001 – 2011", boxSet.yearsLabel())
        assertEquals("2001", boxSet.copy(lastYear = 2001).yearsLabel())
        assertNull(boxSet.copy(firstYear = null, lastYear = 0).yearsLabel())
        assertEquals("2001 – 2011", boxSet.cardCaption())
        assertEquals("8 films", boxSet.copy(firstYear = null, lastYear = null).cardCaption())
        assertEquals("5 / 8 watched", boxSet.copy(playedCount = 5).cardCaption())
        assertEquals("1 film", boxSetFilmCount(1))
        assertEquals(listOf("8 films", "2001 – 2011", "5 of 8 watched"), boxSet.copy(playedCount = 5).heroFacts())
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
}
