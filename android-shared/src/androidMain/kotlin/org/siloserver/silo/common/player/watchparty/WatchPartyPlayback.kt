package org.siloserver.silo.common.player.watchparty

import android.os.SystemClock
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SourceFallbackReason
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.WatchPartyEnded
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.RoomPlaybackBinding
import org.siloserver.silo.watchtogether.RoomPlaybackNotice
import org.siloserver.silo.watchtogether.RoomPlayerPort
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.RoomTransportIntent
import org.siloserver.silo.watchtogether.RoomTransportResult
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import org.siloserver.silo.watchtogether.WatchPartyPlaybackContext
import org.siloserver.silo.watchtogether.playbackContext
import org.siloserver.silo.watchtogether.roomTransportAuthorized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * One player screen's view of the Watch Party engagement. It owns the
 * [RoomPlaybackBinding] for the screen's lifetime and gives the screen the
 * room's playback context, status, and actions. The process-wide
 * [RoomSession] owns the socket; disposing this never leaves the room.
 *
 * Both the phone and TV players use it in place of their own controllers.
 */
class WatchPartyPlayback(
    private val roomId: String,
    private val repository: WatchTogetherRepository,
    private val roomSession: RoomSession,
    private val availability: WatchPartyAvailabilityRepository,
    player: RoomPlayerPort,
    scope: CoroutineScope,
) {
    val binding = RoomPlaybackBinding(
        room = repository,
        player = player,
        parentScope = scope,
        monotonicNowMs = SystemClock::elapsedRealtime,
        wallNowMs = System::currentTimeMillis,
    )

    val room: StateFlow<RoomSnapshot?> get() = repository.roomSnapshot

    /** Why the engagement ended, once it has; the screen leaves the player. */
    val ended: StateFlow<WatchPartyEnded?> get() = repository.ended

    val notices: SharedFlow<RoomPlaybackNotice> get() = binding.notices
    val catchingUp: StateFlow<Boolean> get() = binding.catchingUp
    val offerLowerQuality: StateFlow<Boolean> get() = binding.offerLowerQuality

    /**
     * The playback the player must be prepared for. It changes only when the
     * room starts a new epoch (a new selection revision or file); anchor and
     * membership updates keep the current player.
     */
    val playbackContext: Flow<WatchPartyPlaybackContext?> =
        repository.roomSnapshot
            .map { it?.playbackContext() }
            .distinctUntilChanged { old, new ->
                old?.selectionRevision == new?.selectionRevision && old?.fileId == new?.fileId
            }

    fun start() {
        binding.start()
        WatchPartyDebugRegistry.register(binding)
        roomSession.adopt(roomId)
    }

    /** Stop this screen's binding; the room engagement continues. */
    fun dispose() {
        WatchPartyDebugRegistry.unregister(binding)
        binding.dispose()
    }

    fun requestPlayPause(pause: Boolean): RoomTransportResult = binding.requestPlayPause(pause)

    fun requestSeek(sourceSeconds: Double): RoomTransportResult = binding.requestSeek(sourceSeconds)

    /** Whether the local member may use [intent] right now. */
    fun allows(intent: RoomTransportIntent): Boolean = roomTransportAuthorized(room.value, intent)

    /**
     * A seek this screen did not issue (notification, headset, Bluetooth,
     * Assistant, any other MediaSession controller). The host's becomes a room
     * request; anyone else's is denied and explained. Restore the local
     * position before dispatching: only the accepted room command may apply
     * the target, and a host report must never re-anchor the room early.
     */
    fun onExternalSeek(fromSeconds: Double, toSeconds: Double, restore: (Double) -> Unit): RoomTransportResult {
        if (abs(toSeconds - fromSeconds) < EXTERNAL_SEEK_NOISE_SECONDS) return RoomTransportResult.Ignored
        val now = SystemClock.elapsedRealtime()
        // Every authorized host input must restore before it can be reported.
        // Keep the guest cooldown: repeated callbacks mistaken for outside
        // seeks must not make the guest fight room corrections in a loop.
        if (allows(RoomTransportIntent.Seek) || now - lastRestoreAtMs >= RESTORE_WINDOW_MS) {
            lastRestoreAtMs = now
            restore(fromSeconds)
        }
        return binding.requestSeek(toSeconds)
    }

    private var lastRestoreAtMs = Long.MIN_VALUE / 2

    /** Leave this device's membership. For a host, the room ends two minutes later unless they rejoin. */
    fun leave() {
        dispose()
        roomSession.depart(closeRoom = false)
    }

    /** End the party for everyone (host). */
    fun endForEveryone() {
        dispose()
        roomSession.depart(closeRoom = true)
    }

    /**
     * Report a playback refusal. Only the four approved reasons, and only when
     * the server advertises coordinated fallback, ask the room to move
     * everyone to another source; anything else stays a local error, and no
     * other file is ever chosen locally. Returns true when the room took over.
     */
    suspend fun reportRefusal(context: WatchPartyPlaybackContext, failedFileId: Int, reason: String?): Boolean {
        val approved = SourceFallbackReason.fromWire(reason?.trim()?.lowercase()) ?: return false
        if (availability.features?.sourceFallback != true) return false
        if (room.value?.selectionRevision != context.selectionRevision) return false
        val result = repository.requestSourceFallback(
            selectionRevision = context.selectionRevision,
            failedFileId = failedFileId.toString(),
            reason = approved,
        )
        return result is ApiResult.Success &&
            result.data.room.selectionRevision != context.selectionRevision
    }

    private companion object {
        /** Seek adjustments smaller than this are the player settling, not a request. */
        const val EXTERNAL_SEEK_NOISE_SECONDS = 1.0

        /** Minimum time between two undos of denied outside seeks. */
        const val RESTORE_WINDOW_MS = 2_000L
    }
}
