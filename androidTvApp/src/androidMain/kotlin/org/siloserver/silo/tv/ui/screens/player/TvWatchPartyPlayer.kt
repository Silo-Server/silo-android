package org.siloserver.silo.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import org.koin.compose.koinInject
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import org.siloserver.silo.common.player.watchparty.WatchPartyPlayback
import org.siloserver.silo.repository.WatchPartyEndReason
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.playback.PlaybackAction
import org.siloserver.silo.watchtogether.RoomPlaybackNotice
import org.siloserver.silo.watchtogether.RoomPlayerObservation
import org.siloserver.silo.watchtogether.RoomPlayerPort
import org.siloserver.silo.watchtogether.RoomPlayerState
import org.siloserver.silo.watchtogether.RoomTransportIntent
import org.siloserver.silo.watchtogether.RoomTransportResult
import org.siloserver.silo.watchtogether.WatchPartyPlaybackContext
import kotlin.math.abs

/** Why a Watch Party viewer's playback is held locally, without asking the room. */
enum class TvRoomHold {
    /** The app left the foreground (ON_PAUSE / ON_STOP). */
    Background,

    /** Audio focus was lost or the output became noisy. */
    AudioFocus,

    /** The sleep timer fired. */
    SleepTimer,
}

/** A room start or replan the server refused, waiting for the room to decide. */
data class TvRoomRefusal(
    val context: WatchPartyPlaybackContext,
    val fileId: Int,
    val reason: String,
    val message: String,
)

/** The room playback-control port, mapped onto the TV player's ViewModel. */
internal class TvRoomPlayerPort(private val viewModel: TvPlayerViewModel) : RoomPlayerPort {
    override val observations: StateFlow<RoomPlayerObservation> = viewModel.roomObservation

    override fun seekTo(sourceSeconds: Double) = viewModel.applyRoomSeek(sourceSeconds)

    override fun setPlaying(playing: Boolean) = viewModel.applyRoomPlaying(playing)

    override fun setCorrectionRate(rate: Double?) = viewModel.setRoomCorrectionRate(rate)

    override fun isBuffered(sourceSeconds: Double): Boolean = viewModel.isRoomPositionBuffered(sourceSeconds)
}

/**
 * The TV player screen's side of a Watch Party: routes every user, remote,
 * SiloCast and MediaSession transport input through [playback], recognizes
 * seeks this screen did not issue, and feeds the ViewModel the player samples
 * the room observation is built from.
 *
 * Kept out of the composable so the player screen's generated method stays
 * within ART's JIT limit.
 */
