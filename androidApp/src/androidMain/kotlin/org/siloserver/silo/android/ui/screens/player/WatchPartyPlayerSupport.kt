package org.siloserver.silo.android.ui.screens.player

import androidx.media3.common.Player
import org.siloserver.silo.common.player.seek.PlaybackSeekDecision
import org.siloserver.silo.common.player.seek.decideSeek
import org.siloserver.silo.common.player.watchparty.WatchPartyPlayback
import org.siloserver.silo.model.playback.PlaybackAvailableQualityV3
import org.siloserver.silo.model.playback.PlaybackTimeline
import org.siloserver.silo.playback.PlaybackAction
import org.siloserver.silo.watchtogether.RoomPlayerObservation
import org.siloserver.silo.watchtogether.RoomPlayerPort
import org.siloserver.silo.watchtogether.RoomPlayerState
import org.siloserver.silo.watchtogether.RoomTransportResult
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone player's side of the Watch Party binding. It reports what
 * [PlayerViewModel] observes from Media3 and applies what the binding asks,
 * through paths that never count as the viewer's own transport intent.
 */
internal class MobileRoomPlayerPort(private val viewModel: PlayerViewModel) : RoomPlayerPort {
    override val observations: StateFlow<RoomPlayerObservation> = viewModel.roomObservation

    override fun seekTo(sourceSeconds: Double) = viewModel.applyRoomSeek(sourceSeconds)

    override fun setPlaying(playing: Boolean) = viewModel.applyRoomPlaying(playing)

    override fun setCorrectionRate(rate: Double?) = viewModel.setRoomCorrectionRate(rate)

    override fun isBuffered(sourceSeconds: Double): Boolean = viewModel.isRoomPositionBuffered(sourceSeconds)
}

internal fun roomPlayerState(playbackState: Int): RoomPlayerState = when (playbackState) {
    Player.STATE_BUFFERING -> RoomPlayerState.Buffering
    Player.STATE_READY -> RoomPlayerState.Ready
    Player.STATE_ENDED -> RoomPlayerState.Ended
    else -> RoomPlayerState.Idle
}

/**
 * Whether the mounted stream can reach [targetSourceSeconds] without loading
 * new media: the target maps natively onto the current plan's timeline (no
 * re-anchor) and lands between just behind the playhead and the buffered
 * position. False whenever that cannot be shown.
 */
internal fun roomTargetBufferedLocally(
    timeline: PlaybackTimeline?,
    targetSourceSeconds: Double,
    playerPositionSeconds: Double,
    bufferedPlayerSeconds: Double,
    backwardSlackSeconds: Double = 1.0,
): Boolean {
    if (timeline == null) return false
    if (!targetSourceSeconds.isFinite() || targetSourceSeconds < 0.0) return false
    if (!playerPositionSeconds.isFinite() || !bufferedPlayerSeconds.isFinite()) return false
    if (bufferedPlayerSeconds < playerPositionSeconds) return false
    val native = timeline.decideSeek(targetSourceSeconds) as? PlaybackSeekDecision.NativeSeek ?: return false
    val target = native.targetPlayerPositionSeconds
    return target >= playerPositionSeconds - backwardSlackSeconds && target <= bufferedPlayerSeconds
}

/**
 * One rung below the current one on the same file's quality ladder, or null
 * when nothing lower exists. An unmatched [selectedLabel] (Auto, Original, or
 * none) is treated as the source rung.
 */
internal fun lowerQualityRung(
    available: List<PlaybackAvailableQualityV3>,
    selectedLabel: String?,
): PlaybackAvailableQualityV3? {
    val rungs = available.filter { it.height > 0 }
    val current = rungs.firstOrNull { it.label == selectedLabel }
        ?: rungs.firstOrNull { it.preservesSource }
        ?: rungs.maxByOrNull { it.height }
        ?: return null
    return rungs
        .filter { !it.preservesSource && it.height < current.height }
        .maxByOrNull { it.height }
}

/** Routes a playback-control socket transport command through the room's permission decision. */
internal fun routeRemoteTransportToRoom(
    action: PlaybackAction,
    party: WatchPartyPlayback,
    locallyPaused: Boolean,
): Boolean = when (action) {
    PlaybackAction.Pause -> party.requestPlayPause(pause = true)
    PlaybackAction.Unpause -> party.requestPlayPause(pause = false)
    PlaybackAction.TogglePlayPause -> party.requestPlayPause(pause = !locallyPaused)
    is PlaybackAction.SeekTo -> party.requestSeek(action.positionSeconds)
    else -> RoomTransportResult.Ignored
} == RoomTransportResult.Sent
