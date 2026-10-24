package org.siloserver.silo.model.playback

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.playback.SUBTITLE_OFF_FINGERPRINT
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import org.siloserver.silo.playback.subtitleTrackFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesSubtitleChoiceTest {

    private val englishSdh = PlayerSubtitleInfo(
        index = 3, language = "en", codec = "subrip", label = "English (SDH)", source = "external",
        url = "/s/3.srt",
    )

    @Test
    fun `an episode is remembered under its series and a movie under itself`() {
        assertEquals("series-1", seriesSubtitleChoiceKey("series-1", "episode-9"))
        assertEquals("movie-4", seriesSubtitleChoiceKey(null, "movie-4"))
        assertEquals("movie-4", seriesSubtitleChoiceKey("", "movie-4"))
        assertNull(seriesSubtitleChoiceKey(null, " "))
    }

    @Test
    fun `off is spelled as on web and Apple and never pins forced subtitles`() {
        val body = SiloJson.encodeToJsonElement(SeriesSubtitleChoice.serializer(), SeriesSubtitleChoice.Off).jsonObject
        assertEquals(
            JsonObject(
                mapOf(
                    "subtitle_language" to JsonPrimitive(""),
                    "subtitle_track_index" to JsonPrimitive(-1),
                    "subtitle_mode" to JsonPrimitive("off"),
                ),
            ),
            body,
        )
    }

    @Test
    fun `a track pick carries the server signature and no forced override`() {
        val choice = SeriesSubtitleChoice.of(englishSdh)
        assertEquals("en", choice.subtitleLanguage)
        assertEquals(3, choice.subtitleTrackIndex)
        assertEquals("always", choice.subtitleMode)
        assertEquals(
            SubtitleTrackSignature(
                source = "external", language = "en", codec = "subrip", label = "English (SDH)",
                forced = false, hearingImpaired = true,
            ),
            choice.trackSignature,
        )
        val body = SiloJson.encodeToJsonElement(SeriesSubtitleChoice.serializer(), choice).jsonObject
        assertFalse("show_forced_subtitles" in body)
        assertEquals(JsonPrimitive(true), body["track_signature"]!!.jsonObject["hearing_impaired"])
    }

    @Test
    fun `a catalog pick uses the combined selection index`() {
        val track = SubtitleTrack(index = 2, codec = "pgs", language = "fr", title = "Français", forced = true)
        val choice = SeriesSubtitleChoice.of(track, selectionIndex = 5)
        assertEquals(5, choice.subtitleTrackIndex)
        assertEquals("embedded", choice.trackSignature?.source)
        assertTrue(choice.trackSignature!!.forced)
    }

    @Test
    fun `an identity pick is described from the session inventory`() {
        assertEquals(SeriesSubtitleChoice.Off, SeriesSubtitleChoice.of(SubtitleIdentity.Off, listOf(englishSdh)))
        assertEquals(
            SeriesSubtitleChoice.of(englishSdh),
            SeriesSubtitleChoice.of(SubtitleIdentity.ServerSidecar(serverIndex = 3), listOf(englishSdh)),
        )
        assertNull(SeriesSubtitleChoice.of(SubtitleIdentity.ServerSidecar(serverIndex = 8), listOf(englishSdh)))
    }

    @Test
    fun `the watch detail decodes the remembered track`() {
        val detail = SiloJson.decodeFromString(
            WatchDetail.serializer(),
            """{"content_id":"e1","type":"episode","title":"Pilot","effective_subtitle_mode":"always",
               "effective_subtitle_track_signature":{"source":"embedded","language":"es","codec":"ass",
               "label":"Latino","forced":false,"hearing_impaired":false}}""",
        )
        assertEquals(
            SubtitleTrackSignature(source = "embedded", language = "es", codec = "ass", label = "Latino"),
            detail.effectiveSubtitleTrackSignature,
        )
    }

    @Test
    fun `a pending choice overlays the server's effective fields`() {
        val served = WatchDetail(
            contentId = "e2", type = "episode", title = "E2", seriesId = "s",
            effectiveSubtitleLanguage = "en", effectiveSubtitleMode = "always",
            effectiveSubtitleTrackSignature = SubtitleTrackSignature(language = "en"),
        )
        val track = served.withPendingSeriesSubtitleChoice(PendingSeriesSubtitleChoice(SeriesSubtitleChoice.of(englishSdh)))
        assertEquals("always", track.effectiveSubtitleMode)
        assertEquals(SeriesSubtitleChoice.of(englishSdh).trackSignature, track.effectiveSubtitleTrackSignature)

        val off = served.withPendingSeriesSubtitleChoice(PendingSeriesSubtitleChoice(SeriesSubtitleChoice.Off))
        assertEquals("off", off.effectiveSubtitleMode)
        assertNull(off.effectiveSubtitleTrackSignature)

        val automatic = served.withPendingSeriesSubtitleChoice(PendingSeriesSubtitleChoice(null))
        assertNull(automatic.effectiveSubtitleTrackSignature)
        assertNull(automatic.effectiveSubtitleLanguage, "the starters fall back to the profile's")
        assertNull(automatic.effectiveSubtitleMode)

        // The served Off is the series choice being deleted: it must not keep
        // subtitles off while the reset waits to land.
        val servedOff = served.copy(effectiveSubtitleMode = "off", effectiveSubtitleLanguage = "")
        assertNull(servedOff.withPendingSeriesSubtitleChoice(PendingSeriesSubtitleChoice(null)).effectiveSubtitleMode)

        assertEquals(served, served.withPendingSeriesSubtitleChoice(null))
    }

    // ---- resolver --------------------------------------------------------

    private val tracks = listOf(
        AutoSubtitleCandidate(selectionIndex = 0, language = "es", codec = "ass", title = "Castellano", source = AutoSubtitleSource.EMBEDDED),
        AutoSubtitleCandidate(selectionIndex = 1, language = "es", codec = "ass", title = "Latino", source = AutoSubtitleSource.EMBEDDED),
        AutoSubtitleCandidate(selectionIndex = 2, language = "en", codec = "srt", title = "English", source = AutoSubtitleSource.EXTERNAL),
        AutoSubtitleCandidate(selectionIndex = 3, language = "en", codec = "srt", title = "Signs", forced = true, source = AutoSubtitleSource.EXTERNAL),
    )

    private fun resolve(context: AutoSubtitleContext) =
        resolveAutoSubtitle(tracks, context).selectedCandidate()?.selectionIndex

    @Test
    fun `the series signature wins over the language rules`() {
        val latino = SubtitleTrackSignature(source = "embedded", language = "es", codec = "ass", label = "Latino")
        // Without the signature, "es always" starts on the first Spanish track.
        assertEquals(0, resolve(AutoSubtitleContext("es", "always")))
        assertEquals(1, resolve(AutoSubtitleContext("es", "always", trackSignature = latino)))
        // A remembered forced track is the viewer's pick, so it is honoured.
        val signs = SubtitleTrackSignature(source = "external", language = "en", codec = "srt", label = "Signs", forced = true)
        assertEquals(3, resolve(AutoSubtitleContext("en", "always", showForced = true, trackSignature = signs)))
    }

    @Test
    fun `off for the series with forced subtitles off stays off, whatever the signature`() {
        val latino = SubtitleTrackSignature(source = "embedded", language = "es", codec = "ass", label = "Latino")
        assertEquals(
            AutoSubtitleResolution.Disable,
            resolveAutoSubtitle(
                tracks,
                AutoSubtitleContext("es", "off", showForced = false, audioLanguage = "en", trackSignature = latino),
            ),
        )
    }

    @Test
    fun `off for the series with forced subtitles on starts the forced track for the audio, not the signature`() {
        val latino = SubtitleTrackSignature(source = "embedded", language = "es", codec = "ass", label = "Latino")
        assertEquals(
            3,
            resolve(AutoSubtitleContext("es", "off", showForced = true, audioLanguage = "en", trackSignature = latino)),
        )
    }

    @Test
    fun `a signature with no exact match breaks ties within the language`() {
        // Another episode: same "Latino" track but a different codec. The
        // label still steers the pick away from the first Spanish track.
        val latinoSrt = SubtitleTrackSignature(source = "embedded", language = "es", codec = "srt", label = "Latino")
        assertEquals(1, resolve(AutoSubtitleContext("es", "always", trackSignature = latinoSrt)))
    }

    @Test
    fun `a signature recorded as eng finds the track listed as en`() {
        val latino = SubtitleTrackSignature(source = "embedded", language = "spa", codec = "ass", label = "Latino")
        assertEquals(1, resolve(AutoSubtitleContext("es", "always", trackSignature = latino)))
        assertFalse(tracks[2].matchesSignature(SubtitleTrackSignature(source = "external", language = "fre", codec = "srt")))
    }

    @Test
    fun `forced never outranks the full track without a series choice`() {
        assertEquals(2, resolve(AutoSubtitleContext("en", "always", showForced = true)))
    }

    // ---- local memory ----------------------------------------------------

    @Test
    fun `offline the local memory restores as before`() {
        assertEquals("x", localSubtitleMemoryForSeriesChoice("x", serverChoiceKnown = false, signature = null))
    }

    @Test
    fun `online without a series choice the local memory is ignored`() {
        val local = encodeSubtitleIdentityPreference(SubtitleIdentity.ServerSidecar(3, SubtitleMediaIdentity(language = "en")))
        assertNull(localSubtitleMemoryForSeriesChoice(local, serverChoiceKnown = true, signature = null))
        assertNull(localSubtitleMemoryForSeriesChoice(SUBTITLE_OFF_FINGERPRINT, serverChoiceKnown = true, signature = null))
    }

    @Test
    fun `online the local memory only restores the series choice itself`() {
        val signature = SubtitleTrackSignature(language = "eng", label = "English")
        val sameChoice = encodeSubtitleIdentityPreference(
            SubtitleIdentity.Downloaded(downloadId = 7, media = SubtitleMediaIdentity(language = "en", forced = false)),
        )
        val otherChoice = encodeSubtitleIdentityPreference(
            SubtitleIdentity.ServerSidecar(3, SubtitleMediaIdentity(language = "fr")),
        )
        assertEquals(sameChoice, localSubtitleMemoryForSeriesChoice(sameChoice, true, signature))
        assertNull(localSubtitleMemoryForSeriesChoice(otherChoice, true, signature))
        assertNull(localSubtitleMemoryForSeriesChoice(SUBTITLE_OFF_FINGERPRINT, true, signature))
        val legacy = subtitleTrackFingerprint(SubtitleTrack(index = 1, language = "en", codec = "srt", title = "English"))
        assertEquals(legacy, localSubtitleMemoryForSeriesChoice(legacy, true, signature))
    }

    @Test
    fun `online the local memory of an SDH track does not restore a plain series choice`() {
        val plain = SubtitleTrackSignature(language = "en", label = "English", hearingImpaired = false)
        val sdh = plain.copy(label = "English SDH", hearingImpaired = true)
        val localSdh = encodeSubtitleIdentityPreference(
            SubtitleIdentity.Downloaded(downloadId = 7, media = SubtitleMediaIdentity(language = "en", forced = false, hearingImpaired = true)),
        )
        val localSdhByLabel = encodeSubtitleIdentityPreference(
            SubtitleIdentity.ServerSidecar(3, SubtitleMediaIdentity(language = "en", label = "English SDH")),
        )
        assertNull(localSubtitleMemoryForSeriesChoice(localSdh, true, plain))
        assertNull(localSubtitleMemoryForSeriesChoice(localSdhByLabel, true, plain))
        assertEquals(localSdh, localSubtitleMemoryForSeriesChoice(localSdh, true, sdh))
    }
}
