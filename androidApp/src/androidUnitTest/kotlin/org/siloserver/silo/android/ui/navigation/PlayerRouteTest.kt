package org.siloserver.silo.android.ui.navigation

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerRouteTest {
    private val appNavigationSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/navigation/AppNavigation.kt",
    ).readText()
    private val routesSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/navigation/Routes.kt",
    ).readText()

    @Test
    fun playerRouteIncludesResumePositionWhenProvided() {
        assertEquals(
            "player/episode-1?resumePosition=318.5",
            Route.Player(
                contentId = "episode-1",
                resumePositionSeconds = 318.5,
            ).route,
        )
    }

    @Test
    fun playerRouteKeepsResumePositionWithOtherSelections() {
        assertEquals(
            "player/movie-1?fileId=7&audioTrackIndex=1&subtitleTrackIndex=2&resumePosition=42.0",
            Route.Player(
                contentId = "movie-1",
                fileId = 7,
                audioTrackIndex = 1,
                subtitleTrackIndex = 2,
                resumePositionSeconds = 42.0,
            ).route,
        )
    }

    @Test
    fun playerRouteCarriesNormalizedQuality() {
        assertEquals(
            "player/movie-1?fileId=7&quality=1080p",
            Route.Player(
                contentId = "movie-1",
                fileId = 7,
                quality = " 1080P ",
            ).route,
        )
        assertFalse(Route.Player(contentId = "movie-1", quality = "unlimited").route.contains("quality="))
    }

    @Test
    fun playerRouteOmitsInvalidResumePosition() {
        val route = Route.Player(
            contentId = "movie-1",
            resumePositionSeconds = Double.NaN,
        ).route

        assertFalse(route.contains("resumePosition="))
    }

    @Test
    fun playerRouteUsesSharedResumeArgumentCodec() {
        val route = Route.Player(
            contentId = "movie-1",
            resumePositionSeconds = 0.0,
        ).route

        assertContains(route, "resumePosition=0.0")
        assertTrue(
            routesSource.contains("VideoPlayerRouteArgs.encodeResumePosition(resumePositionSeconds)"),
            "mobile navigation must use the shared resumePosition encoder when launching PlayerScreen",
        )
        assertTrue(
            appNavigationSource.contains("VideoPlayerRouteArgs.parseResumePosition("),
            "mobile navigation must reject non-finite resumePosition args before PlayerScreen sees them",
        )
    }
}
