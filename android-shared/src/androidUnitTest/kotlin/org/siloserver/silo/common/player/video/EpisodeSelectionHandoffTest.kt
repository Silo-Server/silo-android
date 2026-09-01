package org.siloserver.silo.common.player.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.VideoTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo

class EpisodeSelectionHandoffTest {
    @Test
    fun versionSwitchMapsManualAudioByIdentityInsteadOfOrdinal() {
        val source = listOf(
            AudioTrack(codec = "aac", channels = 2, language = "eng", title = "English"),
            AudioTrack(codec = "truehd", channels = 8, language = "jpn", title = "Japanese Atmos"),
        )
        val target = listOf(
            AudioTrack(codec = "truehd", channels = 8, language = "jpn", title = "Japanese Atmos"),
            AudioTrack(codec = "aac", channels = 2, language = "eng", title = "English"),
        )

        assertEquals(0, resolveAudioSelectionAcrossVersions(source, 1, target))
        assertNull(resolveAudioSelectionAcrossVersions(source, null, target))
    }

    @Test
    fun sourceUsesResolutionBeforeCodecAndContainer() {
        val source = version(
            fileId = 101,
            resolution = "2160p",
            codec = "hevc",
            hdr = true,
            container = "mkv",
        )
        val targets = listOf(
            version(201, "1080p", "hevc", hdr = true, container = "mkv"),
            version(202, "2160p", "h264", hdr = false, container = "mp4"),
        )

        assertEquals(
            202,
            resolveEpisodeSourceIntent(captureEpisodeSourceIntent(source), targets),
        )
    }

    @Test
    fun sourceUsesCodecDynamicRangeAndContainerAsTieBreakers() {
        val source = version(
            fileId = 101,
            resolution = "2160p",
            codec = "hevc",
            hdr = true,
            container = "mkv",
            hdrFormat = "Dolby Vision",
        )
        val targets = listOf(
            version(201, "2160p", "hevc", hdr = true, container = "mp4", hdrFormat = "Dolby Vision"),
            version(202, "2160p", "hevc", hdr = true, container = "mkv", hdrFormat = "Dolby Vision"),
            version(203, "2160p", "h264", hdr = true, container = "mkv", hdrFormat = "HDR10"),
        )

        assertEquals(
            202,
            resolveEpisodeSourceIntent(captureEpisodeSourceIntent(source), targets),
        )
    }

    @Test
    fun sourceUsesCodecBeforeDynamicRangeAndContainerWhenCriteriaConflict() {
        val source = version(
            fileId = 101,
            resolution = "2160p",
            codec = "hevc",
            hdr = true,
            container = "mkv",
            hdrFormat = "Dolby Vision",
        )
        val targets = listOf(
            version(201, "2160p", "h264", hdr = true, container = "mkv", hdrFormat = "Dolby Vision"),
            version(202, "2160p", "hevc", hdr = false, container = "mp4"),
        )

        assertEquals(
            202,
            resolveEpisodeSourceIntent(captureEpisodeSourceIntent(source), targets),
        )
    }

    @Test
    fun ambiguousBestSourceFallsBackToAutomaticSelection() {
        val source = version(101, "2160p", "hevc", hdr = true, container = "mkv")
        val targets = listOf(
            version(201, "2160p", "hevc", hdr = true, container = "mkv"),
            version(202, "2160p", "hevc", hdr = true, container = "mkv"),
        )

        assertNull(resolveEpisodeSourceIntent(captureEpisodeSourceIntent(source), targets))
    }

    @Test
    fun unavailableResolutionFallsBackToAutomaticSelection() {
        val source = version(101, "2160p", "hevc", hdr = true, container = "mkv")
        val targets = listOf(
            version(201, "1080p", "hevc", hdr = true, container = "mkv"),
            version(202, "720p", "hevc", hdr = true, container = "mkv"),
        )

        assertNull(resolveEpisodeSourceIntent(captureEpisodeSourceIntent(source), targets))
    }

