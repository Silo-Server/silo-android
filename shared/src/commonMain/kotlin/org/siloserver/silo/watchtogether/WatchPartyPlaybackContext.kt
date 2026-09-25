package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot

/**
 * What a player must prepare for one room playback epoch: the room's exact
 * content, file, and library, the room position, and whether it starts
 * paused. [positionSeconds] is the snapshot anchor as sent; the server has
 * already projected it, and the command sent after attach corrects the rest.
 */
data class WatchPartyPlaybackContext(
    val roomId: String,
    val selectionRevision: Long,
    val contentId: String,
    val fileId: Int,
    val libraryId: Int?,
    val positionSeconds: Double,
    val paused: Boolean,
)

/**
 * The playback context for a playing room, or null when the room is not
 * playing or has not resolved its file yet.
 */
fun RoomSnapshot.playbackContext(): WatchPartyPlaybackContext? {
    if (phase != RoomPhase.Playing) return null
    val content = selectedContentId?.takeIf { it.isNotBlank() } ?: return null
    val file = selectedFileId?.takeIf { it > 0 } ?: return null
    return WatchPartyPlaybackContext(
        roomId = roomId,
        selectionRevision = selectionRevision,
        contentId = content,
        fileId = file,
        libraryId = selectedLibraryId,
        positionSeconds = anchorPositionSeconds.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
        // Everyone starts paused; the room's commands decide when to play.
        paused = isPaused || playbackState != RoomPlaybackState.Playing,
    )
}
