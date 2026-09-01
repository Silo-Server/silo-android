package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.common.player.video.EpisodeSelectionHandoff
import org.siloserver.silo.common.player.video.EpisodeSourceIntent
import org.siloserver.silo.common.player.video.EpisodeSubtitleIntent
import org.siloserver.silo.common.player.video.EpisodeSubtitleMode
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.playback.audioTrackFingerprint

class TvEpisodeHandoffPlaybackStartTest {
    @Test
    fun manualTitleAudioChoiceWinsOverHandoffAndPlaybackSettings() {
        assertEquals(
            2,
            resolveTvStartAudioTrackIndex(
                requestedTitleTrackIndex = 2,
                episodeHandoffTrackIndex = 1,
                automaticPreferenceTrackIndex = 0,
            ),
        )
    }

    @Test
    fun playbackAudioSettingIsOnlyTheAutomaticFallback() {
        assertEquals(
            0,
            resolveTvStartAudioTrackIndex(
                requestedTitleTrackIndex = null,
                episodeHandoffTrackIndex = null,
                automaticPreferenceTrackIndex = 0,
            ),
        )
    }

    @Test
    fun episodeHandoffAudioWinsOverAutomaticPlaybackSetting() {
        assertEquals(
            1,
            resolveTvStartAudioTrackIndex(
                requestedTitleTrackIndex = null,
                episodeHandoffTrackIndex = 1,
                automaticPreferenceTrackIndex = 0,
            ),
        )
    }

    @Test
    fun explicitDetailFileIdWinsOverEpisodeHandoff() {
        val resolved = resolveTvPlaybackStartSelection(
            preferredFileId = 1080,
            episodeSelectionHandoff = handoff(sourceResolution = "2160p"),
            targetVersions = versions(),
            targetLastFileId = 2160,
            preferredQuality = "2160p",
        )

        assertEquals(1080, resolved.fileId)
    }

    @Test
    fun episodeHandoffWinsOverTargetLastFileAndQuality() {
        val resolved = resolveTvPlaybackStartSelection(
            preferredFileId = null,
            episodeSelectionHandoff = handoff(sourceResolution = "1080p"),
            targetVersions = versions(),
            targetLastFileId = 2160,
            preferredQuality = "2160p",
        )

        assertEquals(1080, resolved.fileId)
    }

    @Test
    fun noHandoffPreservesExistingLastFileAndQualitySelection() {
        val lastFileResolved = resolveTvPlaybackStartSelection(
            preferredFileId = null,
            episodeSelectionHandoff = null,
            targetVersions = versions(),
            targetLastFileId = 2160,
            preferredQuality = "1080p",
        )
        val qualityResolved = resolveTvPlaybackStartSelection(
            preferredFileId = null,
            episodeSelectionHandoff = null,
            targetVersions = versions(),
            targetLastFileId = null,
            preferredQuality = "1080p",
        )

        assertEquals(2160, lastFileResolved.fileId)
        assertEquals(1080, qualityResolved.fileId)
    }

    @Test
    fun subtitleIsResolvedAgainstTheChosenTargetVersion() {
        val resolved = resolveTvPlaybackStartSelection(
            preferredFileId = null,
            episodeSelectionHandoff = handoff(
                sourceResolution = "1080p",
                subtitle = EpisodeSubtitleIntent(
                    mode = EpisodeSubtitleMode.TRACK,
                    language = "nl",
                    codecFamily = "subrip",
                    external = false,
                ),
            ),
            targetVersions = versions(),
            targetLastFileId = 2160,
            preferredQuality = "2160p",
        )

        assertEquals(1080, resolved.fileId)
        assertEquals(1, resolved.subtitleTrackIndex)
        assertTrue(resolved.subtitleIntentSpecified)
    }

    @Test
    fun missingExplicitSubtitleReturnsSpecifiedProfileAuto() {
        val resolved = resolveTvPlaybackStartSelection(
            preferredFileId = 1080,
            episodeSelectionHandoff = handoff(
                subtitle = EpisodeSubtitleIntent(
                    mode = EpisodeSubtitleMode.TRACK,
                    language = "fr",
                ),
            ),
            targetVersions = versions(),
            targetLastFileId = null,
            preferredQuality = null,
        )

        assertNull(resolved.subtitleTrackIndex)
        assertTrue(resolved.subtitleIntentSpecified)
    }