    @Test
    fun sourceIntentNeverSerializesTheOriginalFileId() {
        val handoff = EpisodeSelectionHandoff(
            source = captureEpisodeSourceIntent(
                version(4242, "2160p", "hevc", hdr = true, container = "mkv").copy(
                    fileName = "source-file-name.mkv",
                    filePath = "/private/source-file-path.mkv",
                ),
            ),
            subtitle = captureEpisodeSubtitleIntent(
                selectedTrackIndex = 31,
                subtitles = listOf(
                    PlayerSubtitleInfo(
                        index = 31,
                        language = "en",
                        codec = "srt",
                        label = "English",
                        source = "downloaded",
                        url = "/private/source-subtitle.srt",
                        downloadId = 3131,
                        mediaTrackId = "source-media-track-id",
                    ),
                ),
            ),
        )

        val encoded = encodeEpisodeSelectionHandoff(handoff)

        assertFalse(encoded.contains("4242"))
        assertFalse(encoded.contains("31"))
        assertFalse(encoded.contains("3131"))
        assertFalse(encoded.contains("source-file-name"))
        assertFalse(encoded.contains("source-file-path"))
        assertFalse(encoded.contains("source-subtitle"))
        assertFalse(encoded.contains("source-media-track-id"))
        assertTrue(encoded.contains("2160p"))
    }

    @Test
    fun explicitSubtitleMatchesSemanticsAtADifferentTargetIndex() {
        val intent = captureEpisodeSubtitleIntent(
            selectedTrackIndex = 7,
            subtitles = listOf(
                subtitle(
                    index = 7,
                    language = "eng",
                    codec = "application/x-subrip",
                    label = "English SDH",
                    source = "external",
                    forced = false,
                ),
            ),
        )
        val resolved = resolveEpisodeSubtitleIntent(
            intent = intent,
            targetSubtitles = listOf(
                subtitle(2, "en", "srt", "English SDH", "external", forced = false),
                subtitle(9, "en", "srt", "English SDH", "embedded", forced = false),
            ),
        )

        assertEquals(2, resolved.trackIndex)
        assertTrue(resolved.intentSpecified)
    }

    @Test
    fun explicitSubtitleKeepsLanguageAndAccessibilityWhenFormatAndSourceChange() {
        val intent = captureEpisodeSubtitleIntent(
            selectedTrackIndex = 7,
            subtitles = listOf(
                subtitle(7, "en", "srt", "English SDH", "external", forced = false),
            ),
        )

        val resolved = resolveEpisodeSubtitleIntent(
            intent,
            targetSubtitles = listOf(
                subtitle(12, "eng", "webvtt", "English SDH", "embedded", forced = false),
            ),
        )

        assertEquals(12, resolved.trackIndex)
        assertTrue(resolved.intentSpecified)
    }

    @Test
    fun subtitleLanguageOutranksFormatAndSourceKind() {
        val intent = captureEpisodeSubtitleIntent(
            selectedTrackIndex = 7,
            subtitles = listOf(
                subtitle(7, "en", "srt", "English", "external", forced = false),
            ),
        )

        val resolved = resolveEpisodeSubtitleIntent(
            intent,
            targetSubtitles = listOf(
                subtitle(2, "fr", "srt", "French", "external", forced = false),
                subtitle(9, "eng", "webvtt", "English", "embedded", forced = false),
            ),
        )

        assertEquals(9, resolved.trackIndex)
    }

    @Test
    fun subtitleAccessibilityOutranksFormatAndSourceKind() {
        val intent = captureEpisodeSubtitleIntent(
            selectedTrackIndex = 7,
            subtitles = listOf(
                subtitle(7, "en", "srt", "English", "external", forced = false),
            ),
        )

        val resolved = resolveEpisodeSubtitleIntent(
            intent,
            targetSubtitles = listOf(
                subtitle(2, "en", "srt", "English SDH", "external", forced = false),
                subtitle(9, "eng", "webvtt", "English", "embedded", forced = false),
            ),
        )

        assertEquals(9, resolved.trackIndex)
    }

    @Test
    fun ambiguousBestSubtitleUsesProfileAuto() {
        val intent = captureEpisodeSubtitleIntent(
            selectedTrackIndex = 7,
            subtitles = listOf(
                subtitle(7, "en", "srt", "English", "external", forced = false),
            ),
        )

        val resolved = resolveEpisodeSubtitleIntent(
            intent,
            targetSubtitles = listOf(
                subtitle(2, "eng", "srt", "English", "external", forced = false),
                subtitle(9, "en", "srt", "English", "external", forced = false),
            ),
        )

        assertNull(resolved.trackIndex)
        assertTrue(resolved.intentSpecified)
    }

