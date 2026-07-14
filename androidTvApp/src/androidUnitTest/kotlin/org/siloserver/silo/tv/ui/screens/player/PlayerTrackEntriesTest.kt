package org.siloserver.silo.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class PlayerTrackEntriesTest {

    @Test
    fun replanSelectionMapsMedia3OrdinalToStableServerAudioIndex() {
        val catalogTracks = listOf(AudioTrack(index = 1), AudioTrack(index = 5))

        assertEquals(5, selectedServerAudioTrackIndex(1, catalogTracks, currentPlanTrackIndex = 1))
        assertEquals(1, selectedServerAudioTrackIndex(null, catalogTracks, currentPlanTrackIndex = 1))
        assertEquals(1, selectedServerAudioTrackIndex(4, catalogTracks, currentPlanTrackIndex = 1))
    }

    @Test
    fun textTracksExposeEveryTrackInsideMedia3Group() {
        val group = TrackGroup(
            subtitle(label = "English", language = "en"),
            subtitle(label = "English → Dutch (AI) (translated)", language = "en"),
            subtitle(label = "English Forced", language = "en", forced = true),
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    group,
                    false,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, true, false),
                ),
            ),
        )

        val entries = extractTrackEntries(tracks, C.TRACK_TYPE_TEXT)

        assertEquals(3, entries.size)
        assertEquals(listOf(0, 1, 2), entries.map { it.index })
        assertEquals(listOf(false, true, false), entries.map { it.isSelected })
        assertTrue(entries[1].displayLabel.contains("AI Translation"))
        assertTrue(entries[2].displayLabel.contains("Forced"))
    }

    @Test
    fun subtitleSelectionStateMarksOnlyTheAppliedTrack() {
        val tracks = listOf(
            PlayerTrackEntry(index = 0, label = "English", language = "en", isSelected = false),
            PlayerTrackEntry(index = 1, label = "Dutch", language = "nl", isSelected = false),
        )

        val selected = subtitleTracksWithSelection(tracks, selectedIndex = 1)
        val disabled = subtitleTracksWithSelection(selected, selectedIndex = -1)

        assertEquals(listOf(false, true), selected.map { it.isSelected })
        assertEquals(listOf(false, false), disabled.map { it.isSelected })
    }

    @Test
    fun autoSubtitlePreferenceMovesSelectedPgsToMatchingTextSidecar() {
        val tracks = listOf(
            PlayerTrackEntry(
                index = 2,
                label = "English (SDH)",
                language = "en",
                isSelected = true,
                codecOrMime = MimeTypes.APPLICATION_PGS,
            ),
            PlayerTrackEntry(
                index = 6,
                label = "The Day of the Jackal (2024) - S01E06 [Bluray-1080p Remux]-SiCFoI.en.sdh.srt",
                language = "en",
                isSelected = false,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
        )

        assertEquals(6, preferredAutoTextSubtitleIndex(tracks, preferredLanguage = "en"))
    }

    @Test
    fun autoSubtitlePreferenceLeavesSelectedTextTrackAlone() {
        val tracks = listOf(
            PlayerTrackEntry(
                index = 6,
                label = "English VTT",
                language = "en",
                isSelected = true,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
        )

        assertEquals(null, preferredAutoTextSubtitleIndex(tracks, preferredLanguage = "en"))
    }

    @Test
    fun autoSubtitlePreferenceMovesSelectedWrongLanguageToPreferredTextTrack() {
        val tracks = listOf(
            PlayerTrackEntry(
                index = 4,
                label = "Arabic VTT",
                language = "ar",
                isSelected = true,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
            PlayerTrackEntry(
                index = 7,
                label = "English VTT",
                language = "en",
                isSelected = false,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
        )

        assertEquals(7, preferredAutoTextSubtitleIndex(tracks, preferredLanguage = "en"))
    }

    @Test
    fun autoSubtitlePreferenceDemotesClosedCaptionTitledTracksWhenPlainDialogueExists() {
        val ccFirst = listOf(
            PlayerTrackEntry(
                index = 4,
                label = "English (CC)",
                language = "en",
                isSelected = false,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
            PlayerTrackEntry(
                index = 7,
                label = "English",
                language = "en",
                isSelected = false,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
        )
        val closedCaptionFirst = listOf(
            PlayerTrackEntry(
                index = 5,
                label = "English Closed Captions",
                language = "en",
                isSelected = false,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
            PlayerTrackEntry(
                index = 8,
                label = "English Dialogue",
                language = "en",
                isSelected = false,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
        )

        assertEquals(7, preferredAutoTextSubtitleIndex(ccFirst, preferredLanguage = "en"))
        assertEquals(8, preferredAutoTextSubtitleIndex(closedCaptionFirst, preferredLanguage = "en"))
    }

    @Test
    fun autoSubtitlePreferenceKeepsClosedCaptionTrackWhenItIsOnlyLanguageMatch() {
        val tracks = listOf(
            PlayerTrackEntry(
                index = 4,
                label = "English (CC)",
                language = "en",
                isSelected = false,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
        )

        assertEquals(4, preferredAutoTextSubtitleIndex(tracks, preferredLanguage = "en"))
    }

    @Test
    fun autoSubtitlePreferenceDoesNotTreatCcInsideWordsAsClosedCaption() {
        val tracks = listOf(
            PlayerTrackEntry(
                index = 4,
                label = "Soccer Cut",
                language = "en",
                isSelected = false,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
            PlayerTrackEntry(
                index = 7,
                label = "English",
                language = "en",
                isSelected = false,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
        )

        assertEquals(4, preferredAutoTextSubtitleIndex(tracks, preferredLanguage = "en"))
    }

    @Test
    fun autoSubtitleResolverDisablesWhenAudioAlreadyMatchesPreferredLanguage() {
        val audio = listOf(
            PlayerTrackEntry(
                index = 0,
                label = "English EAC3",
                language = "eng",
                isSelected = true,
            ),
        )
        val subtitles = listOf(
            PlayerTrackEntry(
                index = 1,
                label = "English VTT",
                language = "en",
                isSelected = true,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
        )

        assertEquals(
            SubtitleAutoSelection.Disable,
            resolveAutoSubtitleSelection(
                audioTracks = audio,
                subtitleTracks = subtitles,
                preferredLanguage = "en",
                subtitleMode = "auto",
                showForced = true,
            ),
        )
    }

    @Test
    fun autoSubtitleResolverSelectsForcedTrackWhenAudioAlreadyMatchesPreferredLanguage() {
        val audio = listOf(
            PlayerTrackEntry(
                index = 0,
                label = "English EAC3",
                language = "eng",
                isSelected = true,
            ),
        )
        val subtitles = listOf(
            PlayerTrackEntry(
                index = 1,
                label = "English VTT",
                language = "en",
                isSelected = false,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
            PlayerTrackEntry(
                index = 2,
                label = "English Forced VTT",
                language = "en",
                isSelected = false,
                isForced = true,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
        )

        assertEquals(
            SubtitleAutoSelection.Select(2),
            resolveAutoSubtitleSelection(
                audioTracks = audio,
                subtitleTracks = subtitles,
                preferredLanguage = "en",
                subtitleMode = "auto",
                showForced = true,
            ),
        )
    }

    @Test
    fun initialSubtitleOrdinalResolvesThroughMountedSubtitleMetadata() {
        val tracks = listOf(
            PlayerTrackEntry(
                index = 0,
                label = "",
                language = null,
                isSelected = false,
                codecOrMime = MimeTypes.APPLICATION_CEA608,
            ),
            PlayerTrackEntry(
                index = 1,
                label = "English SRT",
                language = "en",
                isSelected = false,
                codecOrMime = MimeTypes.TEXT_VTT,
            ),
            PlayerTrackEntry(
                index = 5,
                label = "ASS",
                language = "en",
                isSelected = false,
                codecOrMime = "text/x-ssa",
            ),
        )
        val mounted = listOf(
            PlayerSubtitleInfo(index = 0, language = "en", codec = "srt", label = "English SRT", url = "/sub/0.srt"),
            PlayerSubtitleInfo(index = 2, language = "en", codec = "ass", label = "ASS", url = "/sub/2.ass"),
        )

        assertEquals(
            5,
            resolveInitialSubtitleTrackIndex(
                requestedOrdinal = 1,
                subtitleTracks = tracks,
                mountedSubtitles = mounted,
            ),
        )
    }

    @Test
    fun initialSubtitleOrdinalDoesNotFallThroughToCeaTrack() {
        val tracks = listOf(
            PlayerTrackEntry(
                index = 0,
                label = "",
                language = null,
                isSelected = false,
                codecOrMime = MimeTypes.APPLICATION_CEA608,
            ),
        )

        assertEquals(
            null,
            resolveInitialSubtitleTrackIndex(
                requestedOrdinal = 0,
                subtitleTracks = tracks,
                mountedSubtitles = emptyList(),
            ),
        )
    }

    @Test
    fun initialEmbeddedVobSubOrdinalResolvesByCodecWithoutSyntheticMetadata() {
        val tracks = listOf(
            PlayerTrackEntry(
                index = 0,
                label = "English ASS",
                language = "en",
                isSelected = true,
                codecOrMime = "text/x-ssa",
            ),
            PlayerTrackEntry(
                index = 2,
                label = "DVD VobSub",
                language = "en",
                isSelected = false,
                codecOrMime = "application/vobsub",
            ),
        )
        val mounted = listOf(
            PlayerSubtitleInfo(
                index = 2,
                codec = "dvd_subtitle",
                source = "embedded",
                url = "",
            ),
        )

        assertEquals(
            2,
            resolveInitialSubtitleTrackIndex(
                requestedOrdinal = 2,
                subtitleTracks = tracks,
                mountedSubtitles = mounted,
            ),
        )
    }

    @Test
    fun initialEmbeddedDvbSubtitleOrdinalNormalizesFfprobeAndMedia3CodecNames() {
        val tracks = listOf(
            PlayerTrackEntry(
                index = 0,
                label = "",
                language = "fi",
                isSelected = false,
                codecOrMime = "application/dvbsubs",
            ),
        )
        val mounted = listOf(
            PlayerSubtitleInfo(
                index = 0,
                codec = "dvb_subtitle",
                source = "embedded",
                url = "",
            ),
        )

        assertEquals(
            0,
            resolveInitialSubtitleTrackIndex(
                requestedOrdinal = 0,
                subtitleTracks = tracks,
                mountedSubtitles = mounted,
            ),
        )
    }

    @Test
    fun textTracksExposeEmbeddedBitmapSubtitlesAndPreserveMedia3FlatIndex() {
        val group = TrackGroup(
            Format.Builder()
                .setLabel("English SDH")
                .setLanguage("en")
                .setSampleMimeType("application/x-media3-cues")
                .setCodecs(MimeTypes.APPLICATION_PGS)
                .build(),
            subtitle(label = "English VTT", language = "en"),
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    group,
                    false,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, false),
                ),
            ),
        )

        val entries = extractTrackEntries(tracks, C.TRACK_TYPE_TEXT)

        assertEquals(2, entries.size)
        assertEquals(listOf(0, 1), entries.map { it.index })
        assertEquals(listOf("English SDH", "English VTT"), entries.map { it.label })
        assertTrue(entries[0].displayLabel.contains("PGS"))
        assertEquals(listOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_SUBRIP), entries.map { it.codecOrMime })
    }

    @Test
    fun videoQualityOptionsFlattenPerFormatVariantsWithAutoFirst() {
        // A single video group carrying three resolution variants must surface
        // three real options, not collapse to one — plus a synthetic "Auto".
        val group = TrackGroup(
            videoFormat(width = 1920, height = 1080, bitrate = 8_000_000),
            videoFormat(width = 1280, height = 720, bitrate = 4_000_000),
            videoFormat(width = 640, height = 360, bitrate = 1_000_000),
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    group,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    // 720p explicitly selected = an override is active.
                    booleanArrayOf(false, true, false),
                ),
            ),
        )

        val options = extractVideoQualityOptions(tracks)

        // Auto + three variants.
        assertEquals(4, options.size)
        assertEquals(VIDEO_QUALITY_AUTO_ID, options.first().id)
        assertEquals("Auto", options.first().label)
        assertTrue(options[1].label.contains("1080p"))
        assertTrue(options[2].label.contains("720p"))
        assertTrue(options[3].label.contains("360p"))
        // The explicitly-selected variant (720p) is selected, not Auto.
        assertEquals(options[2].id, options.first { it.isSelected }.id, "720p variant should be selected")
    }

    @Test
    fun videoQualityAutoSelectedWhenNoOverrideAndDisabledWhenSingleVariant() {
        val adaptiveGroup = TrackGroup(
            videoFormat(width = 1920, height = 1080, bitrate = 8_000_000),
            videoFormat(width = 1280, height = 720, bitrate = 4_000_000),
        )
        val adaptive = Tracks(
            listOf(
                Tracks.Group(
                    adaptiveGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    // Adaptive: both selectable, none pinned as an override.
                    booleanArrayOf(true, true),
                ),
            ),
        )
        val adaptiveOptions = extractVideoQualityOptions(adaptive)
        // With more than one selected variant there is no single override, so
        // Auto is the selected option.
        assertTrue(adaptiveOptions.first().isSelected)

        // A single-variant group offers no genuine quality choice: Auto + one
        // variant = size 2, so the HUD row disables (hasQualityChoice = size>2).
        val singleGroup = TrackGroup(videoFormat(width = 1920, height = 1080, bitrate = 8_000_000))
        val single = Tracks(
            listOf(
                Tracks.Group(
                    singleGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(true),
                ),
            ),
        )
        val singleOptions = extractVideoQualityOptions(single)
        assertEquals(2, singleOptions.size)
        assertTrue(singleOptions.first().isSelected)
    }

    private fun videoFormat(width: Int, height: Int, bitrate: Int): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(width)
            .setHeight(height)
            .setAverageBitrate(bitrate)
            .build()

    private fun subtitle(
        label: String,
        language: String,
        forced: Boolean = false,
    ): Format = Format.Builder()
        .setLabel(label)
        .setLanguage(language)
        .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
        .setSelectionFlags(if (forced) C.SELECTION_FLAG_FORCED else 0)
        .build()
}
