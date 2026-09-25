package org.siloserver.silo.android.ui.screens.player

import android.os.SystemClock
import androidx.media3.common.Player
import org.siloserver.silo.common.player.seek.PlaybackSeekDecision
import org.siloserver.silo.common.player.seek.decideSeek
import org.siloserver.silo.common.player.watchparty.WatchPartyPlayback
import org.siloserver.silo.model.playback.PlaybackAvailableQualityV3
import org.siloserver.silo.model.playback.PlaybackTimeline
import org.siloserver.silo.playback.PlaybackAction
import org.siloserver.silo.watchtogether.RoomPlaybackNotice
import org.siloserver.silo.watchtogether.RoomPlayerObservation
import org.siloserver.silo.watchtogether.RoomPlayerPort
import org.siloserver.silo.watchtogether.RoomPlayerState
import org.siloserver.silo.watchtogether.RoomTransportIntent
import org.siloserver.silo.watchtogether.RoomTransportResult
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

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

/**
 * Seeks this screen sent to Media3, remembered briefly so a seek made anywhere
 * else (notification, headset or Bluetooth skip, Assistant, another
 * MediaSession controller) can be told apart from our own.
 *
 * Register a target before calling `seekTo`: a MediaController reports its own
 * seek to listeners synchronously, inside that call.
 */
internal class IssuedSeekTracker(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class Issued(val targetMs: Long, val issuedAtMs: Long)

    private val issued = ArrayDeque<Issued>()

    fun note(targetMs: Long) {
        prune()
        issued.addLast(Issued(targetMs.coerceAtLeast(0L), nowMs()))
        while (issued.size > MAX_TRACKED) issued.removeFirst()
    }

    /** True when a seek landing at [positionMs] was not one this screen issued. A match is consumed. */
    fun isExternal(positionMs: Long): Boolean {
        prune()
        val match = issued.indexOfFirst { abs(it.targetMs - positionMs) <= TOLERANCE_MS }
        if (match < 0) return true
        issued.removeAt(match)
        return false
    }

    private fun prune() {
        val cutoff = nowMs() - EXPIRY_MS
        while (issued.isNotEmpty() && issued.first().issuedAtMs < cutoff) issued.removeFirst()
    }

    companion object {
        const val TOLERANCE_MS = 500L
        const val EXPIRY_MS = 3_000L
        private const val MAX_TRACKED = 16
    }
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

internal fun watchPartyNoticeText(notice: RoomPlaybackNotice): String = when (notice) {
    is RoomPlaybackNotice.Denied -> when (notice.intent) {
        RoomTransportIntent.Seek -> "Only the host can seek."
        RoomTransportIntent.PlayPause -> "Only the host can play or pause."
    }
    RoomPlaybackNotice.Reconnecting -> "Reconnecting to the party…"
    RoomPlaybackNotice.Undelivered -> "Couldn't reach the party. Try again."
}
