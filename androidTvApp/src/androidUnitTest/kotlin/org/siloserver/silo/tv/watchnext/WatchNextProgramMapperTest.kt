package org.siloserver.silo.tv.watchnext

import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchNextProgramMapperTest {
    @Test
    fun `protected episode uses explicit series artwork and never publishes the still`() {
        val episode = sectionItem(type = "episode").copy(backdropIsEpisodeStill = true, posterIsEpisodeStill = false)
        val prefs = EpisodeSpoilerPrefs(hideImages = true)
        assertEquals(episode.posterUrl, WatchNextProgramMapper.map(episode, "next_up", prefs)?.posterArtUri)
        assertEquals(WatchNextProgramMapper.ASPECT_RATIO_2_3,
            WatchNextProgramMapper.map(episode, "next_up", prefs)?.posterArtAspectRatio)
        val noSafeSlot = episode.copy(posterIsEpisodeStill = true)
        assertNull(WatchNextProgramMapper.map(noSafeSlot, "next_up", prefs))
        val series = ItemDetail(contentId = "series", type = "series", title = "Series", backdropUrl = "safe-series")
        assertEquals("safe-series", WatchNextProgramMapper.map(noSafeSlot, "next_up", prefs, series)?.posterArtUri)
        assertNull(WatchNextProgramMapper.map(noSafeSlot, "next_up", prefs, series.copy(type = "episode")))
    }

    @Test
    fun `legacy protected artwork is replaced but watched and in progress stills remain`() {
        val episode = sectionItem(type = "episode")
        val prefs = EpisodeSpoilerPrefs(hideImages = true)
        assertNull(WatchNextProgramMapper.map(episode, "next_up", prefs))
        assertEquals(episode.backdropUrl,
            WatchNextProgramMapper.map(episode.copy(userState = MediaItemUserState(played = true)), "next_up", prefs)?.posterArtUri)
        assertEquals(episode.backdropUrl,
            WatchNextProgramMapper.map(episode.copy(positionSeconds = 1.0), "continue_watching", prefs)?.posterArtUri)
    }

    private fun sectionItem(
        id: String = "tt1234",
        title: String = "Test Item",
        type: String = "movie",
        posterUrl: String? = "https://example.com/poster.jpg",
        backdropUrl: String? = "https://example.com/backdrop.jpg",
        progressUpdatedAt: String? = null,
    ) = SectionItem(
        contentId = id,
        title = title,
        type = type,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        progressUpdatedAt = progressUpdatedAt,
    )

    @Test
    fun `continue_watching section maps to CONTINUE type with play URI`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(id = "abc"),
            sectionType = "continue_watching",
        )
        assertEquals(WatchNextProgramMapper.WATCH_NEXT_TYPE_CONTINUE, fields?.watchNextType)
        assertEquals("silo://play/abc?type=movie", fields?.intentUri)
        assertEquals("continue_watching:abc", fields?.externalId)
    }

    @Test
    fun `next_up section maps to NEXT type with item URI`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(id = "xyz"),
            sectionType = "next_up",
        )
        assertEquals(WatchNextProgramMapper.WATCH_NEXT_TYPE_NEXT, fields?.watchNextType)
        assertEquals("silo://item/xyz", fields?.intentUri)
    }

    @Test
    fun `unknown sectionType returns null`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(),
            sectionType = "random_recommendations",
        )
        assertNull(fields)
    }

    @Test
    fun `prefers backdrop URL over poster URL`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(posterUrl = "P", backdropUrl = "B"),
            sectionType = "continue_watching",
        )
        assertEquals("B", fields?.posterArtUri)
    }

    @Test
    fun `falls back to poster when backdrop missing`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(posterUrl = "P", backdropUrl = null),
            sectionType = "continue_watching",
        )
        assertEquals("P", fields?.posterArtUri)
    }

    @Test
    fun `returns null when both poster and backdrop missing`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(posterUrl = null, backdropUrl = null),
            sectionType = "continue_watching",
        )
        assertNull(fields)
    }

    @Test
    fun `parses ISO-8601 progressUpdatedAt into lastEngagementTimeMs`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(progressUpdatedAt = "2026-01-01T00:00:00Z"),
            sectionType = "continue_watching",
        )
        // 2026-01-01T00:00:00Z = 1767225600000 ms
        assertEquals(1_767_225_600_000L, fields?.lastEngagementTimeMs)
    }

    @Test
    fun `leaves lastEngagementTimeMs null when progressUpdatedAt missing`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(progressUpdatedAt = null),
            sectionType = "continue_watching",
        )
        // Never re-stamped to "now" — the provider keeps any prior value.
        assertNull(fields?.lastEngagementTimeMs)
    }

    @Test
    fun `leaves lastEngagementTimeMs null when progressUpdatedAt unparseable`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(progressUpdatedAt = "not-a-date"),
            sectionType = "continue_watching",
        )
        assertNull(fields?.lastEngagementTimeMs)
    }

    @Test
    fun `movie type maps to PROGRAM_TYPE_MOVIE with 16-9 art`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(type = "movie"),
            sectionType = "continue_watching",
        )
        assertEquals(WatchNextProgramMapper.PROGRAM_TYPE_MOVIE, fields?.programType)
        assertEquals(WatchNextProgramMapper.ASPECT_RATIO_16_9, fields?.posterArtAspectRatio)
    }

    @Test
    fun `episode type maps to PROGRAM_TYPE_TV_EPISODE`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(type = "episode"),
            sectionType = "next_up",
        )
        assertEquals(WatchNextProgramMapper.PROGRAM_TYPE_TV_EPISODE, fields?.programType)
    }

    @Test
    fun `audiobook type maps to PROGRAM_TYPE_ALBUM with square art`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(type = "audiobook"),
            sectionType = "continue_watching",
        )
        assertEquals(WatchNextProgramMapper.PROGRAM_TYPE_ALBUM, fields?.programType)
        assertEquals(WatchNextProgramMapper.ASPECT_RATIO_1_1, fields?.posterArtAspectRatio)
    }

    @Test
    fun `play intent carries url-encoded item type for deep-link routing`() {
        val fields = WatchNextProgramMapper.map(
            sectionItem(id = "bk1", type = "audiobook"),
            sectionType = "continue_watching",
        )
        assertEquals("silo://play/bk1?type=audiobook", fields?.intentUri)
    }
}