internal class TvWatchPartyScreenController(
    val playback: WatchPartyPlayback,
    private val viewModel: TvPlayerViewModel,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val issuedSeeks = TvRoomIssuedSeeks()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var departed = false

    fun start() = playback.start()

    /** The screen is going away; the room engagement continues. */
    fun dispose() = playback.dispose()

    fun canSeek(): Boolean = playback.allows(RoomTransportIntent.Seek)

    fun canPlayPause(): Boolean = playback.allows(RoomTransportIntent.PlayPause)

    /** A play/pause toggle from the remote, the transport, SiloCast or an admin. */
    fun togglePlayPause(): Boolean = setPlaying(play = viewModel.uiState.value.isPaused)

    /**
     * Ask the room to play or pause. Pressing play while playback is held
     * locally clears the hold without asking the room when the room never
     * paused: a playing room resumes this viewer, and a waiting room keeps
     * its own barrier. Returns true when the input was accepted.
     */
    fun setPlaying(play: Boolean): Boolean {
        if (play && viewModel.isRoomHeld) {
            val room = playback.room.value
            when {
                room.isPlayingRoom() -> {
                    viewModel.resumeRoomPlaybackLocally()
                    return true
                }
                room?.playbackState == RoomPlaybackState.Waiting -> {
                    viewModel.releaseUserRoomHolds()
                    return true
                }
                else -> viewModel.releaseUserRoomHolds()
            }
        }
        return playback.requestPlayPause(pause = !play) == RoomTransportResult.Sent
    }

    /** Ask the room to seek (host only). Returns true when the request was sent. */
    fun seek(sourceSeconds: Double): Boolean =
        playback.requestSeek(sourceSeconds) == RoomTransportResult.Sent

    /** Every seek this screen hands to Media3, so its discontinuity is not mistaken for an outside seek. */
    fun recordIssuedSeek(playerPositionMs: Long) = issuedSeeks.record(playerPositionMs, nowMs())

    /** A Media3 seek discontinuity. Seeks from outside the app become room requests or are undone. */
    fun onSeekDiscontinuity(fromPlayerMs: Long, toPlayerMs: Long) {
        if (issuedSeeks.isOwn(toPlayerMs, nowMs())) return
        val from = viewModel.roomSourcePositionForPlayer(fromPlayerMs) ?: return
        val to = viewModel.roomSourcePositionForPlayer(toPlayerMs) ?: return
        playback.onExternalSeek(from, to) { restore -> viewModel.applyRoomSeek(restore) }
    }

    /**
     * A deliberate MediaSession play/pause (notification, headset, Assistant).
     * The room decides; returns the local play state the player must go back
     * to meanwhile.
     */
    fun onExternalPlayWhenReady(playWhenReady: Boolean): Boolean {
        setPlaying(play = playWhenReady)
        return !viewModel.uiState.value.isPaused
    }

    /**
     * A transport command from the playback-control socket. Transport follows
     * the room's permission decision; Stop and terminate end only this
     * device's engagement. Returns whether the command was accepted.
     */
    fun onRemoteTransport(action: PlaybackAction, exit: () -> Unit): Boolean = when (action) {
        PlaybackAction.Pause -> setPlaying(play = false)
        PlaybackAction.Unpause -> setPlaying(play = true)
        PlaybackAction.TogglePlayPause -> togglePlayPause()
        is PlaybackAction.SeekTo -> seek(action.positionSeconds)
        PlaybackAction.Stop -> {
            leave()
            exit()
            true
        }
        else -> false
    }

    /**
     * A phone's SiloCast launch would replace this party player. Refuse it
     * and say why on the TV too. Called off the main thread.
     */
    fun refuseLaunch(): String {
        mainHandler.post { viewModel.showPlayerMessage(TV_WATCH_PARTY_LAUNCH_REFUSAL) }
        return TV_WATCH_PARTY_LAUNCH_REFUSAL
    }

    /** The viewer changed quality: the room's stall count and reload pacing start over. */
    fun onQualityChanged() = playback.binding.onQualityChanged()

    /** Leave this device's membership, once. For a host the room ends two minutes later. */
    fun leave() {
        if (departed) return
        departed = true
        playback.leave()
    }

    /** End the party for everyone (host), once. */
    fun endForEveryone() {
        if (departed) return
        departed = true
        playback.endForEveryone()
    }

    /** The engagement already ended on the server; leaving would only erase that record. */
    fun markEnded() {
        departed = true
    }

    /** Publish what the player is actually doing. */
    fun sample(player: Player) {
        viewModel.onRoomPlayerSample(
            positionMs = player.currentPosition,
            bufferedPositionMs = player.bufferedPosition,
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            playbackState = player.playbackState,
            suppressed = player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        )
    }
}

/**
 * The Watch Party binding for one player screen, or null for solo playback.
 * Built once per room; started and disposed with the screen. Disposing never
 * leaves the room.
 */
@Composable
internal fun rememberTvWatchParty(roomId: String?, viewModel: TvPlayerViewModel): TvWatchPartyScreenController? {
    val repository: WatchTogetherRepository = koinInject()
    val roomSession: RoomSession = koinInject()
    val availability: WatchPartyAvailabilityRepository = koinInject()
    val scope = rememberCoroutineScope()
    val watchParty = remember(roomId) {
        roomId?.takeIf { it.isNotBlank() }?.let { id ->
            TvWatchPartyScreenController(
                playback = WatchPartyPlayback(
                    roomId = id,
                    repository = repository,
                    roomSession = roomSession,
                    availability = availability,
                    player = TvRoomPlayerPort(viewModel),
                    scope = scope,
                ),
                viewModel = viewModel,
            )
        }
    }
    DisposableEffect(watchParty) {
        watchParty?.start()
        onDispose { watchParty?.dispose() }
    }
    return watchParty
}

