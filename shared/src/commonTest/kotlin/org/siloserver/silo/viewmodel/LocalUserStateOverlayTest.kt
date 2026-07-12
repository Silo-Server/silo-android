package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.repository.port.LocalPlaybackProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalUserStateOverlayTest {

    @Test
    fun `home overlay applies local progress only when local is ahead`() {
        val section = ResolvedSection(
            id = "cw",
            sectionType = "continue_watching",
            title = "Continue Watching",
            items = listOf(
                SectionItem(
                    contentId = "movie-local-ahead",
                    type = "movie",
                    title = "Local Ahead",
                    positionSeconds = 120.0,
                    durationSeconds = 1_000.0,
                ),
                SectionItem(
                    contentId = "movie-server-ahead",
                    type = "movie",
                    title = "Server Ahead",
                    positionSeconds = 500.0,
                    durationSeconds = 1_000.0,
                ),
            ),
        )

        val overlaid = applyLocalStateOverlay(
            sections = listOf(section),
            contentStates = emptyMap(),
            progressStates = mapOf(
                "movie-local-ahead" to LocalPlaybackProgress(
                    fileId = 77,
                    positionSeconds = 300.0,
                    durationSeconds = 1_200.0,
                ),
                "movie-server-ahead" to LocalPlaybackProgress(
                    fileId = 88,
                    positionSeconds = 250.0,
                    durationSeconds = 900.0,
                ),
            ),
        )

        val first = overlaid.single().items[0]
        assertEquals(300.0, first.positionSeconds)
        assertEquals(1_200.0, first.durationSeconds)

        val second = overlaid.single().items[1]
        assertEquals(500.0, second.positionSeconds)
        assertEquals(1_000.0, second.durationSeconds)
    }

    @Test
    fun `detail overlay carries local resume point and file id when ahead`() {
        val detail = ItemDetail(
            contentId = "episode-1",
            type = "episode",
            title = "Episode",
            userData = LeafItemUserData(
                positionSeconds = 90.0,
                durationSeconds = 1_000.0,
                lastFileId = 11,
            ),
        )

        val overlaid = applyLocalPlaybackProgress(
            detail,
            LocalPlaybackProgress(
                fileId = 22,
                positionSeconds = 450.0,
                durationSeconds = 1_100.0,
            ),
        )

        assertEquals(450.0, overlaid.userData?.positionSeconds)
        assertEquals(1_100.0, overlaid.userData?.durationSeconds)
        assertEquals(22, overlaid.userData?.lastFileId)
        assertEquals(true, overlaid.userData?.isInProgress)
    }

    @Test
    fun `detail overlay preserves watched status while applying rewatch progress`() {
        val detail = ItemDetail(
            contentId = "episode-watched",
            type = "episode",
            title = "Watched Episode",
            userData = LeafItemUserData(played = true),
        )

        val overlaid = applyLocalPlaybackProgress(
            detail,
            LocalPlaybackProgress(
                fileId = 22,
                positionSeconds = 450.0,
                durationSeconds = 1_100.0,
            ),
        )

        assertEquals(true, overlaid.userData?.played)
        assertEquals(450.0, overlaid.userData?.positionSeconds)
        assertEquals(true, overlaid.userData?.isInProgress)
    }

    @Test
    fun `episode overlay updates stale episode progress before sync`() {
        val episode = EpisodeListItem(
            contentId = "episode-1",
            seasonNumber = 1,
            episodeNumber = 2,
            userData = LeafItemUserData(
                positionSeconds = 40.0,
                durationSeconds = 1_000.0,
                lastFileId = 11,
            ),
        )

        val overlaid = applyLocalPlaybackProgress(
            episode,
            LocalPlaybackProgress(
                fileId = 22,
                positionSeconds = 600.0,
                durationSeconds = 1_200.0,
            ),
        )

        assertEquals(600.0, overlaid.userData?.positionSeconds)
        assertEquals(1_200.0, overlaid.userData?.durationSeconds)
        assertEquals(22, overlaid.userData?.lastFileId)
        assertEquals(true, overlaid.userData?.isInProgress)
    }
}
