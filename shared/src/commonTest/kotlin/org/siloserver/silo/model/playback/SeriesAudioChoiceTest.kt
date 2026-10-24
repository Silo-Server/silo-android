package org.siloserver.silo.model.playback

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.playback.audioTrackFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeriesAudioChoiceTest {

    private val main = AudioTrack(language = "eng", codec = "truehd", channels = 8, channelLayout = "7.1", title = "English Atmos")
    private val commentary = AudioTrack(language = "eng", codec = "ac3", channels = 2, channelLayout = "stereo", title = "Commentary")
    private val japanese = AudioTrack(language = "jpn", codec = "aac", channels = 2, channelLayout = "stereo", embeddedTitle = "Stereo")

    @Test
    fun `a pick is sent as the server's body, without default or an unknown language`() {
        val body = SiloJson.encodeToJsonElement(
            SeriesAudioChoice.serializer(),
            SeriesAudioChoice.of(japanese.copy(isDefault = true), 2),
        ).jsonObject
        assertEquals(
            JsonObject(
                mapOf(
                    "audio_track_index" to JsonPrimitive(2),
                    "audio_language" to JsonPrimitive("jpn"),
                    "track_signature" to JsonObject(
                        mapOf(
                            "language" to JsonPrimitive("jpn"),
                            "embedded_title" to JsonPrimitive("Stereo"),
                            "codec" to JsonPrimitive("aac"),
                            "layout" to JsonPrimitive("stereo"),
                            "channels" to JsonPrimitive(2),
                        ),
                    ),
                ),
            ),
            body,
        )
        assertNull(SeriesAudioChoice.of(main.copy(language = "und"), 0).audioLanguage)
        assertNull(SeriesAudioChoice.of(main.copy(language = "English"), 0).audioLanguage)
    }

    @Test
    fun `a choice resolves by signature first, across ISO 639 forms, like the server`() {
        val choice = SeriesAudioChoice.of(commentary.copy(language = "en"), 1)
        // Another episode lists the commentary first and as "eng".
        assertEquals(0, choice.resolveOn(listOf(commentary, main, japanese)))
        // No exact track: the remembered position while it holds the language…
        val otherEpisode = listOf(japanese, main.copy(title = "English"))
        assertEquals(1, choice.resolveOn(otherEpisode))
        // …else the first track in the language, else nothing.
        assertEquals(1, choice.copy(audioTrackIndex = 0).resolveOn(otherEpisode))
        assertNull(choice.resolveOn(listOf(japanese)))
    }

    @Test
    fun `a pending choice overlays each version's effective track, and auto clears it`() {
        val detail = WatchDetail(
            contentId = "episode-2", type = "episode", title = "E2", seriesId = "series-1",
            versions = listOf(
                FileVersion(fileId = 1, audioTracks = listOf(main, japanese), effectiveAudioTrackIndex = 0),
                FileVersion(fileId = 2, audioTracks = listOf(japanese, main), effectiveAudioTrackIndex = 1),
            ),
        )
        val japaneseChoice = SeriesAudioChoice.of(japanese, 1)
        assertEquals(
            listOf(1, 0),
            detail.withPendingSeriesAudioChoice(PendingSeriesAudioChoice(japaneseChoice)).versions.map { it.effectiveAudioTrackIndex },
        )
        assertEquals(
            listOf(null, null),
            detail.withPendingSeriesAudioChoice(PendingSeriesAudioChoice(null)).versions.map { it.effectiveAudioTrackIndex },
        )
        assertEquals(detail, detail.withPendingSeriesAudioChoice(null))
    }

    @Test
    fun `online the per-file audio memory only restores the server's track`() {
        val tracks = listOf(main, commentary, japanese)
        val olderPick = audioTrackFingerprint(commentary)
        assertNull(localAudioMemoryForSeriesChoice(olderPick, tracks, serverOrdinal = 2))
        assertEquals(olderPick, localAudioMemoryForSeriesChoice(olderPick, tracks, serverOrdinal = 1))
        // No server answer (offline, an older server, a reset not landed yet).
        assertEquals(olderPick, localAudioMemoryForSeriesChoice(olderPick, tracks, serverOrdinal = null))
    }

}
