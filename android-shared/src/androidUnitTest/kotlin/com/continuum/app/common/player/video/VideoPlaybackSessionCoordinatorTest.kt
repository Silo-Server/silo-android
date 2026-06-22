package com.continuum.app.common.player.video

import com.continuum.app.model.catalog.TimeRange
import com.continuum.app.model.catalog.VersionChapter
import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlayerSubtitleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class VideoPlaybackSessionCoordinatorTest {
    @Test
    fun startPassesRequestToStarter() = runTest {
        val starter = FakePlaybackStarter()
        val coordinator = VideoPlaybackSessionCoordinator(starter)
        val request = VideoPlaybackStartRequest(
            contentId = "movie-123",
            preferredFileId = 44,
            roomId = "room-1",
            resumePositionOverride = 0.0,
        )

        coordinator.start(request)

        assertEquals(listOf(request), starter.requests)
    }

    @Test
    fun startMapsReadyResultToReadyUiState() = runTest {
        val starter = FakePlaybackStarter(
            result = VideoPlaybackStartResult.Ready(
                contentId = "movie-123",
                fileId = 44,
                streamUrl = "https://lib.strm.cafe/api/stream/movie",
                playMethod = PlayMethod.DIRECT,
                delivery = PlaybackDelivery.ORIGINAL_HTTP,
                container = "mkv",
                title = "Michael",
                subtitle = "Movie",
                artworkUrl = "https://lib.strm.cafe/poster.jpg",
                startPositionSeconds = 1887.25,
                sessionId = "session-1",
                serverUrl = "https://lib.strm.cafe",
                accessToken = "token-1",
                mediaFileId = 99,
                audioTrackIndex = 3,
                durationSeconds = 7200.5,
                subtitleUrls = listOf(PlayerSubtitleInfo(index = 0, language = "en", url = "/subtitles/en.srt")),
                preferredAudioLanguage = "en",
                preferredTextLanguage = "nl",
                intro = TimeRange(start = 12.0, end = 88.0),
                credits = TimeRange(start = 7000.0, end = 7200.5),
                chapters = listOf(VersionChapter(index = 0, title = "Opening", startSeconds = 0.0)),
            ),
        )
        val coordinator = VideoPlaybackSessionCoordinator(starter)

        val state = coordinator.start(baseRequest())

        val ready = assertIs<VideoPlayerUiState.Ready>(state)
        assertEquals("movie-123", ready.contentId)
        assertEquals(44, ready.fileId)
        assertEquals("https://lib.strm.cafe/api/stream/movie", ready.streamUrl)
        assertEquals(PlayMethod.DIRECT, ready.playMethod)
        assertEquals(PlaybackDelivery.ORIGINAL_HTTP, ready.delivery)
        assertEquals("mkv", ready.container)
        assertEquals("Michael", ready.title)
        assertEquals("Movie", ready.subtitle)
        assertEquals("https://lib.strm.cafe/poster.jpg", ready.artworkUrl)
        assertEquals(1887.25, ready.startPositionSeconds)
        assertEquals("session-1", ready.sessionId)
        assertEquals("https://lib.strm.cafe", ready.serverUrl)
        assertEquals("token-1", ready.accessToken)
        assertEquals(99, ready.mediaFileId)
        assertEquals(3, ready.audioTrackIndex)
        assertEquals(7200.5, ready.durationSeconds)
        assertEquals(listOf(PlayerSubtitleInfo(index = 0, language = "en", url = "/subtitles/en.srt")), ready.subtitleUrls)
        assertEquals("en", ready.preferredAudioLanguage)
        assertEquals("nl", ready.preferredTextLanguage)
        assertEquals(TimeRange(start = 12.0, end = 88.0), ready.intro)
        assertEquals(TimeRange(start = 7000.0, end = 7200.5), ready.credits)
        assertEquals(listOf(VersionChapter(index = 0, title = "Opening", startSeconds = 0.0)), ready.chapters)
    }

    @Test
    fun startMapsErrorResultToErrorUiState() = runTest {
        val starter = FakePlaybackStarter(
            result = VideoPlaybackStartResult.Error(
                contentId = "movie-123",
                message = "Failed to start playback",
            ),
        )
        val coordinator = VideoPlaybackSessionCoordinator(starter)

        val state = coordinator.start(baseRequest())

        val error = assertIs<VideoPlayerUiState.Error>(state)
        assertEquals("movie-123", error.contentId)
        assertEquals("Failed to start playback", error.message)
    }

    @Test
    fun startPreservesExplicitZeroResumeOverride() = runTest {
        val starter = FakePlaybackStarter()
        val coordinator = VideoPlaybackSessionCoordinator(starter)

        coordinator.start(baseRequest(resumePositionOverride = 0.0))

        assertEquals(0.0, starter.requests.single().resumePositionOverride)
    }

    @Test
    fun startPreservesInvalidResumeOverrideForStarter() = runTest {
        val starter = FakePlaybackStarter()
        val coordinator = VideoPlaybackSessionCoordinator(starter)

        coordinator.start(baseRequest(resumePositionOverride = -12.5))

        assertEquals(-12.5, starter.requests.single().resumePositionOverride)
    }

    private fun baseRequest(
        resumePositionOverride: Double? = 1887.25,
    ) = VideoPlaybackStartRequest(
        contentId = "movie-123",
        preferredFileId = 44,
        roomId = null,
        resumePositionOverride = resumePositionOverride,
    )

    private class FakePlaybackStarter(
        var result: VideoPlaybackStartResult = VideoPlaybackStartResult.Ready(
            contentId = "movie-123",
            fileId = 44,
            streamUrl = "https://lib.strm.cafe/api/stream/movie",
            playMethod = PlayMethod.DIRECT,
            title = "Michael",
            subtitle = "Movie",
            artworkUrl = "https://lib.strm.cafe/poster.jpg",
            startPositionSeconds = 1887.25,
        ),
    ) : VideoPlaybackStarter {
        val requests = mutableListOf<VideoPlaybackStartRequest>()

        override suspend fun start(request: VideoPlaybackStartRequest): VideoPlaybackStartResult {
            requests += request
            return result
        }
    }
}