/**
 * The speed the player runs at. Solo: the saved preference. A Watch Party
 * ignores it: exactly 1x, or the room's temporary correction rate, which is
 * never saved or shown.
 */
@Composable
internal fun TvPlaybackSpeedEffect(
    player: Player?,
    inWatchParty: Boolean,
    viewModel: TvPlayerViewModel,
    savedSpeed: Double,
) {
    val correctionRate by viewModel.roomCorrectionRate.collectAsState()
    val speed = if (inWatchParty) correctionRate ?: 1.0 else savedSpeed
    // PlaybackParameters because MediaController has no setter that keeps
    // pitch correction defaults.
    LaunchedEffect(player, speed) {
        player?.playbackParameters = PlaybackParameters(speed.toFloat())
    }
}

/** Whether the viewer dismissed the current lower-quality offer. */
@Stable
internal class TvWatchPartyQualityOfferState {
    var dismissed by mutableStateOf(false)
}

/**
 * After repeated stalls in a room: one step down the same file's quality
 * ladder, or null when there is no offer, it was dismissed, or no lower rung
 * remains. A new offer after a quality change can show again.
 */
@Composable
internal fun tvWatchPartyLowerQuality(
    watchParty: TvWatchPartyScreenController?,
    offerState: TvWatchPartyQualityOfferState,
    videoQualities: List<VideoQualityOption>,
    selectedFileResolution: String?,
): VideoQualityOption? {
    val offered = watchParty?.playback?.offerLowerQuality?.collectAsState()?.value ?: false
    LaunchedEffect(offered) { if (!offered) offerState.dismissed = false }
    if (!offered || offerState.dismissed) return null
    return tvLowerQualityOption(videoQualities, tvQualityHeight(selectedFileResolution))
}

/** Switch the same file's quality; in a room the binding's stall pacing starts over. */
internal fun switchTvPlaybackQuality(
    viewModel: TvPlayerViewModel,
    watchParty: TvWatchPartyScreenController?,
    id: String,
) {
    if (viewModel.switchQuality(id)) watchParty?.onQualityChanged()
}

/** How often the room observation samples the player. */
private const val ROOM_SAMPLE_INTERVAL_MS = 200L

/** A player route whose room never appears (no live membership) gives up after this. */
private const val ROOM_MISSING_EXIT_MS = 10_000L

/**
 * The Watch Party side effects of one player screen: loads each room epoch,
 * samples the player for the room observation, shows notices, reports
 * refusals to the room, and leaves the player when the room stops playing
 * (back to the lobby, membership kept) or the party ends.
 */
