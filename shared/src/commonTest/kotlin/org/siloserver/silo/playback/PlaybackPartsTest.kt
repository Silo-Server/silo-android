package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.FileVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PlaybackPartsTest {
    private fun part(id: Int, index: Int?, resolution: String = "1080p", group: String = "Film", edition: String? = null, kind: String = "multipart_movie") =
        FileVersion(
            fileId = id,
            resolution = resolution,
            presentationKind = kind,
            presentationGroupKey = group,
            presentationPartIndex = index,
            editionKey = edition,
        )

    @Test fun nextPartIsTheLowestLaterPartOfTheSamePresentation() {
        val versions = listOf(
            part(1, 1), part(2, 2), part(3, 3),
            part(4, 2, resolution = "720p"),
            part(5, 2, edition = "extended"),
            part(6, 2, group = "Other"),
        )
        assertEquals(2, nextPlaybackPartFileId(versions, 1))
        assertEquals(3, nextPlaybackPartFileId(versions, 2))
        assertNull(nextPlaybackPartFileId(versions, 3))
        // The current file's resolution wins when a part has several versions.
        assertEquals(4, nextPlaybackPartFileId(listOf(part(7, 1, resolution = "720p"), part(2, 2), part(4, 2, resolution = "720p")), 7))
    }

    @Test fun ordinaryFilesAndSplitEpisodesFollowTheirOwnRules() {
        val single = FileVersion(fileId = 9, resolution = "1080p")
        assertNull(nextPlaybackPartFileId(listOf(single), 9))
        assertNull(nextPlaybackPartFileId(listOf(single), null))
        val split = listOf(part(1, 1, kind = "split_episode"), part(2, 2, kind = "split_episode"))
        assertEquals(2, nextPlaybackPartFileId(split, 1))
        val audiobook = listOf(part(1, 1, kind = "audiobook_part"), part(2, 2, kind = "audiobook_part"))
        assertNull(nextPlaybackPartFileId(audiobook, 1))
    }

    @Test fun aStartFromTheBeginningOpensTheFirstPart() {
        val versions = listOf(part(2, 2), part(1, 1), part(3, 1, resolution = "720p"), part(4, 2, resolution = "720p"))
        assertEquals(1, firstPlaybackPart(versions, versions[0]).fileId)
        assertEquals(3, firstPlaybackPart(versions, versions[3]).fileId)
        assertSame(versions[1], firstPlaybackPart(versions, versions[1]))
        val single = FileVersion(fileId = 9, resolution = "1080p")
        assertSame(single, firstPlaybackPart(listOf(single), single))
    }
}
