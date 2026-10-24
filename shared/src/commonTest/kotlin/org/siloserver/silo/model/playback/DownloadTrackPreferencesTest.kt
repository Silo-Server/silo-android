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

    @Test fun aDownloadSkipsAudioNoDecoderCanPlayAsTheServerDoes() {
        // The server starts index 1 for this original download; the saved
        // English must not move it back onto the track FFmpeg cannot decode.
        val tracks = listOf(AudioTrack(codec = "unknown", language = "en"), AudioTrack(codec = "aac", language = "en"))
        val english = AudioPreferenceUpdate(audioTrackIndex = 0, audioLanguage = "en")
        assertEquals(1, savedAudioTrackPosition(tracks, english, indexIsSourceOrdinal = true))
        // With nothing decodable the ordinary choice stands, as on the server.
        val none = tracks.map { it.copy(codec = "unknown") }
        assertEquals(0, savedAudioTrackPosition(none, english, indexIsSourceOrdinal = true))
    }

    @Test fun aSignatureTwoFoldedTitlesMatchGivesWayToTheSavedIndex() {
        // Source titles "English" with embedded titles "Main" and "Commentary":
        // the manifest folds both to "English" and drops the embedded title.
        val manifest = listOf(
            AudioTrack(codec = "ac3", language = "en", title = "English", channels = 2, channelLayout = "stereo"),
            AudioTrack(codec = "ac3", language = "en", title = "English", channels = 2, channelLayout = "stereo"),
        )
        val commentary = AudioPreferenceUpdate(
            audioTrackIndex = 1,
            audioLanguage = "en",
            trackSignature = AudioTrackSignature(language = "en", title = "English", embeddedTitle = "Commentary", codec = "ac3", layout = "stereo", channels = 2),
        )
        assertEquals(1, savedAudioTrackPosition(manifest, commentary, indexIsSourceOrdinal = true))
        // A source title kept only as the embedded title is folded the same way.
        val folded = listOf(manifest[0].copy(title = "Main"), manifest[1].copy(title = "Commentary"))
        val embeddedOnly = commentary.copy(audioTrackIndex = -1, trackSignature = commentary.trackSignature!!.copy(title = ""))
        assertEquals(1, savedAudioTrackPosition(folded, embeddedOnly, indexIsSourceOrdinal = true))
    }

    @Test fun aRegionalLanguageRanksAsTheServerRanksIt() {
        val tracks = listOf(AudioTrack(codec = "aac", language = "pt-PT"), AudioTrack(codec = "aac", language = "pt-BR"))
        assertEquals(1, savedAudioTrackPosition(tracks, AudioPreferenceUpdate(audioTrackIndex = -1, audioLanguage = "pt-BR"), indexIsSourceOrdinal = false))
        // A bare language takes the bare track before another variant.
        val bare = listOf(AudioTrack(codec = "aac", language = "zh-Hant"), AudioTrack(codec = "aac", language = "zho"))
        assertEquals(1, savedAudioTrackPosition(bare, AudioPreferenceUpdate(audioTrackIndex = -1, audioLanguage = "zh-Hans"), indexIsSourceOrdinal = false))
    }

    @Test fun aPickOnAPreparedDownloadSendsOnlyItsLanguage() {
        val original = downloadAudioPreferenceUpdate(source, 2, prepared = false)!!
        assertEquals(2, original.audioTrackIndex)
        assertEquals("ja", original.audioLanguage)
        assertNull(original.trackSignature, "the manifest folds the source's title and embedded title")

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
