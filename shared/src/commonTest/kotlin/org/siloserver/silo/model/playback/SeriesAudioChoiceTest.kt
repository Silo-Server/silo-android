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
        // An English name older files carry becomes its tag; anything else the
        // server would refuse is left out.
        assertEquals("en", SeriesAudioChoice.of(main.copy(language = "English"), 0).audioLanguage)
        assertNull(SeriesAudioChoice.of(main.copy(language = "Português"), 0).audioLanguage)
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
    fun `a choice ranks language tags and skips undecodable tracks like the server`() {
        val brazil = AudioTrack(language = "pt-BR", codec = "aac", channels = 2, title = "Portuguese")
        val portugal = brazil.copy(language = "pt-PT")
        val bare = brazil.copy(language = "por")
        val choice = SeriesAudioChoice.of(brazil, 0)
        // Same signature apart from the regional tag: the exact tag wins.
        assertEquals(1, choice.resolveOn(listOf(portugal, brazil)))
        // No exact signature: the bare language outranks another variant.
        val byLanguage = choice.copy(trackSignature = null, audioTrackIndex = 9)
        assertEquals(1, byLanguage.resolveOn(listOf(portugal.copy(title = "x"), bare.copy(title = "y"))))
        // A track FFmpeg cannot decode is never the answer while another can.
        assertEquals(1, choice.resolveOn(listOf(brazil.copy(codec = "unknown"), portugal)))
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

    @Test
    fun `carried picks apply to the same series only and never reuse a position`() {
        val detail = WatchDetail(
            contentId = "episode-3", type = "episode", title = "E3", seriesId = "series-1",
            versions = listOf(FileVersion(fileId = 3, audioTracks = listOf(japanese, main), effectiveAudioTrackIndex = 1)),
        )
        val carried = null.withPick("series-1", audio = SeriesAudioChoice.of(japanese, 2))
            .withPick("series-1", subtitle = SeriesSubtitleChoice.Off)
        val applied = detail.withCarriedSeriesTrackChoices(carried)
        assertEquals(0, applied.versions.single().effectiveAudioTrackIndex)
        assertEquals("off", applied.effectiveSubtitleMode)
        assertEquals(detail, detail.copy(seriesId = "series-2").withCarriedSeriesTrackChoices(carried).copy(seriesId = "series-1"))
        // A pick for another series starts over.
        assertNull(carried.withPick("series-2", subtitle = SeriesSubtitleChoice.Off).audio)
    }
}
