package org.siloserver.silo.model.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonDisplayOrderTest {
    private fun season(
        number: Int,
        specials: Boolean = false,
        id: String = "season-$number-$specials",
        title: String? = null,
        episodeCount: Int = 10,
        userData: SeasonUserData? = null,
    ) = Season(
        contentId = id,
        seasonNumber = number,
        isSpecials = specials,
        title = title,
        episodeCount = episodeCount,
        userData = userData,
    )

    @Test
    fun `specials sort before regular seasons`() {
        val result = listOf(season(2), season(0), season(1)).sortedForDisplay()
        assertEquals(listOf(0, 1, 2), result.map(Season::seasonNumber))
    }

    @Test
    fun `specials flag is authoritative even for nonzero season number`() {
        val result = listOf(season(1), season(99, specials = true), season(2)).sortedForDisplay()
        assertEquals(listOf(99, 1, 2), result.map(Season::seasonNumber))
    }

    @Test
    fun `ordinary opening selects first regular season`() {
        val result = listOf(season(0), season(2), season(1))
            .initialSeasonForDisplay(preferredSeasonNumber = null)
        assertEquals(1, result?.seasonNumber)
    }

    @Test
    fun `opening preloads the season containing the in progress episode`() {
        val result = listOf(
            season(1),
            season(3, userData = SeasonUserData(inProgressCount = 1)),
            season(2),
            season(0),
        ).initialSeasonForDisplay(preferredSeasonNumber = null)

        assertEquals(3, result?.seasonNumber)
    }

    @Test
    fun `opening falls back to a partially watched season`() {
        val result = listOf(
            season(1, userData = SeasonUserData(played = true, watchedCount = 10)),
            season(2, userData = SeasonUserData(watchedCount = 4)),
            season(3),
        ).initialSeasonForDisplay(preferredSeasonNumber = null)

        assertEquals(2, result?.seasonNumber)
    }

    @Test
    fun `fresh series with unplayed specials opens on the first regular season`() {
        val result = listOf(season(2), season(0), season(1))
            .initialSeasonForDisplay(preferredSeasonNumber = null)
        assertEquals(1, result?.seasonNumber)
    }

    @Test
    fun `unplayed specials open once every regular season is watched`() {
        val watched = SeasonUserData(played = true, watchedCount = 10)
        val result = listOf(
            season(1, userData = watched),
            season(2, userData = watched),
            season(0),
        ).initialSeasonForDisplay(preferredSeasonNumber = null)
        assertEquals(0, result?.seasonNumber)
    }

    @Test
    fun `fully watched series opens on the first regular season`() {
        val watched = SeasonUserData(played = true, watchedCount = 10)
        val result = listOf(
            season(0, userData = watched),
            season(1, userData = watched),
            season(2, userData = watched),
        ).initialSeasonForDisplay(preferredSeasonNumber = null)
        assertEquals(1, result?.seasonNumber)
    }

    @Test
    fun `specials with an episode in progress open on specials`() {
        val result = listOf(
            season(0, userData = SeasonUserData(inProgressCount = 1)),
            season(1),
            season(2),
        ).initialSeasonForDisplay(preferredSeasonNumber = null)
        assertEquals(0, result?.seasonNumber)
    }

    @Test
    fun `in progress numbered season beats in progress specials`() {
        val result = listOf(
            season(0, userData = SeasonUserData(inProgressCount = 1)),
            season(1),
            season(3, userData = SeasonUserData(inProgressCount = 1)),
        ).initialSeasonForDisplay(preferredSeasonNumber = null)
        assertEquals(3, result?.seasonNumber)
    }

    @Test
    fun `partially watched numbered season beats partially watched specials`() {
        val result = listOf(
            season(0, userData = SeasonUserData(watchedCount = 2)),
            season(1, userData = SeasonUserData(played = true, watchedCount = 10)),
            season(2, userData = SeasonUserData(watchedCount = 4)),
        ).initialSeasonForDisplay(preferredSeasonNumber = null)
        assertEquals(2, result?.seasonNumber)
    }

    @Test
    fun `requested specials remains selected`() {
        val result = listOf(season(2), season(0), season(1))
            .initialSeasonForDisplay(preferredSeasonNumber = 0)
        assertEquals(0, result?.seasonNumber)
    }

    @Test
    fun `specials-only series selects specials`() {
        val result = listOf(season(0))
            .initialSeasonForDisplay(preferredSeasonNumber = null)
        assertEquals(0, result?.seasonNumber)
    }

    @Test
    fun `duplicate season numbers use title as deterministic tie breaker`() {
        val result = listOf(
            season(1, id = "z-id", title = "Zulu"),
            season(1, id = "a-id", title = "Alpha"),
        ).sortedForDisplay()

        assertEquals(listOf("a-id", "z-id"), result.map(Season::contentId))
    }

    @Test
    fun `duplicate season numbers and titles use content id as deterministic tie breaker`() {
        val result = listOf(
            season(1, id = "z-id", title = "Same"),
            season(1, id = "a-id", title = "Same"),
        ).sortedForDisplay()

        assertEquals(listOf("a-id", "z-id"), result.map(Season::contentId))
    }

    @Test
    fun `ordinary opening aligns selected state and episode request on first regular season`() {
        val plan = listOf(season(2), season(0), season(1))
            .initialSeasonDisplayPlan(preferredSeasonNumber = null)

        assertEquals(listOf(0, 1, 2), plan.seasons.map(Season::seasonNumber))
        assertEquals(1, plan.selectedSeasonNumber)
        assertEquals(1, plan.episodeRequestSeasonNumber)
    }

    @Test
    fun `explicit specials aligns selected state and episode request`() {
        val plan = listOf(season(2), season(0), season(1))
            .initialSeasonDisplayPlan(preferredSeasonNumber = 0)

        assertEquals(0, plan.selectedSeasonNumber)
        assertEquals(0, plan.episodeRequestSeasonNumber)
    }

    @Test
    fun `specials-only opening aligns selected state and episode request`() {
        val plan = listOf(season(0))
            .initialSeasonDisplayPlan(preferredSeasonNumber = null)

        assertEquals(0, plan.selectedSeasonNumber)
        assertEquals(0, plan.episodeRequestSeasonNumber)
    }
}
