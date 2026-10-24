package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.common.player.MountedSubtitleTrack
import org.siloserver.silo.common.player.resolveMountedSubtitle
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.download.OfflineSubtitleFile
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitlePreferenceUpdate
import org.siloserver.silo.model.playback.SubtitleTrackSignature
import org.siloserver.silo.model.playback.downloadSubtitlePreferenceUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflinePlaybackTracksTest {

    private val files = listOf(
        OfflineSubtitleFile(path = "/data/app/subs/0.ass", format = "ass", language = "eng", title = "Signs & Songs"),
        OfflineSubtitleFile(path = "/data/app/subs/1.sup", format = "pgs", language = "eng", forced = true),
        OfflineSubtitleFile(path = "/data/app/subs/2.srt", format = "srt", language = "fre", hearingImpaired = true),
    )

    /** A prepared MP4's timed text, the two merged sidecars, and a bitmap track the menu drops. */
    private val mediaTracks = listOf(
        textTrack(0, "0:3", null, "eng", "application/x-quicktime-tx3g"),
        textTrack(1, "0:4", null, "spa", "application/x-quicktime-tx3g"),
        textTrack(2, "1:silo-subtitle:0", "English", "eng", "text/x-ssa"),
        textTrack(3, "2:silo-subtitle:1", "English", "eng", "application/pgs", forced = true),
        textTrack(4, "0:5", null, "ger", "application/vobsub"),
    )

    @Test
    fun sidecarRowsSkipMissingFilesAndPointAtTheLocalFile() {
        val rows = offlineSidecarSubtitleRows(files) { !it.endsWith("1.sup") }

        assertEquals(listOf(0, 1), rows.map { it.index })
        assertEquals(listOf("eng", "fre"), rows.map { it.language })
        assertEquals("file:///data/app/subs/0.ass", rows[0].url)
        assertEquals("Signs & Songs", rows[0].label)
        assertEquals("SDH", rows[1].label)
    }

    @Test
    fun offlineLocalRowsNeverBecomeServerSubtitleIndexes() {
        val rows = offlineSidecarSubtitleRows(files) { true } + localEmbeddedSubtitleRows(mediaTracks)

        // Cast and replans send this index to the server; a local menu position
        // names no server subtitle, so the server applies preferences instead.
        rows.indices.forEach { ordinal -> assertNull(selectedServerSubtitleTrackIndex(ordinal, rows)) }
        assertEquals(-1, selectedServerSubtitleTrackIndex(-1, rows))
    }

    @Test
    fun sidecarSelectionResolvesToItsMergedMedia3Track() {
        val rows = offlineSidecarSubtitleRows(files) { true }

        val identity = assertIs<SubtitleIdentity.ServerSidecar>(mobileSubtitleIdentity(rows[1]))
        assertEquals(1, identity.serverIndex)
        val match = assertNotNull(resolveMountedSubtitle(identity, mediaTracks))
        assertEquals("2:silo-subtitle:1", match.track.trackId)
    }

    @Test
    fun embeddedRowsListOnlyTheFilesOwnRenderableTracks() {
        val rows = localEmbeddedSubtitleRows(mediaTracks)

        assertEquals(listOf("0:3", "0:4"), rows.map { it.mediaTrackId })
        assertTrue(rows.all { it.url.isBlank() && it.source == "embedded" })
        assertTrue(rows.all { it.index >= OFFLINE_EMBEDDED_SUBTITLE_INDEX_BASE })
        val identity = assertIs<SubtitleIdentity.Embedded>(mobileSubtitleIdentity(rows[1]))
        val match = assertNotNull(resolveMountedSubtitle(identity, mediaTracks))
        assertEquals("0:4", match.track.trackId)
    }

    @Test
    fun menuSelectionResolvesBackToTheSameRow() {
        val rows = localEmbeddedSubtitleRows(mediaTracks) + offlineSidecarSubtitleRows(files) { true }

        rows.forEachIndexed { ordinal, row ->
            assertEquals(ordinal, resolveMobileSubtitleOrdinal(mobileSubtitleIdentity(row), rows))
        }
    }

    @Test
    fun preferencesPickASubtitleWhileTheFileDefaultFlagIsIgnored() {
        val rows = localEmbeddedSubtitleRows(mediaTracks) + offlineSidecarSubtitleRows(files) { true }
        val audio = listOf(
            AudioTrack(index = 0, codec = "aac", channels = 2, language = "jpn"),
            AudioTrack(index = 1, codec = "aac", channels = 2, language = "eng"),
        )

        // Japanese audio with an English subtitle preference: the first
        // full English text track.
        assertEquals(
            MobileSubtitleAutoSelection.Select(0),
            resolveMobileAutoSubtitleSelection(audio, 0, rows, "eng", null, showForcedSubtitles = true),
        )
        // English audio: only a forced English track may turn on.
        val forced = resolveMobileAutoSubtitleSelection(audio, 1, rows, "eng", null, showForcedSubtitles = true)
        val forcedRow = rows[(forced as MobileSubtitleAutoSelection.Select).ordinal]
        assertEquals(true, forcedRow.forced)
        // No preference: nothing turns on, whatever the file marks default.
        assertEquals(
            MobileSubtitleAutoSelection.NoChange,
            resolveMobileAutoSubtitleSelection(audio, 0, rows, null, null, showForcedSubtitles = true),
        )
    }

    @Test
    fun anSdhSidecarWithAPlainTitleIsSavedAsSdh() {
        val sdh = OfflineSubtitleFile(path = "/data/app/subs/3.srt", format = "srt", language = "eng", title = "English", hearingImpaired = true)
        val row = offlineSidecarSubtitleRows(listOf(sdh)) { true }.single()

        assertEquals(true, downloadSubtitlePreferenceUpdate(row).trackSignature?.hearingImpaired)
        // And the saved SDH track is found again among plain and SDH English.
        val plain = OfflineSubtitleFile(path = "/data/app/subs/4.srt", format = "srt", language = "eng", title = "English")
        val rows = offlineSidecarSubtitleRows(listOf(plain, sdh)) { true }
        val signature = SubtitleTrackSignature(language = "en", codec = "srt", label = "English", hearingImpaired = true)
        assertEquals(
            MobileSubtitleAutoSelection.Select(1),
            resolveMobileAutoSubtitleSelection(emptyList(), 0, rows, "en", "always", showForcedSubtitles = false, trackSignature = signature),
        )
    }

    @Test
    fun aSeriesRowWithoutALanguageOrModeKeepsTheInheritedOnes() {
        // A row holding only a forced-subtitle override: the profile's English/Always still applies.
        val inherited = OfflineSubtitlePreferences(preferredLanguage = "en", mode = "always", showForced = false)
        val forcedOnly = SubtitlePreferenceUpdate(subtitleTrackIndex = -1, subtitleLanguage = "", subtitleMode = "", showForcedSubtitles = true)
        val rows = offlineSidecarSubtitleRows(files) { true }

        val merged = inherited.withSeriesRow(forcedOnly)
        assertEquals(OfflineSubtitlePreferences(preferredLanguage = "en", mode = "always", showForced = true), merged)
        assertEquals(
            MobileSubtitleAutoSelection.Select(0),
            resolveMobileAutoSubtitleSelection(emptyList(), 0, rows, merged.preferredLanguage, merged.mode, merged.showForced, merged.trackSignature),
        )
        // Off is a mode, so it still turns subtitles off.
        val off = inherited.withSeriesRow(forcedOnly.copy(subtitleMode = "off"))
        assertEquals(
            MobileSubtitleAutoSelection.Disable,
            resolveMobileAutoSubtitleSelection(emptyList(), 0, rows, off.preferredLanguage, off.mode, off.showForced, off.trackSignature),
        )
    }

    private fun textTrack(
        index: Int,
        trackId: String,
        label: String?,
        language: String,
        codec: String,
        forced: Boolean = false,
    ) = MountedSubtitleTrack(index, trackId, label, language, codec, forced, hearingImpaired = false)
}