@Composable
internal fun TvWatchPartyEffects(
    watchParty: TvWatchPartyScreenController,
    roomId: String,
    viewModel: TvPlayerViewModel,
    player: Player?,
    onReturnToLobby: () -> Unit,
    onPartyEnded: () -> Unit,
    repository: WatchTogetherRepository = koinInject(),
) {
    val context = LocalContext.current
    val latestReturnToLobby by rememberUpdatedState(onReturnToLobby)
    val latestPartyEnded by rememberUpdatedState(onPartyEnded)

    // Each new room epoch (selection revision or file) prepares the exact
    // file at the room position, paused. Anchor and membership updates keep
    // the current player.
    LaunchedEffect(watchParty) {
        watchParty.playback.playbackContext.collect { playback ->
            if (playback != null) viewModel.loadRoomPlayback(playback)
        }
    }

    // Host Stop keeps the membership and returns to the lobby; an ended
    // party leaves the player.
    LaunchedEffect(watchParty) {
        watchParty.playback.room.collect { room ->
            if (room?.phase == RoomPhase.Lobby) latestReturnToLobby()
        }
    }
    LaunchedEffect(watchParty) {
        watchParty.playback.ended.filterNotNull().collect { ended ->
            if (ended.roomId != roomId) return@collect
            watchParty.markEnded()
            Toast.makeText(context, tvWatchPartyEndedText(ended.reason), Toast.LENGTH_SHORT).show()
            latestPartyEnded()
        }
    }
    // A route with no live membership behind it (the room token is memory
    // only) would otherwise wait on the loading screen forever.
    LaunchedEffect(watchParty) {
        watchParty.playback.room.collectLatest { room ->
            if (room != null) return@collectLatest
            delay(ROOM_MISSING_EXIT_MS)
            if (watchParty.playback.ended.value == null) {
                Toast.makeText(context, "This Watch Party isn't available anymore.", Toast.LENGTH_SHORT).show()
                latestPartyEnded()
            }
        }
    }

    LaunchedEffect(watchParty) {
        watchParty.playback.notices.collect { notice ->
            viewModel.showPlayerMessage(tvWatchPartyNoticeText(notice))
        }
    }
    // Transient server rejections (a refused request) never end the party.
    LaunchedEffect(watchParty) {
        repository.errors.collect { message ->
            if (message.isNotBlank()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // A refused start or replan goes to the room first. Only the approved
    // reasons move everyone to another source; otherwise it is this
    // player's error. No other file is ever chosen here.
    LaunchedEffect(watchParty) {
        viewModel.roomRefusal.filterNotNull().collect { refusal ->
            val tookOver = watchParty.playback.reportRefusal(
                context = refusal.context,
                failedFileId = refusal.fileId,
                reason = refusal.reason,
            )
            viewModel.onRoomRefusalHandled(refusal, tookOver)
        }
    }

    // Engine truth for the room binding, sampled between Media3 callbacks.
    LaunchedEffect(watchParty, player) {
        val sampled = player ?: return@LaunchedEffect
        while (isActive) {
            watchParty.sample(sampled)
            delay(ROOM_SAMPLE_INTERVAL_MS)
        }
    }
}

private fun org.siloserver.silo.model.watchtogether.RoomSnapshot?.isPlayingRoom(): Boolean =
    this != null &&
        phase == RoomPhase.Playing &&
        !isPaused &&
        playbackState == RoomPlaybackState.Playing

/** Plain-language text for a room notice. */
internal fun tvWatchPartyNoticeText(notice: RoomPlaybackNotice): String = when (notice) {
    is RoomPlaybackNotice.Denied -> when (notice.intent) {
        RoomTransportIntent.Seek -> "Only the host can seek."
        RoomTransportIntent.PlayPause -> "Only the host can play or pause."
    }
    RoomPlaybackNotice.Reconnecting -> "Reconnecting to the party…"
    RoomPlaybackNotice.Undelivered -> "Couldn't reach the party. Try again."
}

internal const val TV_WATCH_PARTY_LAUNCH_REFUSAL = "Leave the Watch Party to play something else."

/** Whether a freshly published session starts paused: always in a Watch Party, where only the room plays. */
internal fun tvPausedWhenReady(inWatchParty: Boolean): Boolean = inWatchParty

/**
 * The context a room start sends. The first start of a revision uses the room
 * position as sent. A restart of a session already prepared for this
 * revision (retry, reachability retry, 404 renewal) resumes where this player
 * was: the requested position, else the current one. Positions from an
 * earlier revision never carry over, and the file never changes.
 */
internal fun tvRoomStartContext(
    context: WatchPartyPlaybackContext?,
    startPositionOverride: Double?,
    currentSourcePositionSeconds: Double,
    preparedRevision: Long,
): WatchPartyPlaybackContext? {
    val room = context ?: return null
    if (preparedRevision != room.selectionRevision) return room
    val position = startPositionOverride?.takeIf { it.isFinite() && it >= 0.0 }
        ?: currentSourcePositionSeconds.takeIf { it.isFinite() && it > 0.0 }
        ?: room.positionSeconds
    return room.copy(positionSeconds = position)
}

/** Plain-language text for why this device's party ended. */
internal fun tvWatchPartyEndedText(reason: String): String = when (reason) {
    WatchPartyEndReason.Replaced -> "You joined this Watch Party on another device."
    WatchPartyEndReason.ConnectionLost -> "Lost the connection to the Watch Party."
    else -> "The Watch Party ended."
}

/**
 * Whether reaching [targetPlayerSeconds] needs no new media: it must lie
 * between a second behind the playhead and the buffered position of the
 * mounted item, all in player time.
 */
internal fun tvRoomPositionBuffered(
    targetPlayerSeconds: Double,
    currentPlayerSeconds: Double,
    bufferedPlayerSeconds: Double,
    behindToleranceSeconds: Double = 1.0,
): Boolean {
    if (!targetPlayerSeconds.isFinite() || !currentPlayerSeconds.isFinite() || !bufferedPlayerSeconds.isFinite()) {
        return false
    }
    if (bufferedPlayerSeconds < currentPlayerSeconds) return false
    return targetPlayerSeconds >= currentPlayerSeconds - behindToleranceSeconds &&
        targetPlayerSeconds <= bufferedPlayerSeconds
}

/** The room player state for a Media3 playback state. */
internal fun tvRoomPlayerState(playbackState: Int?, hasMedia: Boolean): RoomPlayerState {
    if (!hasMedia) return RoomPlayerState.Idle
    return when (playbackState) {
        Player.STATE_BUFFERING -> RoomPlayerState.Buffering
        Player.STATE_READY -> RoomPlayerState.Ready
        Player.STATE_ENDED -> RoomPlayerState.Ended
        else -> RoomPlayerState.Idle
    }
}

/**
 * The speed a SiloCast `setPlaybackSpeed` may apply, or null to refuse it.
 * A Watch Party plays at 1x (plus the room's own correction), so a phone
 * cannot change it there.
 */
internal fun tvSiloCastPlaybackSpeed(inWatchParty: Boolean, requested: Double): Double? =
    requested.takeIf { !inWatchParty && it.isFinite() && it > 0.0 }

/**
 * Seeks this screen issued, remembered briefly so the discontinuity each one
 * produces is not mistaken for a seek from outside the app.
 */
internal class TvRoomIssuedSeeks(
    private val toleranceMs: Long = 500L,
    private val lifetimeMs: Long = 3_000L,
    private val capacity: Int = 16,
) {
    private data class Issued(val targetMs: Long, val issuedAtMs: Long)

    private val issued = ArrayDeque<Issued>()

    fun record(targetPlayerMs: Long, nowMs: Long) {
        prune(nowMs)
        issued.addLast(Issued(targetPlayerMs.coerceAtLeast(0L), nowMs))
        while (issued.size > capacity) issued.removeFirst()
    }

    /** True when [targetPlayerMs] matches a seek this screen issued recently. */
    fun isOwn(targetPlayerMs: Long, nowMs: Long): Boolean {
        prune(nowMs)
        return issued.any { abs(it.targetMs - targetPlayerMs) <= toleranceMs }
    }

    private fun prune(nowMs: Long) {
        while (issued.isNotEmpty() && nowMs - issued.first().issuedAtMs > lifetimeMs) {
            issued.removeFirst()
        }
    }
}

/**
 * One step down the same file's quality ladder: the highest rung below the
 * current height. With no known height (Auto or Original), the top rung.
 */
internal fun tvLowerQualityOption(
    options: List<VideoQualityOption>,
    fallbackCurrentHeight: Int?,
): VideoQualityOption? {
    val rungs = options
        .mapNotNull { option -> tvQualityHeight(option.resolution)?.let { option to it } }
        .sortedByDescending { it.second }
    if (rungs.isEmpty()) return null
    val selected = options.firstOrNull { it.isSelected }
    val current = tvQualityHeight(selected?.resolution) ?: fallbackCurrentHeight
    return if (current != null) {
        rungs.firstOrNull { it.second < current }?.first
    } else {
        rungs.first().first
    }
}

/** A vertical resolution from labels such as "720p", "1080p" or "4K". */
internal fun tvQualityHeight(label: String?): Int? {
    val text = label?.trim()?.lowercase() ?: return null
    Regex("(\\d{3,4})p").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return when {
        "8k" in text -> 4320
        "4k" in text || "uhd" in text -> 2160
        else -> null
    }
}
