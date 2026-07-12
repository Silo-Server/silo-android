package org.siloserver.silo.android.ui.navigation

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
}
