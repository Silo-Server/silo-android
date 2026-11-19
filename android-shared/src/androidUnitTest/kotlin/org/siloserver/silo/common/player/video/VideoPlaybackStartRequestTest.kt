package org.siloserver.silo.common.player.video

import kotlin.test.Test
import kotlin.test.assertEquals
import org.siloserver.silo.common.player.StartParams
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.watchtogether.WatchPartyPlaybackContext

class VideoPlaybackStartRequestTest {
    @Test
    fun `only a room, a chosen version or a pinned renewal pins the file`() {
        val room = WatchPartyPlaybackContext(
            roomId = "room",
            selectionRevision = 1,
            contentId = "movie",
            fileId = 7,
            libraryId = null,
            positionSeconds = 0.0,
            paused = false,
        )
        fun renewal(allow: Boolean?) = StartParams(
            contentId = "movie",
            fileId = 7,
            capabilities = ClientCodecCapabilities(),
            clientPlaybackContext = ClientPlaybackContext(formFactor = "mobile", appVersion = "test"),
            allowAlternateVersions = allow,
        )
        val base = VideoPlaybackStartRequest(
            contentId = "movie",
            preferredFileId = 7,
            roomId = null,
            resumePositionOverride = null,
        )
        val cases = listOf(
            "a default play" to (base to null),
            "a Watch Party room" to (base.copy(room = room) to false),
            "a version the viewer chose" to (base.copy(versionChosen = true) to false),
            "a renewal of a pinned start" to (base.copy(recoveryStartParams = renewal(false)) to false),
            "a renewal of a default play" to (base.copy(recoveryStartParams = renewal(null)) to null),
            "a chosen version over an unpinned renewal" to
                (base.copy(versionChosen = true, recoveryStartParams = renewal(null)) to false),
        )
        for ((name, case) in cases) {
            val (request, expected) = case
            assertEquals(expected, request.allowAlternateVersions(), name)
        }
    }
}
