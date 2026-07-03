package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.model.catalog.VersionChapter
import kotlin.test.Test
import kotlin.test.assertEquals

class TvPlayerHudTabsTest {

    private fun track(index: Int) = PlayerTrackEntry(
        index = index,
        label = "Track $index",
        language = null,
        isSelected = false,
    )

    @Test
    fun emptyDataShowsInfoVideoAndSubtitles() {
        // Subtitles is always available (it hosts Search / AI-Translate / style
        // even with no current tracks); Stats/Audio/Chapters hide when empty.
        val tabs = visibleHudTabs(
            stats = PlayerStatsSnapshot(),
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            chapters = emptyList(),
        )

        assertEquals(
            listOf(HudTab.Info, HudTab.Video, HudTab.Subtitles),
            tabs,
        )
    }

    @Test
    fun statsTabShowsWhenStatsHaveRenderableData() {
        val tabs = visibleHudTabs(
            stats = PlayerStatsSnapshot(videoCodec = "video/avc"),
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            chapters = emptyList(),
        )

        assertEquals(
            listOf(HudTab.Info, HudTab.Stats, HudTab.Video, HudTab.Subtitles),
            tabs,
        )
    }

    @Test
    fun audioTabShowsWhenAudioTracksExist() {
        val tabs = visibleHudTabs(
            stats = PlayerStatsSnapshot(),
            audioTracks = listOf(track(0)),
            subtitleTracks = emptyList(),
            chapters = emptyList(),
        )

        assertEquals(
            listOf(HudTab.Info, HudTab.Video, HudTab.Audio, HudTab.Subtitles),
            tabs,
        )
    }

    @Test
    fun subtitlesTabShowsWhenSubtitleTracksExist() {
        val tabs = visibleHudTabs(
            stats = PlayerStatsSnapshot(),
            audioTracks = emptyList(),
            subtitleTracks = listOf(track(0)),
            chapters = emptyList(),
        )

        assertEquals(
            listOf(HudTab.Info, HudTab.Video, HudTab.Subtitles),
            tabs,
        )
    }

    @Test
    fun chaptersTabShowsWhenSelectedVersionHasChapters() {
        val tabs = visibleHudTabs(
            stats = PlayerStatsSnapshot(),
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            chapters = listOf(
                VersionChapter(index = 0, title = "Opening", startSeconds = 0.0),
            ),
        )

        assertEquals(
            listOf(HudTab.Info, HudTab.Video, HudTab.Subtitles, HudTab.Chapters),
            tabs,
        )
    }

    @Test
    fun allTabsShowInStableHudOrderWhenEveryoneHasData() {
        val tabs = visibleHudTabs(
            stats = PlayerStatsSnapshot(audioUnderruns = 1),
            audioTracks = listOf(track(0)),
            subtitleTracks = listOf(track(0)),
            chapters = listOf(
                VersionChapter(index = 0, title = "Opening", startSeconds = 0.0),
            ),
        )

        assertEquals(
            listOf(
                HudTab.Info,
                HudTab.Stats,
                HudTab.Video,
                HudTab.Audio,
                HudTab.Subtitles,
                HudTab.Chapters,
            ),
            tabs,
        )
    }
}
