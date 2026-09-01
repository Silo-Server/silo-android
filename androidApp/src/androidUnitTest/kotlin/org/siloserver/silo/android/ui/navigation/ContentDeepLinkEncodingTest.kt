package org.siloserver.silo.android.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.siloserver.silo.model.section.SectionItem

/**
 * `URI.path` is already percent-decoded. The parser used to take that decoded
 * value and interpolate it straight back into a route string, so the decoded
 * bytes were re-read as route syntax: an id carrying an encoded `?` became a
 * query argument, and one carrying an encoded `/` truncated.
 *
 * Plain JVM: both sides use java.net encoding precisely so routes stay
 * testable without Robolectric — `android.net.Uri` is stubbed here and would
 * silently yield "item/null".
 */
class ContentDeepLinkEncodingTest {

    @Test
    fun `an id containing an encoded separator cannot inject a route argument`() {
        val route = contentDeepLinkRouteOrNull("silo://item/abc%3FseasonNumber%3D9")

        assertEquals(
            Route.ItemDetail("abc?seasonNumber=9").route,
            route,
            "the whole decoded id must stay one path segment",
        )
        // The literal injection the old code produced.
        assertNotEquals("item/abc?seasonNumber=9", route)
    }

    @Test
    fun `an ordinary id is unchanged through the round trip`() {
        assertEquals(
            Route.ItemDetail("tt0111161").route,
            contentDeepLinkRouteOrNull("silo://item/tt0111161"),
        )
    }

    @Test
    fun `a continue watching episode opens its series and preserves its exact selection`() {
        val item = SectionItem(
            contentId = "episode/3-1",
            type = "episode",
            title = "Episode 1",
            seriesId = "series 42",
            seasonNumber = 3,
            episodeNumber = 1,
        )

        assertEquals(
            "item/series%2042?seasonNumber=3&episodeContentId=episode%2F3-1",
            continueWatchingDetailRoute(item),
        )
    }

    @Test
    fun `a continue watching movie still opens its own detail`() {
        val item = SectionItem(
            contentId = "movie-1",
            type = "movie",
            title = "Movie",
        )

        assertEquals(Route.ItemDetail("movie-1").route, continueWatchingDetailRoute(item))
    }

    @Test
    fun `a plus in an id survives as a plus`() {
        // URLDecoder would turn this into a space; a path segment must not.
        val route = contentDeepLinkRouteOrNull("silo://item/a%2Bb")
        assertEquals(Route.ItemDetail("a+b").route, route)
    }

    @Test
    fun `a play link round trips through the player route`() {
        assertEquals(
            Route.Player(contentId = "abc/def").route,
            contentDeepLinkRouteOrNull("silo://play/abc%2Fdef"),
        )
    }

    @Test
    fun `a non silo scheme is not a deep link`() {
        assertNull(contentDeepLinkRouteOrNull("https://example.com/item/abc"))
    }

    /**
     * The player route is parsed back to compare against the live player. An
     * encoded id would never equal the decoded target, so an already-showing
     * player would look like a new request and restart.
     */
    @Test
    fun `the player route parses back to the original id`() {
        val route = Route.Player(contentId = "abc?x=1").route

        assertEquals("abc?x=1", playerRouteIntentOrNull(route)?.contentId)
    }

    private fun assertNotEquals(unexpected: String, actual: String?) {
        if (unexpected == actual) {
            throw AssertionError("expected not to equal <$unexpected>")
        }
    }
}