    @Test
    fun underSpecifiedTrackIntentUsesProfileAuto() {
        val resolved = resolveEpisodeSubtitleIntent(
            EpisodeSubtitleIntent(mode = EpisodeSubtitleMode.TRACK),
            targetSubtitles = listOf(
                subtitle(2, "en", "srt", "English", "external", forced = false),
            ),
        )

        assertNull(resolved.trackIndex)
        assertTrue(resolved.intentSpecified)
    }

    @Test
    fun explicitNonSdhSubtitleDoesNotResolveToAnSdhTarget() {
        val intent = captureEpisodeSubtitleIntent(
            selectedTrackIndex = 7,
            subtitles = listOf(
                subtitle(7, "en", "srt", "English", "external", forced = false),
            ),
        )

        val resolved = resolveEpisodeSubtitleIntent(
            intent,
            targetSubtitles = listOf(
                subtitle(2, "en", "srt", "English", "external", forced = false),
                subtitle(9, "en", "srt", "English SDH", "external", forced = false),
            ),
        )

        assertEquals(2, resolved.trackIndex)
        assertTrue(resolved.intentSpecified)
    }

    @Test
    fun explicitOffRemainsOff() {
        val resolved = resolveEpisodeSubtitleIntent(
            EpisodeSubtitleIntent.off(),
            targetSubtitles = emptyList(),
        )

        assertEquals(-1, resolved.trackIndex)
        assertTrue(resolved.intentSpecified)
    }

    @Test
    fun automaticSubtitleRemainsUnspecified() {
        val resolved = resolveEpisodeSubtitleIntent(
            EpisodeSubtitleIntent.auto(),
            targetSubtitles = emptyList(),
        )

        assertNull(resolved.trackIndex)
        assertFalse(resolved.intentSpecified)
    }

    @Test
    fun unavailableExplicitSubtitleUsesProfileAutoWithoutTargetDurableRestore() {
        val resolved = resolveEpisodeSubtitleIntent(
            EpisodeSubtitleIntent(
                mode = EpisodeSubtitleMode.TRACK,
                language = "nl",
                codecFamily = "subrip",
                external = true,
            ),
            targetSubtitles = listOf(
                subtitle(4, "en", "srt", "English", "external", forced = false),
            ),
        )

        assertNull(resolved.trackIndex)
        assertTrue(resolved.intentSpecified)
    }

    @Test
    fun malformedPayloadDecodesToNull() {
        assertNull(decodeEpisodeSelectionHandoff("{not-json"))
    }

    @Test
    fun encodedPayloadRoundTripsSemanticIntentAndRestoresAutoDefault() {
        val handoff = EpisodeSelectionHandoff(
            source = captureEpisodeSourceIntent(
                version(101, "2160p", "hevc", hdr = true, container = "mkv"),
            ),
            subtitle = EpisodeSubtitleIntent(
                mode = EpisodeSubtitleMode.TRACK,
                language = "en",
                codecFamily = "subrip",
                external = true,
            ),
        )

        assertEquals(handoff, decodeEpisodeSelectionHandoff(encodeEpisodeSelectionHandoff(handoff)))
        assertEquals(EpisodeSubtitleIntent.auto(), decodeEpisodeSelectionHandoff("{}")?.subtitle)
    }

    private fun version(
        fileId: Int,
        resolution: String,
        codec: String,
        hdr: Boolean,
        container: String,
        hdrFormat: String? = null,
    ) = FileVersion(
        fileId = fileId,
        resolution = resolution,
        codecVideo = codec,
        hdr = hdr,
        container = container,
        videoTracks = listOf(
            VideoTrack(
                codec = codec,
                hdr = hdr,
                hdrFormat = hdrFormat,
            ),
        ),
    )

    private fun subtitle(
        index: Int,
        language: String,
        codec: String,
        label: String,
        source: String,
        forced: Boolean,
    ) = PlayerSubtitleInfo(
        index = index,
        language = language,
        codec = codec,
        label = label,
        source = source,
        forced = forced,
        url = "/subtitles/$index",
    )
}