    @Test
    fun explicitOffIsRetainedClientSide() {
        val handoff = handoff(subtitle = EpisodeSubtitleIntent.off())
        val resolved = resolveTvPlaybackStartSelection(
            preferredFileId = 1080,
            episodeSelectionHandoff = handoff,
            targetVersions = versions(),
            targetLastFileId = null,
            preferredQuality = null,
        )

        assertEquals(-1, resolved.subtitleTrackIndex)
        assertTrue(resolved.subtitleIntentSpecified)
        assertNull(
            resolveTvServerSubtitleTrackIndex(
                episodeSelectionHandoff = handoff,
                resolvedEpisodeSelection = resolved,
                requestedSubtitleTrackIndex = 4,
            ),
        )
    }

    @Test
    fun automaticHandoffDropsStaleRequestedSubtitleIndex() {
        val handoff = handoff()
        val resolved = resolveTvPlaybackStartSelection(
            preferredFileId = 1080,
            episodeSelectionHandoff = handoff,
            targetVersions = versions(),
            targetLastFileId = null,
            preferredQuality = null,
        )

        assertNull(
            resolveTvServerSubtitleTrackIndex(
                episodeSelectionHandoff = handoff,
                resolvedEpisodeSelection = resolved,
                requestedSubtitleTrackIndex = 4,
            ),
        )
    }

    @Test
    fun tvAudioPrecedenceIsManualThenCarryThenPerFileThenLanguage() {
        val tracks = listOf(
            AudioTrack(codec = "aac", language = "eng", title = "English"),
            AudioTrack(codec = "truehd", language = "jpn", title = "Japanese"),
            AudioTrack(codec = "eac3", language = "fra", title = "French"),
        )
        val durable = audioTrackFingerprint(tracks[1])
        val capabilities = ClientCodecCapabilities(codecsAudio = listOf("aac", "truehd", "eac3"))

        assertEquals(0, resolveTvInitialAudioTrackIndex(0, 2, false, tracks, durable, "fr", capabilities))
        assertEquals(2, resolveTvInitialAudioTrackIndex(null, 2, false, tracks, durable, "fr", capabilities))
        assertEquals(1, resolveTvInitialAudioTrackIndex(null, null, false, tracks, durable, "fr", capabilities))
        assertEquals(2, resolveTvInitialAudioTrackIndex(null, null, false, tracks, null, "fr", capabilities))
        assertNull(resolveTvInitialAudioTrackIndex(null, null, true, tracks, durable, "fr", capabilities))
    }

    @Test
    fun tvAutomaticAudioFallsBackFromUnsupportedDefaultToSupportedTrack() {
        val tracks = listOf(
            AudioTrack(codec = "truehd", language = "eng", isDefault = true),
            AudioTrack(codec = "aac", language = "eng"),
        )

        assertEquals(
            1,
            resolveTvInitialAudioTrackIndex(
                requestedAudioIndex = null,
                carriedAudioIndex = null,
                unresolvedCarriedChoice = false,
                tracks = tracks,
                durableAudioFingerprint = null,
                preferredAudioLanguage = "eng",
                capabilities = ClientCodecCapabilities(codecsAudio = listOf("aac")),
            ),
        )
    }

    private fun handoff(
        sourceResolution: String? = null,
        subtitle: EpisodeSubtitleIntent = EpisodeSubtitleIntent.auto(),
    ) = EpisodeSelectionHandoff(
        source = sourceResolution?.let(::EpisodeSourceIntent),
        subtitle = subtitle,
    )

    private fun versions() = listOf(
        FileVersion(
            fileId = 2160,
            resolution = "2160p",
            subtitleTracks = listOf(
                SubtitleTrack(index = 9, language = "nl", codec = "srt", external = true),
            ),
        ),
        FileVersion(
            fileId = 1080,
            resolution = "1080p",
            subtitleTracks = listOf(
                SubtitleTrack(index = 17, language = "en", codec = "srt", external = true),
                SubtitleTrack(index = 18, language = "nl", codec = "srt", external = false),
            ),
        ),
    )
}
