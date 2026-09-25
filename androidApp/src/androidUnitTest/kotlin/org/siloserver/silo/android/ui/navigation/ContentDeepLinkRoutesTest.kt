package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.cast.SiloCastPlaybackRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentDeepLinkRoutesTest {
    @Test
    fun playLinkCarriesValidatedVersionQualityAndTracks() {
        assertEquals(
            "player/movie-1?fileId=121&quality=original&audioTrackIndex=2&subtitleTrackIndex=-1",
            contentDeepLinkRouteOrNull(
                "silo://play/movie-1?fileId=121&quality=Original&audioTrackIndex=2&subtitleTrackIndex=-1",
            ),
        )
    }

    @Test
    fun playLinkDropsInvalidPlaybackParameters() {
        assertEquals(
            "player/movie-1",
            contentDeepLinkRouteOrNull(
                "silo://play/movie-1?fileId=-5&quality=unlimited&audioTrackIndex=-2&subtitleTrackIndex=-9",
            ),
        )
    }

    @Test
    fun malformedQueryEncodingDoesNotCrashPlaybackRouting() {
        assertNull(contentDeepLinkRouteOrNull("silo://play/movie-1?quality=%"))
    }

    @Test
    fun playLinkBecomesTheSameRequestForAnEngagedTv() {
        val route = contentDeepLinkRouteOrNull(
            "silo://play/episode%201+b?fileId=121&audioTrackIndex=2&subtitleTrackIndex=-1",
        )!!
        assertEquals(
            SiloCastPlaybackRequest(
                contentId = "episode 1+b",
                fileId = 121,
                audioTrackIndex = 2,
                subtitleTrackIndex = -1,
                startFromBeginning = false,
            ),
            playerRouteCastRequestOrNull(route),
        )
        assertEquals(
            12.5,
            playerRouteCastRequestOrNull(Route.Player("m", resumePositionSeconds = 12.5, libraryId = 3).route)?.resumePosition,
        )
        val startOver = playerRouteCastRequestOrNull(Route.Player("m", resumePositionSeconds = 0.0).route)!!
        assertEquals(true, startOver.startFromBeginning)
        assertNull(startOver.resumePosition)
    }

    @Test
    fun onlyPlainPlayerRoutesGoToTheTv() {
        assertNull(playerRouteCastRequestOrNull(Route.ItemDetail("movie-1").route))
        // Route.Player's roomId goes through android.net.Uri, which is stubbed here.
        assertNull(playerRouteCastRequestOrNull("player/movie-1?roomId=room-7"))
    }
}
