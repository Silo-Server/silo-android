package org.siloserver.silo.tv.ui.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Item detail used `launchSingleTop`, which matches the destination NODE rather
 * than its arguments. Every item-detail route shares one node, so navigating
 * from detail A to related item B reused A's entry instead of pushing — Back
 * from B skipped A and returned to whatever was underneath.
 *
 * The replacement collapses only an EXACT repeat, which is what the original
 * comment claimed `launchSingleTop` did.
 */
class TvItemDetailNavigationTest {


    @Test
    fun `a different item is not the current page`() {
        assertFalse(
            tvIsAlreadyShowingItemDetail(
                currentRoute = TvRoute.ItemDetail.ROUTE,
                currentContentId = "item-a",
                currentSeasonNumber = null,
                contentId = "item-b",
                seasonNumber = null,
            ),
            "a related item must push its own entry so Back returns to the item it came from",
        )
    }

    @Test
    fun `the same item is the current page`() {
        assertTrue(
            tvIsAlreadyShowingItemDetail(
                currentRoute = TvRoute.ItemDetail.ROUTE,
                currentContentId = "item-a",
                currentSeasonNumber = null,
                contentId = "item-a",
                seasonNumber = null,
            ),
            "a double Select on one card must not stack a duplicate page",
        )
    }

    @Test
    fun `the same series at a different season is not the current page`() {
        assertFalse(
            tvIsAlreadyShowingItemDetail(
                currentRoute = TvRoute.ItemDetail.ROUTE,
                currentContentId = "series-a",
                currentSeasonNumber = 1,
                contentId = "series-a",
                seasonNumber = 2,
            ),
        )
    }

    @Test
    fun `the same series at the same season is the current page`() {
        assertTrue(
            tvIsAlreadyShowingItemDetail(
                currentRoute = TvRoute.ItemDetail.ROUTE,
                currentContentId = "series-a",
                currentSeasonNumber = 3,
                contentId = "series-a",
                seasonNumber = 3,
            ),
        )
    }

    @Test
    fun `the same continue watching episode is the current page`() {
        assertTrue(
            tvIsAlreadyShowingItemDetail(
                currentRoute = TvRoute.ItemDetail.ROUTE,
                currentContentId = "series-a",
                currentSeasonNumber = 3,
                currentEpisodeContentId = "episode-1",
                contentId = "series-a",
                seasonNumber = 3,
                episodeContentId = "episode-1",
            ),
        )
    }

    @Test
    fun `another episode in the same season is a different detail request`() {
        assertFalse(
            tvIsAlreadyShowingItemDetail(
                currentRoute = TvRoute.ItemDetail.ROUTE,
                currentContentId = "series-a",
                currentSeasonNumber = 3,
                currentEpisodeContentId = "episode-1",
                contentId = "series-a",
                seasonNumber = 3,
                episodeContentId = "episode-2",
            ),
        )
    }

    @Test
    fun `continue watching route carries encoded series season and episode`() {
        assertEquals(
            "item/series%2Fa?seasonNumber=3&episodeContentId=episode%2F1",
            TvRoute.ItemDetail(
                contentId = "series/a",
                seasonNumber = 3,
                episodeContentId = "episode/1",
            ).route,
        )
    }

    @Test
    fun `season canonicalization route carries no episode selection`() {
        assertEquals(
            "item/series%2Fa?seasonNumber=3",
            TvRoute.ItemDetail(
                contentId = "series/a",
                seasonNumber = 3,
            ).route,
        )
    }

