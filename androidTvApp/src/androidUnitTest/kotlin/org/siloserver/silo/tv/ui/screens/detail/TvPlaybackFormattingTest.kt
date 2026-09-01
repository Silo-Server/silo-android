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

    @Test fun autoModeChecksOnlyAutoRow() {
        assertTrue(isAudioSelectorOptionSelected(null, null))
        assertFalse(isAudioSelectorOptionSelected(1, null))
    }

    @Test fun explicitModeChecksOnlyPhysicalTrack() {
        assertFalse(isAudioSelectorOptionSelected(null, 1))
        assertTrue(isAudioSelectorOptionSelected(1, 1))
        assertFalse(isAudioSelectorOptionSelected(0, 1))
    }

    @Test fun selectorNeedsMoreThanOneRealChoice() {
        assertFalse(selectorIsInteractive(0))
        assertFalse(selectorIsInteractive(1))
        assertTrue(selectorIsInteractive(2))
    }

    /**
     * The rule counts REAL choices, so the pseudo-entries the menus prepend do
     * not make a single-track file interactive. A lone subtitle track assembles
     * three menu rows (Auto · Off · the track) but is still one choice, and the
     * old enabled-row count read that as a dropdown worth opening — the bug this
     * replaced. Apple applies the same `shouldEnableSubtitleSelector` rule.
     */
    @Test fun pseudoEntriesDoNotMakeASingleTrackInteractive() {
        val subtitleTracksOnAOneTrackFile = 1

        assertFalse(selectorIsInteractive(subtitleTracksOnAOneTrackFile))
    }

    @Test fun automaticNoTrackCopyMatchesTvOs() {
        assertEquals("Auto - None", automaticTrackLabel(null))
        assertEquals("Auto - English", automaticTrackLabel("English"))
    }

    // --- catalog audio, keyed by ordinal ---

    /**
     * Audio is addressed by ORDINAL. The server sends no `index` for audio
     * tracks (subtitles get one), so [AudioTrack.index] is its `0` default on
     * every row: keying on it collapsed both tracks of a two-track file onto
     * the first, and the picker rendered the Dutch track with the English label.
     */
    @Test fun audioSummaryForOrdinal_distinguishesTracksThatShareTheDefaultIndex() {
        val version = fileVersion(
            audio = listOf(
                AudioTrack(language = "eng", codec = "dts", channels = 6),
                AudioTrack(language = "nld", codec = "aac", channels = 2),
            ),
        )
        assertEquals(0, version.audioTracks!![0].index, "the wire carries no audio index")
        assertEquals(0, version.audioTracks!![1].index)

        val first = TvPlaybackFormatting.audioSummaryForOrdinal(version, 0)
        val second = TvPlaybackFormatting.audioSummaryForOrdinal(version, 1)

        assertTrue(first != null && first.contains("English"), "got $first")
        assertTrue(second != null && second.contains("Dutch"), "got $second")
        assertTrue(first != second, "identical indices must not collapse the rows")
    }

    @Test fun audioSummaryForOrdinal_nullWhenUnresolvable() {
        val version = fileVersion(audio = listOf(AudioTrack(language = "eng")))
        assertEquals(null, TvPlaybackFormatting.audioSummaryForOrdinal(version, null))
        assertEquals(null, TvPlaybackFormatting.audioSummaryForOrdinal(version, 1))
        assertEquals(null, TvPlaybackFormatting.audioSummaryForOrdinal(null, 0))
        assertEquals(null, TvPlaybackFormatting.audioSummaryForOrdinal(fileVersion(), 0))
    }

    @Test fun effectiveAudioOrdinal_prefersPlanThenServerEffectiveThenDefault() {
        val tracks = listOf(
            AudioTrack(language = "eng"),
            AudioTrack(language = "nld", isDefault = true),
        )

        assertEquals(0, TvPlaybackFormatting.effectiveAudioOrdinal(tracks, planOrdinal = 0))
        // Out of range must not be echoed back.
        assertEquals(1, TvPlaybackFormatting.effectiveAudioOrdinal(tracks, planOrdinal = 9))
        assertEquals(
            0,
            TvPlaybackFormatting.effectiveAudioOrdinal(
                tracks,
                planOrdinal = null,
                version = fileVersion(audio = tracks, effectiveAudioIndex = 0),
            ),
            "the server's effective ordinal outranks the default flag",
        )
        assertEquals(1, TvPlaybackFormatting.effectiveAudioOrdinal(tracks, planOrdinal = null))
        assertEquals(null, TvPlaybackFormatting.effectiveAudioOrdinal(emptyList(), 0))
    }

    /** Title is often all that separates two otherwise identical mixes. */
    @Test fun audioChoiceLabelForOrdinal_keepsTitleAndDefault() {
        val tracks = listOf(
            AudioTrack(language = "eng", codec = "aac", channels = 2, title = "Main", isDefault = true),
            AudioTrack(language = "eng", codec = "aac", channels = 2, title = "Director Commentary"),
        )

        val main = TvPlaybackFormatting.audioChoiceLabelForOrdinal(tracks, 0)
        val commentary = TvPlaybackFormatting.audioChoiceLabelForOrdinal(tracks, 1)

        assertTrue(main != commentary, "identical summaries must stay distinguishable: $main / $commentary")
        assertTrue(commentary!!.contains("Director Commentary"), "got $commentary")
        assertTrue(main!!.contains("Default"), "got $main")
        assertEquals(null, TvPlaybackFormatting.audioChoiceLabelForOrdinal(tracks, 2))
    }

    // --- versionPickerLabels ---

    @Test fun versionPickerLabels_leaveDistinctLabelsAlone() {
        val versions = listOf(
            fileVersion(fileId = 1, resolution = "1080p"),
            fileVersion(fileId = 2, resolution = "2160p", hdr = true),
        )
        assertEquals(listOf("1080P", "4K · HDR"), TvPlaybackFormatting.versionPickerLabels(versions))
    }

    @Test fun versionPickerLabels_carryCodecsLikeTvOs() {
        // Two files that differ only by codec are told apart by the base label
        // itself (resolution · video codec · DR · audio codec, as on tvOS).
        val versions = listOf(
            fileVersion(fileId = 1, resolution = "2160p", codecVideo = "hevc", codecAudio = "truehd", hdr = true),
            fileVersion(fileId = 2, resolution = "2160p", codecVideo = "av1", codecAudio = "eac3", hdr = true),
        )
        assertEquals(
            listOf("4K · HEVC · HDR · TrueHD", "4K · AV1 · HDR · EAC3"),
            TvPlaybackFormatting.versionPickerLabels(versions),
        )
    }

    @Test fun versionPickerLabels_disambiguateCollidingLabelsBySize() {
        // The device case: one title, two 4K DV files, two identical rows.
        val dv = listOf(VideoTrack(codec = "hevc", dolbyVision = "Profile 7"))
        val versions = listOf(
            fileVersion(fileId = 1, resolution = "1080p"),
            fileVersion(fileId = 2, resolution = "2160p", hdr = true, video = dv, fileSize = 62_000_000_000),
            fileVersion(fileId = 3, resolution = "2160p", hdr = true, video = dv, fileSize = 18_000_000_000),
        )

        val labels = TvPlaybackFormatting.versionPickerLabels(versions)

        assertEquals("1080P", labels[0])
        assertEquals(labels.distinct().size, labels.size, "colliding rows must be distinguishable")
        assertTrue(labels[1].startsWith("4K · HEVC · DV · "), "got ${labels[1]}")
        assertTrue(labels[2].startsWith("4K · HEVC · DV · "), "got ${labels[2]}")
    }

    @Test fun versionPickerLabels_fallBackToContainerWhenSizesMatch() {
        val dv = listOf(VideoTrack(codec = "hevc", dolbyVision = "Profile 7"))
        val versions = listOf(
            fileVersion(fileId = 1, resolution = "2160p", hdr = true, video = dv, container = "mkv", fileSize = 42),
            fileVersion(fileId = 2, resolution = "2160p", hdr = true, video = dv, container = "mp4", fileSize = 42),
        )

        val labels = TvPlaybackFormatting.versionPickerLabels(versions)

        assertEquals(labels.distinct().size, labels.size)
        assertTrue(labels.any { it.endsWith("MP4") }, "got $labels")
    }

    /**
     * Every codec and every size is shared here — the codec pairs collide on
     * the base label and the sizes collide within each pair — so the size
     * suffix must be applied per colliding group rather than given up on.
     */
    @Test fun versionPickerLabels_widenUntilTheGroupIsActuallySeparated() {
        val dv = listOf(VideoTrack(codec = "hevc", dolbyVision = "Profile 7"))
        fun v(id: Int, codec: String, size: Long) =
            fileVersion(fileId = id, resolution = "2160p", hdr = true, video = dv, codecVideo = codec, fileSize = size)
        val versions = listOf(
            v(1, "hevc", 20_000_000_000),
            v(2, "av1", 20_000_000_000),
            v(3, "hevc", 40_000_000_000),
            v(4, "av1", 40_000_000_000),
        )

        val labels = TvPlaybackFormatting.versionPickerLabels(versions)

        assertEquals(4, labels.distinct().size, "every version must be distinguishable; got $labels")
        assertTrue(labels.all { it.startsWith("4K · HEVC · DV · ") || it.startsWith("4K · AV1 · DV · ") }, "got $labels")
    }

    @Test fun versionPickerLabels_indistinguishableVersionsStayEqual() {
        // Nothing to say them apart with: better a duplicate label than a
        // fabricated difference. Selection still works on fileId.
        val dv = listOf(VideoTrack(codec = "hevc", dolbyVision = "Profile 7"))
        val versions = listOf(
            fileVersion(fileId = 1, resolution = "2160p", hdr = true, video = dv),
            fileVersion(fileId = 2, resolution = "2160p", hdr = true, video = dv),
        )
        assertEquals(listOf("4K · HEVC · DV", "4K · HEVC · DV"), TvPlaybackFormatting.versionPickerLabels(versions))
    }

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
        assertEquals("4K · HEVC · DV", TvPlaybackFormatting.versionShortLabel(v))
    }

    @Test fun versionShortLabel_includesAudioCodecLikeTvOs() {
        val v = fileVersion(
            resolution = "2160p",
            codecVideo = "hevc",
            hdr = true,
            video = listOf(VideoTrack(codec = "hevc", dolbyVision = "Profile 8")),
            audio = listOf(
                audioTrack(codec = "aac", channels = 2),
                audioTrack(codec = "truehd", layout = "7.1", default = true),
            ),
        )
        // Audio codec is the Auto-resolved (default) track's, not the first.
        assertEquals("4K · HEVC · DV · TrueHD", TvPlaybackFormatting.versionShortLabel(v))
    }

    @Test fun versionShortLabel_fallsBackToVersionAudioCodec() {
        val v = fileVersion(resolution = "1080p", codecVideo = "h264", codecAudio = "eac3")
        assertEquals("1080P · H.264 · EAC3", TvPlaybackFormatting.versionShortLabel(v))
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

    @Test fun versionCompactLabel_keepsResolutionAndDynamicRangeOnly() {
        val v = fileVersion(
            resolution = "2160p",
            codecVideo = "hevc",
            codecAudio = "truehd",
            hdr = true,
        )

        assertEquals("4K · HDR", TvPlaybackFormatting.versionCompactLabel(v))
    }

    @Test fun compactSubtitleSelectorValue_dropsAutoAndTechnicalSuffixes() {
        assertEquals("English", compactSubtitleSelectorValue("Auto - English · SRT"))
        assertEquals("Off", compactSubtitleSelectorValue("Off"))
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

    @Test fun audioOptions_noPhysicalTrackSelectedWhenAuto() {
        val v = fileVersion(
            audio = listOf(
                audioTrack(codec = "aac", lang = "eng"),
                audioTrack(codec = "eac3", lang = "fre", default = true),
            ),
        )
        val opts = TvPlaybackFormatting.audioOptions(v, selectedAudioTrackIndex = null)
        assertFalse(opts[0].isSelected)
        assertFalse(opts[1].isSelected)
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

    @Test fun audioOptions_effectiveIndexDoesNotDuplicateAutoSelection() {
        val v = fileVersion(
            audio = listOf(
                audioTrack(codec = "aac", lang = "eng", default = true),
                audioTrack(codec = "eac3", lang = "fre"),
            ),
            effectiveAudioIndex = 1,
        )
        val opts = TvPlaybackFormatting.audioOptions(v, selectedAudioTrackIndex = null)
        assertFalse(opts[0].isSelected)
        assertFalse(opts[1].isSelected)
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
        assertEquals("Auto - French", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoNoneWhenAudioMatchesPreferred() {
        // auto mode hides subs when audio is already in the preferred language.
        val v = fileVersion(subtitles = listOf(subtitleTrack(lang = "eng")))
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(
            preferredLanguage = "en",
            mode = "auto",
            audioLanguage = "eng",
        )
        assertEquals("Auto - None", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
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
        assertEquals("Auto - English (Forced)", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoNoneWhenModeOff() {
        val v = fileVersion(subtitles = listOf(subtitleTrack(lang = "eng")))
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(preferredLanguage = "en", mode = "off")
        assertEquals("Auto - None", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoNoneWhenPreferenceIsNoSubs() {
        // Empty (not null) preferred language means "no subs" at this level.
        val v = fileVersion(subtitles = listOf(subtitleTrack(lang = "eng")))
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(preferredLanguage = "", mode = "auto")
        assertEquals("Auto - None", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
    }

    @Test fun subtitleValueLabel_autoNoneWhenNoPreferenceAndModeAuto() {
        // No language preference + plain auto → resolver leaves subs off.
        val v = fileVersion(subtitles = listOf(subtitleTrack(lang = "eng")))
        val ctx = TvPlaybackFormatting.SubtitleAutoContext(preferredLanguage = null, mode = "auto")
        assertEquals("Auto - None", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
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
        assertEquals("Auto - French", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
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
        assertEquals("Auto - English", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
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
        assertEquals("Auto - French · SRT", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
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
        assertEquals("Auto - English · SRT", TvPlaybackFormatting.subtitleValueLabel(v, null, ctx))
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

    @Test fun subtitleValueLabel_hindiCodeDoesNotAddHearingImpairedBadge() {
        val v = fileVersion(subtitles = listOf(subtitleTrack(index = 1, lang = "eng", title = "EN - HI")))
        assertEquals("English", TvPlaybackFormatting.subtitleValueLabel(v, selectedSubtitleTrackIndex = 0))
    }

    @Test fun subtitleOptions_useCombinedSpaceNotStreamIndex() {
        // Stream indexes are non-ordinal and collide (external tracks decode 0).
        // selectionIndex must be the COMBINED index the server resolves
        // (externals first, embedded after), with distinct stable ids, and
        // selection matches in the same space.
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

    @Test fun subtitleOptions_embeddedFirstCatalogStillEmitsCombinedIndexes() {
        // Catalog order is embedded-first (the server lists embedded tracks
        // before external sidecars), but the selection space is externals-first.
        // A Bluray with embedded da/en plus da/sv sidecars: picking the
        // embedded English row must target combined index 3, not position 1 —
        // position 1 is the Swedish sidecar and selecting it used to start
        // playback with the wrong track entirely.
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(index = 2, lang = "dan"),
                subtitleTrack(index = 3, lang = "eng"),
                subtitleTrack(index = 0, lang = "dan", external = true),
                subtitleTrack(index = 0, lang = "swe", external = true),
            ),
        )
        val opts = TvPlaybackFormatting.subtitleOptions(v, selectedSubtitleTrackIndex = 3)
        assertEquals(listOf(2, 0, 3, 1), opts.map { it.selectionIndex })
        assertTrue(opts[2].isSelected) // embedded English row carries combined 3
        assertEquals(
            "English",
            TvPlaybackFormatting.subtitleValueLabel(v, selectedSubtitleTrackIndex = 3),
        )
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

    @Test fun subtitleOptions_neverExposeShowOrReleaseFilename() {
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(
                    index = 0,
                    lang = "dan",
                    codec = "srt",
                    title = "Reasonable Doubt (2014) [Bluray-1080p x264]-GROUP.da.srt",
                    external = true,
                ),
            ),
        )

        val option = TvPlaybackFormatting.subtitleOptions(v, selectedSubtitleTrackIndex = null).single()
        assertEquals("Danish", option.title)
        assertEquals("SRT · External", option.detail)
    }

    @Test fun subtitleOptions_orderSemanticallyWithoutRenumberingCombinedIndexes() {
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(index = 10, lang = "jpn", codec = "srt"),
                subtitleTrack(index = 11, lang = "eng", codec = "srt", forced = true),
                subtitleTrack(index = 0, lang = "fre", codec = "srt", external = true),
                subtitleTrack(index = 12, lang = "eng", codec = "pgs"),
                subtitleTrack(index = 13, lang = "eng", codec = "srt", title = "English SDH"),
                subtitleTrack(index = 14, lang = "eng", codec = "ass"),
                subtitleTrack(index = 15, lang = "eng", codec = "srt"),
            ),
        )

        val opts = TvPlaybackFormatting.subtitleOptions(
            v,
            selectedSubtitleTrackIndex = 6,
            preferredLanguage = "en",
        )

        assertEquals(listOf(6, 2, 4, 5, 3, 0, 1), opts.map { it.selectionIndex })
        assertTrue(opts.first().isSelected)
        assertEquals("French", opts[5].title)
        assertEquals("Japanese", opts[6].title)
    }

    @Test fun subtitleOptions_matchTvOsFormatBeforeVariantPrecedence() {
        val v = fileVersion(
            subtitles = listOf(
                subtitleTrack(index = 10, lang = "eng", codec = "ass"),
                subtitleTrack(index = 11, lang = "eng", codec = "srt", forced = true),
                subtitleTrack(index = 12, lang = "eng", codec = "pgs"),
            ),
        )

        val opts = TvPlaybackFormatting.subtitleOptions(
            v,
            selectedSubtitleTrackIndex = null,
            preferredLanguage = "en",
        )

        // Canonical SubtitleDisplayOrder.swift ranks SRT before ASS before PGS,
        // then applies full/forced/SDH only inside a format rank.
        assertEquals(listOf(1, 0, 2), opts.map { it.selectionIndex })
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
