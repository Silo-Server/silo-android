package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pref-request builders feed the server's per-series audio/subtitle
 * preference endpoints; a wrong index space or a signature field mismatch
 * silently breaks track stickiness, so the shapes are pinned here. Ports the
 * assertions from silo-apple's `TrackSelectionPersistenceTests.swift`.
 */
class TrackSelectionPersistenceTest {

    // ---- Pref key ----

    @Test
    fun prefKeyPrefersSeriesIdOverContentId() {
        assertEquals("series-9", TrackSelectionPersistence.prefKey("series-9", "ep-1"))
        assertEquals("movie-7", TrackSelectionPersistence.prefKey(null, "movie-7"))
        assertEquals("movie-7", TrackSelectionPersistence.prefKey("", "movie-7"))
        assertNull(TrackSelectionPersistence.prefKey(null, null))
        assertNull(TrackSelectionPersistence.prefKey("", ""))
    }

    // ---- Audio requests ----

    @Test
    fun audioRequestBuildsSignatureFromServerMetadata() {
        val version = FileVersion(
            fileId = 1,
            audioTracks = listOf(
                AudioTrack(language = "en", codec = "aac", channels = 2, channelLayout = "stereo", isDefault = true),
                AudioTrack(
                    language = "ja",
                    title = "Japanese 5.1",
                    codec = "eac3",
                    channels = 6,
                    channelLayout = "5.1",
                    isDefault = false,
                ),
            ),
        )

        val request = TrackSelectionPersistence.audioRequest(version, ordinal = 1)

        assertEquals(1, request?.audioTrackIndex)
        assertEquals("ja", request?.audioLanguage)
        assertEquals("ja", request?.trackSignature?.language)
        assertEquals("Japanese 5.1", request?.trackSignature?.title)
        assertEquals("Japanese 5.1", request?.trackSignature?.embeddedTitle)
        assertEquals("eac3", request?.trackSignature?.codec)
        assertEquals("5.1", request?.trackSignature?.layout)
        assertEquals(6, request?.trackSignature?.channels)
        assertEquals(false, request?.trackSignature?.isDefault)
    }

    @Test
    fun audioRequestRejectsOutOfRangeOrdinal() {
        val version = FileVersion(
            fileId = 1,
            audioTracks = listOf(AudioTrack(language = "en", codec = "aac")),
        )

        assertNull(TrackSelectionPersistence.audioRequest(version, ordinal = 1))
        assertNull(TrackSelectionPersistence.audioRequest(version, ordinal = -1))
    }

    @Test
    fun audioRequestFromLiveTrackFallsBackToTrackFields() {
        val request = TrackSelectionPersistence.audioRequest(
            ordinal = -1,
            language = "en",
            title = "Commentary",
            codec = "ac3",
            layout = "stereo",
            channels = 2,
        )

        // Unknown ordinal must persist as "no index" so the server matches by
        // signature/language.
        assertEquals(-1, request.audioTrackIndex)
        assertEquals("en", request.audioLanguage)
        assertEquals("ac3", request.trackSignature?.codec)
        assertEquals(2, request.trackSignature?.channels)
    }

    // ---- Subtitle requests ----

    @Test
    fun subtitleRequestForEmbeddedTrack() {
        val version = FileVersion(
            fileId = 1,
            subtitleTracks = listOf(
                SubtitleTrack(index = 2, language = "en", codec = "subrip", title = "English", forced = false),
            ),
        )

        val request = TrackSelectionPersistence.subtitleRequest(version, ffIndex = 2, showForced = true)

        assertEquals(2, request?.subtitleTrackIndex)
        assertEquals("en", request?.subtitleLanguage)
        assertEquals(SUBTITLE_MODE_ALWAYS, request?.subtitleMode)
        assertEquals("", request?.externalSubtitlePath)
        assertEquals("embedded", request?.trackSignature?.source)
        assertEquals("English", request?.trackSignature?.label)
        assertEquals(true, request?.showForcedSubtitles)
    }

    @Test
    fun subtitleRequestForExternalTrackCarriesPath() {
        val version = FileVersion(
            fileId = 1,
            subtitleTracks = listOf(
                SubtitleTrack(index = 5, language = "es", codec = "subrip", external = true, externalPath = "Movie.es.srt"),
            ),
        )

        val request = TrackSelectionPersistence.subtitleRequest(version, ffIndex = 5, showForced = null)

        assertEquals("external", request?.trackSignature?.source)
        assertEquals("Movie.es.srt", request?.externalSubtitlePath)
    }

    @Test
    fun subtitleRequestNegativeIndexMeansOff() {
        val version = FileVersion(
            fileId = 1,
            subtitleTracks = listOf(SubtitleTrack(index = 0, language = "en", codec = "subrip")),
        )

        val request = TrackSelectionPersistence.subtitleRequest(version, ffIndex = -1, showForced = false)

        assertEquals(-1, request?.subtitleTrackIndex)
        assertEquals("", request?.subtitleLanguage)
        assertEquals(SUBTITLE_MODE_OFF, request?.subtitleMode)
        assertNull(request?.trackSignature)
    }

    @Test
    fun subtitleRequestUnknownIndexReturnsNull() {
        val version = FileVersion(
            fileId = 1,
            subtitleTracks = listOf(SubtitleTrack(index = 0, language = "en", codec = "subrip")),
        )

        assertNull(TrackSelectionPersistence.subtitleRequest(version, ffIndex = 3, showForced = null))
    }

    @Test
    fun subtitleRequestFromSessionTrackUsesIndexAndFlags() {
        val track = PlayerSubtitleInfo(
            index = 4,
            language = "en",
            codec = "ass",
            label = "Signs & Songs",
            source = "embedded",
            forced = true,
            url = "/stream/s1/subtitles/4.ass",
        )

        val request = TrackSelectionPersistence.subtitleRequest(track, showForced = null)

        assertEquals(4, request.subtitleTrackIndex)
        assertEquals(SUBTITLE_MODE_ALWAYS, request.subtitleMode)
        assertEquals("embedded", request.trackSignature?.source)
        assertEquals(true, request.trackSignature?.forced)
        assertEquals("Signs & Songs", request.trackSignature?.label)
    }

    @Test
    fun subtitleRequestFromSessionTrackDefaultsSourceToExternal() {
        val track = PlayerSubtitleInfo(index = 0, language = "en", url = "/stream/s1/subtitles/0.vtt")

        val request = TrackSelectionPersistence.subtitleRequest(track, showForced = null)

        assertEquals("external", request.trackSignature?.source)
    }
}
