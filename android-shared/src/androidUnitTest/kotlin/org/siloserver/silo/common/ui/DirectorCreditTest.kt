package org.siloserver.silo.common.ui

import org.siloserver.silo.model.catalog.CrewMember
import org.siloserver.silo.model.catalog.ItemDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectorCreditTest {
    @Test
    fun movieCreditMatchesExactDirectorJobAndCleansNames() {
        val detail = ItemDetail(
            contentId = "movie-1",
            type = "MoViE",
            title = "Movie",
            crew = listOf(
                CrewMember(name = " Alice ", job = " director "),
                CrewMember(name = "Camera", job = "Director of Photography"),
                CrewMember(name = "", job = "Director"),
                CrewMember(name = "Alice", job = "DIRECTOR"),
                CrewMember(name = "Bob", job = "Director"),
            ),
        )

        assertEquals("Directed by Alice, Bob", movieDirectorCredit(detail))
    }

    @Test
    fun movieCreditKeepsServerOrderAndCapsAtThreeNames() {
        val detail = ItemDetail(
            contentId = "movie-2",
            type = "movie",
            title = "Movie",
            crew = listOf("One", "Two", "Three", "Four").map {
                CrewMember(name = it, job = "Director")
            },
        )

        assertEquals("Directed by One, Two, Three", movieDirectorCredit(detail))
    }

    @Test
    fun movieCreditIsAbsentForNonMoviesOrMissingDirectors() {
        assertNull(
            movieDirectorCredit(
                ItemDetail(
                    contentId = "episode-1",
                    type = "episode",
                    title = "Episode",
                    crew = listOf(CrewMember(name = "Alice", job = "Director")),
                ),
            ),
        )
        assertNull(
            movieDirectorCredit(
                ItemDetail(
                    contentId = "movie-3",
                    type = "movie",
                    title = "Movie",
                    crew = listOf(CrewMember(name = "Camera", job = "Cinematographer")),
                ),
            ),
        )
    }
}
