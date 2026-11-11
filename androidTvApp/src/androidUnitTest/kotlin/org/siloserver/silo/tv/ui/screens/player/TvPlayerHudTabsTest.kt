package org.siloserver.silo.tv.ui.screens.player

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
    fun emptyDataShowsSubtitlesSettingsAndInfo() {
        // Subtitles is always available (it hosts Search / AI-Translate / style
        // even with no current tracks); Audio/Chapters hide when empty.
        val tabs = visibleHudTabs(
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            chapters = emptyList(),
        )

        assertEquals(
            listOf(HudTab.Subtitles, HudTab.Video, HudTab.Info),
            tabs,
        )
    }

    @Test
    fun audioTabShowsWhenAudioTracksExist() {
        val tabs = visibleHudTabs(
            audioTracks = listOf(track(0)),
            subtitleTracks = emptyList(),
            chapters = emptyList(),
        )

        assertEquals(
            listOf(HudTab.Subtitles, HudTab.Audio, HudTab.Video, HudTab.Info),
            tabs,
        )
    }

    @Test
    fun subtitlesTabShowsWhenSubtitleTracksExist() {
        val tabs = visibleHudTabs(
            audioTracks = emptyList(),
            subtitleTracks = listOf(track(0)),
            chapters = emptyList(),
        )

        assertEquals(
            listOf(HudTab.Subtitles, HudTab.Video, HudTab.Info),
            tabs,
        )
    }

    @Test
    fun chaptersTabShowsWhenSelectedVersionHasChapters() {
        val tabs = visibleHudTabs(
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            chapters = listOf(
                VersionChapter(index = 0, title = "Opening", startSeconds = 0.0),
            ),
        )

        assertEquals(
            listOf(HudTab.Subtitles, HudTab.Chapters, HudTab.Video, HudTab.Info),
            tabs,
        )
    }

    @Test
    fun allTabsShowInStableHudOrderWhenEveryoneHasData() {
        val tabs = visibleHudTabs(
            audioTracks = listOf(track(0)),
            subtitleTracks = listOf(track(0)),
            chapters = listOf(
                VersionChapter(index = 0, title = "Opening", startSeconds = 0.0),
            ),
        )

        assertEquals(
            listOf(
                HudTab.Subtitles,
                HudTab.Audio,
                HudTab.Chapters,
                HudTab.Video,
                HudTab.Info,
            ),
            tabs,
        )
    }
}
