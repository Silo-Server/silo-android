package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.catalog.VideoTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPlaybackFormattingTest {

    // --- versionShortLabel ---

    @Test fun versionShortLabel_4kHdr() {
        val v = fileVersion(resolution = "2160p", hdr = true)
        assertEquals("4K · HDR", TvPlaybackFormatting.versionShortLabel(v))
    }

    @Test fun versionShortLabel_4kDolbyVision() {
        val v = fileVersion(
            resolution = "2160p",
            hdr = true,
            video = listOf(VideoTrack(codec = "hevc", dolbyVision = "Profile 7")),
        )
        assertEquals("4K · DV", TvPlaybackFormatting.versionShortLabel(v))
    }

    @Test fun versionShortLabel_1080() {
        assertEquals(
            "1080P",
            TvPlaybackFormatting.versionShortLabel(fileVersion(resolution = "1080p", hdr = false)),
        )
    }

    @Test fun versionShortLabel_nullIsAuto() {
        assertEquals("Auto", TvPlaybackFormatting.versionShortLabel(null))
    }

    @Test fun versionShortLabel_blankResolutionNoHdrIsAuto() {
        assertEquals("Auto", TvPlaybackFormatting.versionShortLabel(fileVersion(resolution = null, hdr = false)))
    }

    @Test fun versionValueLabel_matchesTvOsDolbyVisionSummary() {
        val v = fileVersion(
            resolution = "2160p",
            codecVideo = "hevc",
            hdr = true,
            video = listOf(
                VideoTrack(
                    codec = "hevc",
                    dolbyVision = "Profile 8",
                    dolbyVisionProfile = 8,
                    hdr = true,
                ),
            ),
            audio = listOf(audioTrack(codec = "truehd", layout = "7.1", default = true)),
        )

        assertEquals(
            "Auto: 2160p · HEVC · DV · TrueHD",
            TvPlaybackFormatting.versionValueLabel(v, selectedVersionFileId = null),
        )
        assertTrue(TvPlaybackFormatting.isDolbyVision(v))
    }

    @Test fun versionValueLabel_fallsBackToHdrAndVersionAudioCodec() {
        val v = fileVersion(
            resolution = "1080p",
            codecVideo = "h264",
            codecAudio = "eac3",
            hdr = true,
        )

        assertEquals(
            "1080p · H.264 · HDR · EAC3",
            TvPlaybackFormatting.versionValueLabel(v, selectedVersionFileId = 1),
        )
    }

    // --- versionDetailLabel ---

    @Test fun versionDetailLabel_codecContainerSize() {
        val v = fileVersion(
            resolution = "2160p",
            codecVideo = "hevc",
            container = "mkv",
            fileSize = 8L * 1024 * 1024 * 1024,
        )
        // 8 GiB → decimal/adaptive like Apple's ByteCountFormatter (.file): 8.59 GB.
        assertEquals("HEVC · MKV · 8.59 GB", TvPlaybackFormatting.versionDetailLabel(v))
    }

    @Test fun versionDetailLabel_megabytesDecimal() {
        val v = fileVersion(codecVideo = "h264", container = "mp4", fileSize = 500L * 1024 * 1024)
        // 500 MiB = 524_288_000 bytes → 524.3 MB (decimal, 1 dp), matching ByteCountFormatter.
        assertEquals("H.264 · MP4 · 524.3 MB", TvPlaybackFormatting.versionDetailLabel(v))
    }

    // --- audioValueLabel / audioOptions ---

    @Test fun audioValueLabel_codecLayoutLanguage() {
        val v = fileVersion(
            audio = listOf(audioTrack(codec = "EAC3", layout = "5.1", lang = "English", default = true)),
        )
        // Auto previews its resolution (QA 2026-07-08 / Apple parity).
        assertEquals("Auto: English · EAC3 · 5.1", TvPlaybackFormatting.audioValueLabel(v, selectedAudioTrackIndex = null))
        assertEquals("English · EAC3 · 5.1", TvPlaybackFormatting.audioValueLabel(v, selectedAudioTrackIndex = 0))
    }

    @Test fun audioValueLabel_eng3LetterCode() {
        val v = fileVersion(audio = listOf(audioTrack(codec = "aac", layout = "stereo", lang = "eng")))
        assertEquals("Auto: English · AAC · Stereo", TvPlaybackFormatting.audioValueLabel(v, selectedAudioTrackIndex = null))
    }

    @Test fun audioValueLabel_unknownWhenNoTracks() {
        assertEquals("Unknown", TvPlaybackFormatting.audioValueLabel(fileVersion(), selectedAudioTrackIndex = null))
    }

    @Test fun audioOptions_selectionFollowsSelectedIndex() {
        val v = fileVersion(
            audio = listOf(
                audioTrack(codec = "aac", layout = "stereo", lang = "eng"),
                audioTrack(codec = "eac3", layout = "5.1", lang = "fre"),
            ),
        )
        val opts = TvPlaybackFormatting.audioOptions(v, selectedAudioTrackIndex = 1)
        assertEquals(2, opts.size)
        assertEquals(0, opts[0].ordinal)
        assertFalse(opts[0].isSelected)
        assertTrue(opts[1].isSelected)
    }

    @Test fun audioOptions_defaultSelectedWhenNoSelection() {
        val v = fileVersion(
            audio = listOf(
                audioTrack(codec = "aac", lang = "eng"),
                audioTrack(codec = "eac3", lang = "fre", default = true),
            ),
        )
        val opts = TvPlaybackFormatting.audioOptions(v, selectedAudioTrackIndex = null)
        assertFalse(opts[0].isSelected)
        assertTrue(opts[1].isSelected)
    }

    @Test fun audioValueLabel_effectiveIndexBeatsDefaultWhenAuto() {
        // The server-resolved effective track sits between an explicit pick and
        // the isDefault flag (Apple parity: selected → effective → default → first).
        val v = fileVersion(
            audio = listOf(
                audioTrack(codec = "aac", layout = "stereo", lang = "eng", default = true),
                audioTrack(codec = "eac3", layout = "5.1", lang = "fre"),
            ),
            effectiveAudioIndex = 1,
        )
        assertEquals("Auto: French · EAC3 · 5.1", TvPlaybackFormatting.audioValueLabel(v, selectedAudioTrackIndex = null))
        // An explicit selection still wins over the effective index.
        assertEquals("English · AAC · Stereo", TvPlaybackFormatting.audioValueLabel(v, selectedAudioTrackIndex = 0))
    }

    @Test fun audioValueLabel_outOfRangeEffectiveIndexFallsBackToDefault() {
        val v = fileVersion(
            audio = listOf(
                audioTrack(codec = "aac", layout = "stereo", lang = "eng"),
                audioTrack(codec = "eac3", layout = "5.1", lang = "fre", default = true),
            ),
            effectiveAudioIndex = 7,
        )
        assertEquals("Auto: French · EAC3 · 5.1", TvPlaybackFormatting.audioValueLabel(v, selectedAudioTrackIndex = null))
    }

    @Test fun audioOptions_effectiveIndexSelectedWhenNoSelection() {
        val v = fileVersion(
            audio = listOf(
                audioTrack(codec = "aac", lang = "eng", default = true),
                audioTrack(codec = "eac3", lang = "fre"),
            ),
            effectiveAudioIndex = 1,
        )
        val opts = TvPlaybackFormatting.audioOptions(v, selectedAudioTrackIndex = null)
        assertFalse(opts[0].isSelected)
        assertTrue(opts[1].isSelected)
    }

    // --- subtitleValueLabel / subtitleOptions ---

    @Test fun subtitleValueLabel_offForMinusOne() {
        assertEquals("Off", TvPlaybackFormatting.subtitleValueLabel(fileVersion(), selectedSubtitleTrackIndex = -1))
    }

    @Test fun subtitleValueLabel_autoWithoutContextIsBare() {
        // No resolution inputs (no autoContext): we can't preview the pick, so
        // show a bare "Auto" rather than guessing from isDefault (which the
        // player's resolver never consults) — QA 2026-07-09 / tvOS parity.
        val v = fileVersion(subtitles = listOf(subtitleTrack(lang = "eng"), subtitleTrack(lang = "fre")))
        assertEquals("Auto", TvPlaybackFormatting.subtitleValueLabel(v, selectedSubtitleTrackIndex = null))
    }

    @Test fun subtitleValueLabel_autoWithoutContextSingleTrackShowsTrack() {
        val v = fileVersion(subtitles = listOf(subtitleTrack(lang = "eng")))
        assertEquals("English", TvPlaybackFormatting.subtitleValueLabel(v, selectedSubtitleTrackIndex = null))
    }

    // --- Auto preview resolved through the REAL selection rules (tvOS parity) ---

    @Test fun subtitleValueLabel_autoResolvesPreferredLanguage() {
        // preferred=fr, audio=en → readable French subs auto-selected.
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(lang = "eng"),
                subtitleTrack(lang = "fre"),
            ),
        )
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(
            preferredLanguage = "fr",
            mode = "auto",
            audioLanguage = "eng",
        )
        assertEquals("Auto: French", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoNoneWhenAudioMatchesPreferred() {
        // auto mode hides subs when audio is already in the preferred language.
        val v = fileVersion(subtitles = listOf(subtitleTrack(lang = "eng")))
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(
            preferredLanguage = "en",
            mode = "auto",
            audioLanguage = "eng",
        )
        assertEquals("Auto: Off", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoForcedWhenAudioMatchesAndShowForced() {
        // audio matches preferred + showForced → the language-matching forced
        // (signs-only) track is exactly what plays.
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(lang = "eng"),
                subtitleTrack(lang = "eng", forced = true),
            ),
        )
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(
            preferredLanguage = "en",
            mode = "auto",
            showForced = true,
            audioLanguage = "eng",
        )
        assertEquals("Auto: English (Forced)", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoNoneWhenModeOff() {
        val v = fileVersion(subtitles = listOf(subtitleTrack(lang = "eng")))
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(preferredLanguage = "en", mode = "off")
        assertEquals("Auto: Off", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoNoneWhenPreferenceIsNoSubs() {
        // Empty (not null) preferred language means "no subs" at this level.
        val v = fileVersion(subtitles = listOf(subtitleTrack(lang = "eng")))
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(preferredLanguage = "", mode = "auto")
        assertEquals("Auto: Off", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoNoneWhenNoPreferenceAndModeAuto() {
        // No language preference + plain auto → resolver leaves subs off.
        val v = fileVersion(subtitles = listOf(subtitleTrack(lang = "eng")))
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(preferredLanguage = null, mode = "auto")
        assertEquals("Auto: Off", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoAlwaysPicksFullDialogueWithNoPreference() {
        // mode=always with no language pref falls back to the best track,
        // preferring full-dialogue (non-forced, non-SDH) over the forced one.
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(lang = "fre", forced = true),
                subtitleTrack(lang = "fre"),
            ),
        )
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(preferredLanguage = null, mode = "always")
        assertEquals("Auto: French", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoPrefersFullDialogueOverSdh() {
        // With a language match, SDH is skipped in favour of the readable track.
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(lang = "eng", title = "English SDH"),
                subtitleTrack(lang = "eng"),
            ),
        )
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(
            preferredLanguage = "en",
            mode = "auto",
            audioLanguage = "jpn",
        )
        assertEquals("Auto: English", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoSkipsDvbBitmapForTextTrack() {
        // ffprobe reports DVB as "dvb_subtitle"; the old '_'→'-' normalization
        // never matched its "dvbsubs" token, so the bitmap track leaked past
        // the resolver's text-track preference. Alphanumeric-only
        // normalization classifies it as bitmap (Apple token-set parity).
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(lang = "fre", codec = "dvb_subtitle"),
                subtitleTrack(lang = "fre", codec = "subrip"),
            ),
        )
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(
            preferredLanguage = "fr",
            mode = "auto",
            audioLanguage = "eng",
        )
        assertEquals("Auto: French · SRT", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoSkipsVobsubBitmapForTextTrack() {
        // "vobsub" is in Apple's bitmap token set but was missing here.
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(lang = "eng", codec = "vobsub"),
                subtitleTrack(lang = "eng", codec = "srt"),
            ),
        )
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(
            preferredLanguage = "en",
            mode = "auto",
            audioLanguage = "jpn",
        )
        assertEquals("Auto: English · SRT", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun resolvedAudioLanguage_returnsAutoTrackLanguage() {
        val v = fileVersion(
            audio = listOf(
                audioTrack(codec = "aac", lang = "jpn"),
                audioTrack(codec = "eac3", lang = "eng", default = true),
            ),
        )
        // Auto resolves to the default track → its language.
        assertEquals("eng", TvPlaybackFormatting.resolvedAudioLanguage(v, selectedAudioTrackIndex = null))
        assertEquals("jpn", TvPlaybackFormatting.resolvedAudioLanguage(v, selectedAudioTrackIndex = 0))
    }

    @Test fun subtitleValueLabel_languageForSelected() {
        // Selection is by ORDINAL: the single track sits at ordinal 0 even though
        // its stream index is 3.
        val v = fileVersion(subtitles = listOf(subtitleTrack(index = 3, lang = "eng")))
        assertEquals("English", TvPlaybackFormatting.subtitleValueLabel(v, selectedSubtitleTrackIndex = 0))
    }

    @Test fun subtitleValueLabel_forcedBadge() {
        val v = fileVersion(subtitles = listOf(subtitleTrack(index = 2, lang = "fre", forced = true)))
        assertEquals("French (Forced)", TvPlaybackFormatting.subtitleValueLabel(v, selectedSubtitleTrackIndex = 0))
    }

    @Test fun subtitleValueLabel_hearingImpairedBadge() {
        val v = fileVersion(subtitles = listOf(subtitleTrack(index = 1, lang = "eng", title = "English SDH")))
        assertEquals("English (SDH)", TvPlaybackFormatting.subtitleValueLabel(v, selectedSubtitleTrackIndex = 0))
    }

    @Test fun subtitleOptions_useFlatOrdinalNotStreamIndex() {
        // Stream indexes are non-ordinal and collide (external tracks decode 0).
        // selectionIndex must be the 0-based ordinal, with distinct stable ids,
        // and selection matches by ordinal.
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(index = 0, lang = "eng", external = true),
                subtitleTrack(index = 0, lang = "fre", external = true),
                subtitleTrack(index = 5, lang = "spa"),
            ),
        )
        val opts = TvPlaybackFormatting.subtitleOptions(v, selectedSubtitleTrackIndex = 1)
        assertEquals(listOf(0, 1, 2), opts.map { it.selectionIndex })
        assertEquals(3, opts.map { it.stableId }.toSet().size)
        assertFalse(opts[0].isSelected)
        assertTrue(opts[1].isSelected)
        assertFalse(opts[2].isSelected)
    }

    @Test fun subtitleOptions_carryForcedAndDefaultDetail() {
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(index = 0, lang = "eng", codec = "srt", forced = true, default = true),
            ),
        )
        val opts = TvPlaybackFormatting.subtitleOptions(v, selectedSubtitleTrackIndex = 0)
        assertEquals(1, opts.size)
        assertEquals(0, opts[0].selectionIndex)
        assertTrue(opts[0].isSelected)
        assertTrue(opts[0].detail.contains("Forced"))
        assertTrue(opts[0].detail.contains("Default"))
    }

    // --- editions (Android model has no edition data → single group) ---

    @Test fun editions_collapseToSingleGroup() {
        val versions = listOf(fileVersion(fileId = 1), fileVersion(fileId = 2))
        val editions = TvPlaybackFormatting.editions(versions)
        assertEquals(1, editions.size)
        assertEquals(2, editions[0].versions.size)
    }

    @Test fun editions_emptyWhenNoVersions() {
        assertTrue(TvPlaybackFormatting.editions(emptyList()).isEmpty())
    }

    // --- builders matching the real Android model constructors ---

    private fun fileVersion(
        fileId: Int = 1,
        resolution: String? = null,
        codecVideo: String? = null,
        codecAudio: String? = null,
        hdr: Boolean = false,
        container: String? = null,
        fileSize: Long = 0,
        audio: List<AudioTrack>? = null,
        video: List<VideoTrack>? = null,
        effectiveAudioIndex: Int? = null,
        subtitles: List<SubtitleTrack>? = null,
    ): FileVersion = FileVersion(
        fileId = fileId,
        resolution = resolution,
        codecVideo = codecVideo,
        codecAudio = codecAudio,
        hdr = hdr,
        container = container,
        fileSize = fileSize,
        audioTracks = audio,
        videoTracks = video,
        effectiveAudioTrackIndex = effectiveAudioIndex,
        subtitleTracks = subtitles,
    )

    private fun audioTrack(
        index: Int = 0,
        codec: String? = null,
        layout: String? = null,
        channels: Int? = null,
        lang: String? = null,
        title: String? = null,
        default: Boolean = false,
    ): AudioTrack = AudioTrack(
        index = index,
        codec = codec,
        channelLayout = layout,
        channels = channels,
        language = lang,
        title = title,
        isDefault = default,
    )

    private fun subtitleTrack(
        index: Int = 0,
        codec: String? = null,
        lang: String? = null,
        title: String? = null,
        forced: Boolean = false,
        default: Boolean = false,
        external: Boolean = false,
    ): SubtitleTrack = SubtitleTrack(
        index = index,
        codec = codec,
        language = lang,
        title = title,
        forced = forced,
        isDefault = default,
        external = external,
    )
}
