package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.section.SectionItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DetailSeedTest {
    @AfterTest
    fun clearPendingTap() {
        DetailSeeds.take("")
    }

    @Test
    fun episodeCardSeedsItsSeriesFromSeriesFieldsOnly() {
        val episode = SectionItem(
            contentId = "episode-1",
            type = "episode",
            title = "Pilot",
            seriesId = "series-1",
            seriesTitle = "The Show",
            overview = "Episode spoiler.",
            runtime = 42,
            posterUrl = "https://img/still.jpg",
            backdropUrl = "https://img/still-wide.jpg",
            seriesBackdropUrl = "https://img/series-backdrop.jpg",
        )
        val seed = detailSeed(episode, opening = "series-1")!!
        assertEquals("series", seed.type)
        assertEquals("The Show", seed.title)
        assertEquals("https://img/series-backdrop.jpg", seed.backdropUrl)
        assertNull(seed.posterUrl)
        assertNull(seed.overview)
        assertEquals(0, seed.runtime)
        // An episode never seeds its own page.
        assertNull(detailSeed(episode, opening = "episode-1"))
    }

    @Test
    fun aTapIsTakenOnceAndOnlyByThePageItOpens() {
        val card = SectionItem(contentId = "audiobook-1", type = "audiobook", title = "Dracula")
        DetailSeeds.offer(card)
        assertNull(DetailSeeds.take("movie-2"))
        // The mismatched page cleared the slot.
        assertNull(DetailSeeds.take("audiobook-1"))

        DetailSeeds.offer(card)
        assertEquals("audiobook", DetailSeeds.take("audiobook-1")?.type)
        assertNull(DetailSeeds.take("audiobook-1"))
    }

    @Test
    fun aTapMadeBeforeAnIdentityChangeSeedsNothing() {
        val generation = MutableStateFlow(1L)
        DetailSeeds.bindIdentity(generation)
        DetailSeeds.offer(BrowseItem(contentId = "movie-1", type = "movie", title = "Prometheus"))
        generation.value = 2L
        assertNull(DetailSeeds.take("movie-1"))

        DetailSeeds.offer(BrowseItem(contentId = "movie-1", type = "movie", title = "Prometheus"))
        assertEquals("Prometheus", DetailSeeds.take("movie-1")?.title)
    }
}
