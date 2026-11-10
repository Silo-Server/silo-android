package org.siloserver.silo.common.i18n

import org.siloserver.silo.network.apiv2.Problem
import org.siloserver.silo.network.apiv2.ProblemError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerTextTest {
    // Raw resource text by catalog key, as getString(id) returns it.
    private val catalog = mapOf(
        "home.section.continueWatching" to "Seguir viendo",
        "home.section.recentlyAddedIn" to "Añadido recientemente en %1\$s",
        "home.section.repeated" to "%1\$s: lo nuevo de %1\$s",
        "home.section.twoArgs" to "%1\$s y %2\$s",
        "app.name" to "Silo",
    )

    private fun title(title: String, key: String?, args: Map<String, String>? = null, rtl: Boolean = false) =
        ServerText.sectionTitle(title, key, args, rtl) { catalog[it] }

    @Test fun keyedTitleUsesTheCatalog() {
        assertEquals("Seguir viendo", title("Continue Watching", "home.section.continueWatching"))
        assertEquals("Añadido recientemente en Films", title("Recently Added in Films", "home.section.recentlyAddedIn", mapOf("library" to "Films")))
        assertEquals("Films: lo nuevo de Films", title("x", "home.section.repeated", mapOf("library" to "Films")))
    }

    @Test fun anythingThatDoesNotFitKeepsTheServerTitle() {
        // Renamed by an admin: the server sends no key.
        assertEquals("Keep Watching", title("Keep Watching", null))
        // A key this build doesn't have (newer server).
        assertEquals("Trending Now", title("Trending Now", "home.section.trendingNow"))
        // Only section keys are looked up.
        assertEquals("Server", title("Server", "app.name"))
        // Missing, extra or unplaceable arguments.
        assertEquals("Recently Added in Films", title("Recently Added in Films", "home.section.recentlyAddedIn"))
        assertEquals("Seguir", title("Seguir", "home.section.continueWatching", mapOf("library" to "Films")))
        assertEquals("A and B", title("A and B", "home.section.twoArgs", mapOf("a" to "A", "b" to "B")))
    }

    @Test fun argumentsAreIsolatedOnlyRightToLeft() {
        assertEquals("Añadido recientemente en \u2068Films\u2069", title("x", "home.section.recentlyAddedIn", mapOf("library" to "Films"), rtl = true))
        assertEquals("Seguir viendo", title("x", "home.section.continueWatching", rtl = true))
    }

    private val problem = Problem(
        type = "https://siloserver.org/docs/api/v2/problems/validation_failed",
        title = "t",
        status = 422,
        detail = "d",
        errors = listOf(ProblemError("query.a", "unknown_parameter"), ProblemError("query.b", "invalid_enum")),
    )

    @Test fun problemTextFollowsD33() {
        val es = mapOf(
            "error.field.invalid_enum" to "Elige uno de los valores permitidos.",
            "error.validation_failed" to "Algunos valores no son válidos.",
        )
        // English always shows the server's detail.
        assertNull(ServerText.problem(problem, english = true) { es[it] })
        // The first field error with a translation wins, then the type.
        assertEquals("Elige uno de los valores permitidos.", ServerText.problem(problem, english = false) { es[it] })
        assertEquals("Algunos valores no son válidos.", ServerText.problem(problem.copy(errors = emptyList()), english = false) { es[it] })
        // Nothing translated: detail.
        assertNull(ServerText.problem(problem, english = false) { null })
    }
}
