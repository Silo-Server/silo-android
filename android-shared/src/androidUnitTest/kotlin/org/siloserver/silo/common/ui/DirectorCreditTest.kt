package org.siloserver.silo.common.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.common.R
import org.siloserver.silo.model.catalog.CrewMember
import org.siloserver.silo.model.catalog.ItemDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DirectorCreditTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    /** [movieDirectorCredit] in English, outside composition. */
    private fun creditInEnglish(detail: ItemDetail): String? =
        movieDirectorNames(detail)?.let { resources.getString(R.string.detail_directedBy, it.joinToString(", ")) }

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

        assertEquals("Directed by Alice, Bob", creditInEnglish(detail))
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

        assertEquals("Directed by One, Two, Three", creditInEnglish(detail))
    }

    @Test
    fun movieCreditIsAbsentForNonMoviesOrMissingDirectors() {
        assertNull(
            creditInEnglish(
                ItemDetail(
                    contentId = "episode-1",
                    type = "episode",
                    title = "Episode",
                    crew = listOf(CrewMember(name = "Alice", job = "Director")),
                ),
            ),
        )
        assertNull(
            creditInEnglish(
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
