package org.siloserver.silo.tv.ui.navigation

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure routing decision: audiobook-type items go to the audiobook player route,
 * everything else to the video player route. Mirrors the catalog's
 * `isAudiobookItemType` taxonomy (case/whitespace-insensitive, singular "audiobook").
 */
class TvAudiobookRoutingTest {

    @Test
    fun audiobookTypeRoutesToAudiobookPlayer() {
        assertEquals(
            TvRoute.AudiobookPlayer("ab-1", fileId = 7).route,
            tvPlayDestinationFor(itemType = "audiobook", contentId = "ab-1", fileId = 7),
        )
    }

    @Test
    fun audiobookTypeIsCaseAndWhitespaceInsensitive() {
        assertEquals(
            TvRoute.AudiobookPlayer("ab-2", fileId = null).route,
            tvPlayDestinationFor(itemType = "  AudioBook ", contentId = "ab-2", fileId = null),
        )
    }

    @Test
    fun movieTypeRoutesToVideoPlayer() {
        assertEquals(
            TvRoute.Player("m-1", fileId = 3).route,
            tvPlayDestinationFor(itemType = "movie", contentId = "m-1", fileId = 3),
        )
    }

    @Test
    fun nullTypeRoutesToVideoPlayer() {
        assertEquals(
            TvRoute.Player("x-1", fileId = null).route,
            tvPlayDestinationFor(itemType = null, contentId = "x-1", fileId = null),
        )
    }

    @Test
    fun audiobookRouteCarriesExplicitStartPosition() {
        val route = tvPlayDestinationFor(
            itemType = "audiobook",
            contentId = "ab-3",
            fileId = 9,
            resumePositionSeconds = 1234.5,
        )

        assertContains(route, "audiobook/ab-3")
        assertContains(route, "fileId=9")
        assertContains(route, "startPosition=1234.5")
    }

    @Test
    fun audiobookRouteOmitsInvalidStartPosition() {
        val route = TvRoute.AudiobookPlayer(
            contentId = "ab-4",
            startPositionSeconds = Double.NaN,
        ).route

        assertFalse(route.contains("startPosition="))
    }

    @Test
    fun playbackDeepLinkCarriesValidatedVersionQualityAndTracks() {
        val query = mapOf(
            "fileId" to "121",
            "quality" to "Original",
            "audioTrackIndex" to "2",
            "subtitleTrackIndex" to "-1",
        )

        val args = parseTvPlaybackDeepLinkArgs(query::get)

        assertEquals(121, args.fileId)
        assertEquals("original", args.quality)
        assertEquals(2, args.audioTrackIndex)
        assertEquals(-1, args.subtitleTrackIndex)
        val route = tvPlayDestinationFor(
            itemType = "movie",
            contentId = "movie-121",
            fileId = args.fileId,
            resumePositionSeconds = null,
            audioTrackIndex = args.audioTrackIndex,
            subtitleTrackIndex = args.subtitleTrackIndex,
            quality = args.quality,
        )
        assertContains(route, "fileId=121")
        assertContains(route, "quality=original")
        assertContains(route, "audioTrackIndex=2")
        assertContains(route, "subtitleTrackIndex=-1")
    }

    @Test
    fun playbackDeepLinkRejectsInvalidUntrustedValues() {
        val query = mapOf(
            "fileId" to "-5",
            "quality" to "unlimited",
            "audioTrackIndex" to "-2",
            "subtitleTrackIndex" to "-9",
        )

        assertEquals(TvPlaybackDeepLinkArgs(), parseTvPlaybackDeepLinkArgs(query::get))
    }

    @Test
    fun exactVideoPlaybackDeepLinkIsAlreadyArrived() {
        assertTrue(
            tvPlaybackDeepLinkArrived(
                currentRoute = TvRoute.Player.ROUTE,
                currentContentId = "movie-1",
                currentFileId = 42,
                currentQuality = "original",
                currentAudioTrackIndex = 1,
                currentSubtitleTrackIndex = 8,
                itemType = "movie",
                contentId = "movie-1",
                requested = TvPlaybackDeepLinkArgs(
                    fileId = 42,
                    quality = "original",
                    audioTrackIndex = 1,
                    subtitleTrackIndex = 8,
                ),
            ),
        )
    }

    @Test
    fun sameContentWithAnotherSubtitleIsANewPlaybackRequest() {
        assertFalse(
            tvPlaybackDeepLinkArrived(
                currentRoute = TvRoute.Player.ROUTE,
                currentContentId = "movie-1",
                currentFileId = 42,
                currentQuality = "original",
                currentAudioTrackIndex = 1,
                currentSubtitleTrackIndex = 10,
                itemType = "movie",
                contentId = "movie-1",
                requested = TvPlaybackDeepLinkArgs(
                    fileId = 42,
                    quality = "original",
                    audioTrackIndex = 1,
                    subtitleTrackIndex = 8,
                ),
            ),
            "a warm test link must replace the player instead of reusing stale subtitle state",
        )
    }

    @Test
    fun audiobookArrivalIgnoresVideoOnlyTrackArguments() {
        assertTrue(
            tvPlaybackDeepLinkArrived(
                currentRoute = TvRoute.AudiobookPlayer.ROUTE,
                currentContentId = "book-1",
                currentFileId = 7,
                currentQuality = null,
                currentAudioTrackIndex = null,
                currentSubtitleTrackIndex = null,
                itemType = "audiobook",
                contentId = "book-1",
                requested = TvPlaybackDeepLinkArgs(
                    fileId = 7,
                    quality = "720p",
                    audioTrackIndex = 2,
                    subtitleTrackIndex = 4,
                ),
            ),
        )
    }
}
