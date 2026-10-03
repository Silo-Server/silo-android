package org.siloserver.silo.common.ui

import org.siloserver.silo.model.catalog.CastMember
import org.siloserver.silo.model.catalog.CrewMember
import org.siloserver.silo.model.catalog.ItemDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastCrewCreditsTest {
    @Test
    fun groupsDirectorsThenWritersThenCastWithDividers() {
        val credits = castCrewCredits(
            cast = listOf(
                CastMember(name = "Second", character = "B", order = 1),
                CastMember(name = "First", character = "A", order = 0),
            ),
            crew = listOf(
                CrewMember(name = "Writer One", job = "Writer", personId = "20"),
                CrewMember(name = "Producer", job = "Producer", personId = "30"),
                CrewMember(name = "Dir", job = "Director", personId = "10"),
                CrewMember(name = "Script", job = "Screenplay", personId = "21"),
            ),
            isSeries = false,
        )

        assertEquals(
            listOf("Dir", "Writer One", "Script", "First", "Second"),
            credits.map { it.name },
        )
        assertEquals(
            listOf("Director", "Writer", "Writer", "A", "B"),
            credits.map { it.caption },
        )
        assertEquals(
            listOf(null, "Writers", null, "Cast", null),
            credits.map { it.dividerLabel },
        )
        assertEquals(
            listOf(
                CastCrewGroup.Lead,
                CastCrewGroup.Writers,
                CastCrewGroup.Writers,
                CastCrewGroup.Cast,
                CastCrewGroup.Cast,
            ),
            credits.map { it.group },
        )
    }

    @Test
    fun capsEachGroup() {
        val credits = castCrewCredits(
            cast = (0 until 20).map { CastMember(name = "Actor $it", order = it) },
            crew = (0 until 4).map { CrewMember(name = "Dir $it", job = "Director", personId = "d$it") } +
                (0 until 5).map { CrewMember(name = "Writer $it", job = "Writer", personId = "w$it") },
            isSeries = false,
        )

        assertEquals(listOf("Dir 0", "Dir 1"), credits.namesIn(CastCrewGroup.Lead))
        assertEquals(listOf("Writer 0", "Writer 1", "Writer 2"), credits.namesIn(CastCrewGroup.Writers))
        assertEquals((0 until 12).map { "Actor $it" }, credits.namesIn(CastCrewGroup.Cast))
    }

    @Test
    fun deduplicatesByPersonIdThenNameAndOnlyMatchesExactJobs() {
        val credits = castCrewCredits(
            cast = emptyList(),
            crew = listOf(
                CrewMember(name = "Alice", job = " director ", personId = "1"),
                CrewMember(name = "Alice A.", job = "Director", personId = "1"),
                CrewMember(name = "Camera", job = "Director of Photography", personId = "2"),
                CrewMember(name = "Bob", job = "Writer"),
                CrewMember(name = " bob ", job = "Screenplay"),
                CrewMember(name = "Story Person", job = "Story", personId = "3"),
                CrewMember(name = "", job = "Writer"),
            ),
            isSeries = false,
        )

        assertEquals(listOf("Alice"), credits.namesIn(CastCrewGroup.Lead))
        assertEquals(listOf("Bob"), credits.namesIn(CastCrewGroup.Writers))
    }

    @Test
    fun writerDirectorOnlyAppearsInTheLeadGroup() {
        val credits = castCrewCredits(
            cast = listOf(CastMember(name = "Star", order = 0)),
            crew = listOf(
                CrewMember(name = "Auteur", job = "Writer", personId = "1"),
                CrewMember(name = "Auteur", job = "Director", personId = "1"),
                CrewMember(name = "Nameonly", job = "Director"),
                CrewMember(name = "Nameonly", job = "Screenplay"),
                CrewMember(name = "Co-writer", job = "Writer", personId = "2"),
            ),
            isSeries = false,
        )

        assertEquals(listOf("Auteur", "Nameonly"), credits.namesIn(CastCrewGroup.Lead))
        assertEquals(listOf("Co-writer"), credits.namesIn(CastCrewGroup.Writers))
    }

    @Test
    fun seriesCaptionsDirectorCreditsAsCreator() {
        val detail = ItemDetail(
            contentId = "series-1",
            type = "series",
            title = "Series",
            crew = listOf(CrewMember(name = "Showrunner", job = "Director", personId = "1")),
        )

        val credits = castCrewCredits(detail)

        assertEquals(listOf("Creator"), credits.map { it.caption })
        assertNull(credits.single().dividerLabel)
    }

    @Test
    fun castOnlyFallbackHasNoDividers() {
        val credits = castCrewCredits(
            cast = listOf(
                CastMember(name = "B", order = 1),
                CastMember(name = "A", order = 0),
            ),
            crew = listOf(CrewMember(name = "Producer", job = "Producer")),
            isSeries = false,
        )

        assertEquals(listOf("A", "B"), credits.map { it.name })
        assertTrue(credits.all { it.dividerLabel == null })
    }

    @Test
    fun firstShownGroupNeverHasADivider() {
        val credits = castCrewCredits(
            cast = listOf(CastMember(name = "Star", order = 0)),
            crew = listOf(CrewMember(name = "Writer", job = "Writer")),
            isSeries = false,
        )

        assertEquals(listOf(null, "Cast"), credits.map { it.dividerLabel })
    }

    @Test
    fun nonMovieOrSeriesKeepsThePlainCastRow() {
        val cast = (0 until 15).map { CastMember(name = "Actor $it", character = "C$it", order = 15 - it) }
        val detail = ItemDetail(
            contentId = "episode-1",
            type = "episode",
            title = "Episode",
            cast = cast,
            crew = listOf(CrewMember(name = "Dir", job = "Director")),
        )

        val credits = castCrewCredits(detail)

        assertEquals(cast.map { it.name }, credits.map { it.name })
        assertTrue(credits.all { it.group == CastCrewGroup.Cast && it.dividerLabel == null })
    }

    @Test
    fun initialsUseFirstAndLastWords() {
        assertEquals("GG", personInitials("Greta Gerwig"))
        assertEquals("JT", personInitials("  John  Ronald  Tolkien "))
        assertEquals("C", personInitials("cher"))
        assertEquals("", personInitials("  "))
    }

    private fun List<CastCrewCredit>.namesIn(group: CastCrewGroup) =
        filter { it.group == group }.map { it.name }
}
