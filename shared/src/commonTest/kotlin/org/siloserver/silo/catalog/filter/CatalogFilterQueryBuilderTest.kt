package org.siloserver.silo.catalog.filter

import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.network.api.toV2Groups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors silo-apple's CatalogQueryBuilderTests — the wire contract is
 * pinned against silo-server's catalog_parser.go. Apple is authoritative.
 */
class CatalogFilterQueryBuilderTest {

    @Test
    fun matchParamFollowsMatchAll() {
        assertEquals("all", CatalogFilterQueryBuilder.matchParam(CatalogFilterState()))
        assertEquals(
            "any",
            CatalogFilterQueryBuilder.matchParam(CatalogFilterState(matchAll = false)),
        )
    }

    @Test
    fun genreValuesBecomeOneAnyGroupOfContainsRulesSorted() {
        val state = CatalogFilterState(
            selections = mapOf(CatalogFacet.Genre to setOf("Drama", "Action")),
        )
        val groups = CatalogFilterQueryBuilder.buildGroups(state)
        assertEquals(1, groups.size)
        assertEquals("any", groups[0].match)
        assertEquals(listOf("Action", "Drama"), groups[0].rules.map { it.value.content })
        assertTrue(groups[0].rules.all { it.field == "genre" && it.op == "contains" })
    }

    @Test
    fun scalarFacetsUseIsOp() {
        val state = CatalogFilterState(
            selections = mapOf(
                CatalogFacet.Studio to setOf("A24"),
                CatalogFacet.SeriesName to setOf("Discworld"),
            ),
        )
        val groups = CatalogFilterQueryBuilder.buildGroups(state)
        val fields = groups.flatMap { it.rules }.associate { it.field to it.op }
        assertEquals("is", fields["studio"])
        assertEquals("is", fields["series"])
    }

    @Test
    fun decadesBecomeYearBetweenRangeRules() {
        val state = CatalogFilterState(
            selections = mapOf(CatalogFacet.Decade to setOf("2010")),
        )
        val groups = CatalogFilterQueryBuilder.buildGroups(state)
        assertEquals(1, groups.size)
        val rule = groups[0].rules.single()
        assertEquals("year", rule.field)
        assertEquals("between", rule.op)
        assertEquals(listOf("2010", "2019"), rule.values)
    }

    @Test
    fun dynamicRangeMapsToBooleanFieldRules() {
        val state = CatalogFilterState(
            selections = mapOf(CatalogFacet.DynamicRange to setOf("dolby_vision", "hdr")),
        )
        val groups = CatalogFilterQueryBuilder.buildGroups(state)
        assertEquals(1, groups.size)
        assertEquals("any", groups[0].match)
        // The server rejects string booleans ("true"), so the wire value must be a JSON boolean.
        val byField = groups.toV2Groups()[0].rules.associate { it.field to it.value }
        assertEquals(JsonPrimitive(true), byField["hdr"])
        assertEquals(JsonPrimitive(true), byField["dolby_vision"])
    }

    @Test
    fun watchStatusIsItsOwnAllGroupWithOneRule() {
        val expectations = mapOf(
            "unwatched" to ("watched" to false),
            "watched" to ("watched" to true),
            "inProgress" to ("in_progress" to true),
            "favorited" to ("favorited" to true),
            "watchlist" to ("in_watchlist" to true),
        )
        for ((status, expected) in expectations) {
            val state = CatalogFilterState(
                selections = mapOf(CatalogFacet.WatchStatus to setOf(status)),
            )
            val group = CatalogFilterQueryBuilder.buildGroups(state).toV2Groups().single()
            assertEquals("all", group.match)
            val rule = group.rules.single()
            assertEquals(expected.first, rule.field, "field for $status")
            assertEquals(JsonPrimitive(expected.second), rule.value, "JSON boolean value for $status")
            assertEquals("is", rule.op)
        }
    }

    @Test
    fun facetAvailabilityMatchesIos() {
        val video = CatalogFacet.available(BrowseFacetMediaType.Video)
        val audiobook = CatalogFacet.available(BrowseFacetMediaType.Audiobook)
        assertTrue(CatalogFacet.Resolution in video)
        assertTrue(CatalogFacet.Author !in video)
        assertEquals(
            listOf(
                CatalogFacet.Genre, CatalogFacet.Author, CatalogFacet.Narrator,
                CatalogFacet.SeriesName, CatalogFacet.Decade, CatalogFacet.WatchStatus,
            ),
            audiobook,
        )
    }

    @Test
    fun resetKeepsSortAndOrder() {
        val state = CatalogFilterState(
            selections = mapOf(CatalogFacet.Genre to setOf("Drama")),
            matchAll = false,
            sort = "title",
            order = "asc",
        )
        val reset = state.resetFilters()
        assertTrue(reset.selections.isEmpty())
        assertTrue(reset.matchAll)
        assertEquals("title", reset.sort)
        assertEquals("asc", reset.order)
    }

    @Test
    fun activeFacetCountCountsDimensionsNotValues() {
        val state = CatalogFilterState(
            selections = mapOf(
                CatalogFacet.Genre to setOf("Drama", "Action"),
                CatalogFacet.DynamicRange to setOf("hdr", "dolby_vision"),
                CatalogFacet.Studio to emptySet(),
            ),
        )
        assertEquals(2, state.activeFacetCount)
    }
}
