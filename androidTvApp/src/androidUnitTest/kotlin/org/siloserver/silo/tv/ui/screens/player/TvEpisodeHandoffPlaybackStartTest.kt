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
    fun nextUpCarriesTheChosenSubtitleNotTheFallbackThatPlayed() {
        val sdh = EpisodeSubtitleIntent(EpisodeSubtitleMode.TRACK, language = "en", hearingImpaired = true)
        val plainEnglish = EpisodeSubtitleIntent(EpisodeSubtitleMode.TRACK, language = "en", hearingImpaired = false)
        // E03 had only plain English, so the SDH choice carried in fell back.
        val playedOnE03 = EpisodeSelectionHandoff(subtitle = plainEnglish)
        val carriedIntoE03 = EpisodeSelectionHandoff(subtitle = sdh)

        assertEquals(sdh, carryEpisodeIntent(playedOnE03, carriedIntoE03, audioPicked = false, subtitlePicked = false).subtitle)
        // A pick made during E03 is the new choice.
        assertEquals(plainEnglish, carryEpisodeIntent(playedOnE03, carriedIntoE03, audioPicked = false, subtitlePicked = true).subtitle)
        // Nothing carried in: what played (an explicit choice) is carried.
        assertEquals(plainEnglish, carryEpisodeIntent(playedOnE03, null, audioPicked = false, subtitlePicked = false).subtitle)
    }

    @Test
    fun nextUpCarriesOnlyAudioTheViewerChose() {
        val japanese = org.siloserver.silo.common.player.video.EpisodeAudioIntent(
            org.siloserver.silo.common.player.video.EpisodeAudioMode.TRACK, language = "jpn",
        )
        val english = org.siloserver.silo.common.player.video.EpisodeAudioIntent(
            org.siloserver.silo.common.player.video.EpisodeAudioMode.TRACK, language = "eng",
        )
        // E03 has only English audio; an automatic commit made it look chosen.
        val playedOnE03 = EpisodeSelectionHandoff(audio = english)
        val auto = org.siloserver.silo.common.player.video.EpisodeAudioMode.AUTO

        assertEquals(auto, carryEpisodeIntent(playedOnE03, null, audioPicked = false, subtitlePicked = false).audio.mode)
        assertEquals(auto, carryEpisodeIntent(playedOnE03, EpisodeSelectionHandoff(), audioPicked = false, subtitlePicked = false).audio.mode)
        assertEquals(japanese, carryEpisodeIntent(playedOnE03, EpisodeSelectionHandoff(audio = japanese), audioPicked = false, subtitlePicked = false).audio)
        assertEquals(english, carryEpisodeIntent(playedOnE03, EpisodeSelectionHandoff(audio = japanese), audioPicked = true, subtitlePicked = false).audio)
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
    fun tvAudioPrecedenceIsManualThenCarryThenServerThenPerFileThenLanguage() {
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
        // The server's effective track (the series choice) beats this device's
        // per-file memory and language, never a pick or a carried choice.
        assertEquals(0, resolveTvInitialAudioTrackIndex(null, null, false, tracks, durable, "fr", capabilities, serverAudioTrackIndex = 0))
        assertEquals(2, resolveTvInitialAudioTrackIndex(null, 2, false, tracks, durable, "fr", capabilities, serverAudioTrackIndex = 0))
        assertEquals(1, resolveTvInitialAudioTrackIndex(1, null, false, tracks, durable, "fr", capabilities, serverAudioTrackIndex = 0))
        // An index the file does not have is no answer.
        assertEquals(1, resolveTvInitialAudioTrackIndex(null, null, false, tracks, durable, "fr", capabilities, serverAudioTrackIndex = 7))
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
