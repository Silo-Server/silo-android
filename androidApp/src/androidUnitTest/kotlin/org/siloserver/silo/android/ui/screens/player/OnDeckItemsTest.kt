package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import kotlin.test.*

class OnDeckItemsTest {
    private fun card(id: String) = SectionItem(id, "movie", id, backdropUrl = "art:$id")
    @Test fun preservesOrderFiltersAndTwelveCardBound() {
        val cards = listOf(card("current"), card("same-series").copy(seriesId = "series"), card("no-art").copy(backdropUrl = null),
            card("first"), card("first")) + (1..15).map { card("item:$it") }
        val sections = listOf(ResolvedSection("ignored", "popular", "Popular", items = listOf(card("ignored"))),
            ResolvedSection("cw", "continue_watching", "Continue", items = cards))
        val result = sections.toOnDeckItems("current", "series")
        assertEquals(listOf("first") + (1..11).map { "item:$it" }, result.map { it.contentId })
        assertEquals("art:first", result.first().artUrl)
        assertTrue(emptyList<ResolvedSection>().toOnDeckItems("current", null).isEmpty())
    }
    @Test fun preservesSeriesTitleSeasonZeroAndBoundedProgress() {
        val episode = card("episode").copy(seriesTitle = "Series", seasonNumber = 0, episodeNumber = 2,
            positionSeconds = 120.0, durationSeconds = 100.0, backdropThumbhash = "hash")
        val section = ResolvedSection("next", "next_up", "Next", items = listOf(episode))
        val result = listOf(section).toOnDeckItems("current", null).single()
        assertEquals("Series", result.title); assertEquals("S0·E2 — episode", result.subtitle)
        assertEquals(1f, result.progressFraction); assertEquals("hash", result.artThumbhash)
    }
}