    @Test
    fun `canonical replacement is owned by the raw detail entry`() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt",
        ).readText()
        val replacement = source
            .substringAfter("onSeriesDetailReplace = canonicalizeSeries@")
            .substringBefore("onSeriesClick =")

        assertTrue(replacement.contains("currentBackStackEntry?.id != backStack.id"))
        assertTrue(replacement.contains("return@canonicalizeSeries"))
        assertTrue(replacement.contains("episodeContentId = episodeContentId"))
        assertTrue(replacement.contains("popUpTo(it) { inclusive = true }"))
    }

    @Test
    fun `the identical request on the same entry is suppressed`() {
        val destination = TvRoute.Player(contentId = "movie-a").route
        assertEquals(
            TvPlaybackNavAction.Suppress,
            tvPlaybackNavAction(
                currentRoute = TvRoute.Player.ROUTE,
                currentEntryId = "entry-1",
                lastPlaybackNavigation = TvPlaybackNavigation(destination, "entry-1"),
                destination = destination,
            ),
            "a double Select must not start a second session on the same title",
        )
    }

    /**
     * The same title at another version is a different request; dropping it
     * would make the version picker silently do nothing.
     */
    @Test
    fun `the same title at a different version still navigates`() {
        assertEquals(
            TvPlaybackNavAction.ReplaceCurrentPlayer,
            tvPlaybackNavAction(
                currentRoute = TvRoute.Player.ROUTE,
                currentEntryId = "entry-1",
                lastPlaybackNavigation = TvPlaybackNavigation(
                    TvRoute.Player(contentId = "movie-a", fileId = 1).route,
                    "entry-1",
                ),
                destination = TvRoute.Player(contentId = "movie-a", fileId = 2).route,
            ),
        )
    }

    @Test
    fun `a different title takes over the player entry`() {
        assertEquals(
            TvPlaybackNavAction.ReplaceCurrentPlayer,
            tvPlaybackNavAction(
                currentRoute = TvRoute.Player.ROUTE,
                currentEntryId = "entry-1",
                lastPlaybackNavigation = TvPlaybackNavigation(
                    TvRoute.Player(contentId = "movie-a").route,
                    "entry-1",
                ),
                destination = TvRoute.Player(contentId = "movie-b").route,
            ),
            "stacking players leaves Back walking through dead sessions",
        )
    }

    @Test
    fun `playback arriving over an audiobook takes over that entry too`() {
        assertEquals(
            TvPlaybackNavAction.ReplaceCurrentPlayer,
            tvPlaybackNavAction(
                currentRoute = TvRoute.AudiobookPlayer.ROUTE,
                currentEntryId = "entry-1",
                lastPlaybackNavigation = TvPlaybackNavigation(
                    TvRoute.AudiobookPlayer(contentId = "book-a").route,
                    "entry-1",
                ),
                destination = TvRoute.Player(contentId = "movie-b").route,
            ),
            "Back must not resurrect the audiobook the viewer replaced",
        )
    }

    @Test
    fun `playing from a non player screen pushes`() {
        val destination = TvRoute.Player(contentId = "movie-a").route
        assertEquals(
            TvPlaybackNavAction.Push,
            tvPlaybackNavAction(
                currentRoute = TvRoute.ItemDetail.ROUTE,
                currentEntryId = "entry-1",
                lastPlaybackNavigation = TvPlaybackNavigation(destination, "entry-1"),
                destination = destination,
            ),
            "Play from detail must push a player, not be mistaken for a repeat",
        )
    }

    /**
     * The case that broke every weaker key: cast and auto-advance navigate
     * without going through the helper, and can put up the SAME title with
     * different arguments (an auto-advance handoff, another file). Keying on
     * route + content id suppressed the user's real request; keying on the entry
     * the request produced does not, because those paths pop and push, so the id
     * differs. (Watch Together used to belong on this list; it now routes
     * through the helper precisely because it single-topped the player entry in
     * place, preserving the id.)
     */
    @Test
    fun `a bypass path putting up the same title does not suppress`() {
        val destination = TvRoute.Player(contentId = "movie-a").route
        assertEquals(
            TvPlaybackNavAction.ReplaceCurrentPlayer,
            tvPlaybackNavAction(
                currentRoute = TvRoute.Player.ROUTE,
                // A cast launch replaced our entry with its own for the same title.
                currentEntryId = "entry-2",
                lastPlaybackNavigation = TvPlaybackNavigation(destination, "entry-1"),
                destination = destination,
            ),
        )
    }

    /** With nothing recorded there is nothing to collide with. */
    @Test
    fun `a request that never arrived can be retried`() {
        val destination = TvRoute.Player(contentId = "movie-a").route
        assertEquals(
            TvPlaybackNavAction.ReplaceCurrentPlayer,
            tvPlaybackNavAction(
                currentRoute = TvRoute.Player.ROUTE,
                currentEntryId = "entry-1",
                lastPlaybackNavigation = null,
                destination = destination,
            ),
        )
    }

    @Test
    fun `a matching id on some other destination is not the current page`() {
        assertFalse(
            tvIsAlreadyShowingItemDetail(
                currentRoute = TvRoute.Player.ROUTE,
                currentContentId = "item-a",
                currentSeasonNumber = null,
                contentId = "item-a",
                seasonNumber = null,
            ),
            "playing an item must not suppress opening its detail page",
        )
    }

    // --- what gets recorded ---

    /**
     * The case that made the previous key unsound: replacing a Watch Together
     * player for the SAME title with a solo request, where the navigation is
     * dropped during teardown. Confirming arrival on route + content id alone
     * would adopt the untouched Watch Together entry as ours, and the retry
     * would then be suppressed — the user presses Play and nothing happens.
     */
    @Test
    fun `a dropped navigation records nothing even when the same title is up`() {
        assertNull(
            tvRecordedPlaybackNavigation(
                destination = TvRoute.Player(contentId = "movie-a").route,
                contentId = "movie-a",
                entryIdBefore = "entry-1",
                // Unchanged: navigate() was dropped.
                arrivedEntryId = "entry-1",
                arrivedContentId = "movie-a",
            ),
        )
    }

    @Test
    fun `a landed navigation records its own entry`() {
        val destination = TvRoute.Player(contentId = "movie-a").route
        assertEquals(
            TvPlaybackNavigation(destination, "entry-2"),
            tvRecordedPlaybackNavigation(
                destination = destination,
                contentId = "movie-a",
                entryIdBefore = "entry-1",
                arrivedEntryId = "entry-2",
                arrivedContentId = "movie-a",
            ),
        )
    }

    @Test
    fun `landing somewhere other than the requested content records nothing`() {
        assertNull(
            tvRecordedPlaybackNavigation(
                destination = TvRoute.Player(contentId = "movie-a").route,
                contentId = "movie-a",
                entryIdBefore = "entry-1",
                arrivedEntryId = "entry-2",
                arrivedContentId = null,
            ),
        )
    }
}
