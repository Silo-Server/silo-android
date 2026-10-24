package org.siloserver.silo.model.playback

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadSidecar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A download honours the series' remembered audio, and a pick on it never publishes a guess. */
class DownloadTrackPreferencesTest {
    private val source = listOf(
        AudioTrack(codec = "truehd", language = "en", title = "English Atmos", channels = 8, channelLayout = "7.1", isDefault = true),
        AudioTrack(codec = "ac3", language = "en", title = "Commentary", channels = 2, channelLayout = "stereo"),
        AudioTrack(codec = "ac3", language = "ja", title = "Japanese", channels = 6, channelLayout = "5.1"),
    )

    @Test fun aDownloadResolvesTheSavedPickAsTheServerWould() {
        val commentary = audioPreferenceUpdate(source, 1)!!
        // The exact track, even though its language has a default track first.
        assertEquals(1, savedAudioTrackPosition(source, commentary, indexIsSourceOrdinal = true))
        // A prepared download re-encoded every track: the signature no longer
        // matches and its positions are not the source's, so only the language holds.
        val prepared = source.map { it.copy(codec = "aac", channels = 2, channelLayout = "stereo") }.reversed()
        assertEquals(1, savedAudioTrackPosition(prepared, commentary, indexIsSourceOrdinal = false))
        val japanese = AudioPreferenceUpdate(audioTrackIndex = -1, audioLanguage = "jpn")
        assertEquals(2, savedAudioTrackPosition(source, japanese, indexIsSourceOrdinal = true))
        // Nothing in the saved language: the download's own default stands.
        assertNull(savedAudioTrackPosition(source, AudioPreferenceUpdate(audioTrackIndex = 0, audioLanguage = "fr"), indexIsSourceOrdinal = true))
    }

    @Test fun aPickOnAPreparedDownloadSendsOnlyItsLanguage() {
        val original = downloadAudioPreferenceUpdate(source, 2, prepared = false)!!
        assertEquals(2, original.audioTrackIndex)
        assertEquals("ac3", original.trackSignature?.codec)

        val prepared = downloadAudioPreferenceUpdate(source.map { it.copy(codec = "aac") }, 2, prepared = true)!!
        assertEquals(-1, prepared.audioTrackIndex, "an output position is not a source index")
        assertEquals("ja", prepared.audioLanguage)
        assertNull(prepared.trackSignature, "re-encoded metadata would name no source track")

        val subtitle = downloadSubtitlePreferenceUpdate(
            PlayerSubtitleInfo(index = 10_001, language = "en", label = "English SDH", codec = "subrip", url = "file:///x.srt"),
        )
        assertEquals(-1, subtitle.subtitleTrackIndex)
        assertEquals(true, subtitle.trackSignature?.hearingImpaired)
        assertEquals("off", downloadSubtitlePreferenceUpdate(null).subtitleMode)
    }

    @Test fun aDownloadOnlyNamesASeriesItWasSavedWith() {
        fun sidecar(mediaType: String, series: String?, episode: Int?) = DownloadSidecar(
            record = DownloadRecord(id = "d", contentId = "item-1", mediaFileId = 1, kind = "video", status = "completed", createdAt = ""),
            title = "t",
            mediaType = mediaType,
            seriesContentId = series,
            episodeNumber = episode,
            updatedAtMs = 0,
        )
        assertEquals("item-1", sidecar("movie", null, null).trackPreferenceKey())
        assertEquals("series-1", sidecar("tv", "series-1", 3).trackPreferenceKey())
        assertNull(sidecar("tv", null, 3).trackPreferenceKey(), "an older download without its series")
        assertNull(sidecar("audiobook", null, null).trackPreferenceKey())
    }
}
