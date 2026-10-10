package org.siloserver.silo.common.player.watchparty

import android.os.SystemClock
import org.siloserver.silo.watchtogether.RoomPlaybackNotice
import org.siloserver.silo.watchtogether.RoomTransportIntent
import kotlin.math.abs

/**
 * Seeks a player screen sent to Media3, remembered briefly so a seek made
 * anywhere else (notification, headset or Bluetooth skip, Assistant, another
 * MediaSession controller) can be told apart from its own. Positions are on
 * the mounted player's timeline.
 *
 * Register a target before calling `seekTo`: a MediaController reports its own
 * seek to listeners synchronously, inside that call.
 */
class IssuedSeekTracker(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class Issued(val targetMs: Long, val issuedAtMs: Long)

    private val issued = ArrayDeque<Issued>()

    fun note(targetMs: Long) {
        prune()
        issued.addLast(Issued(targetMs.coerceAtLeast(0L), nowMs()))
        while (issued.size > MAX_TRACKED) issued.removeFirst()
    }

    /**
     * True when a seek landing at [positionMs] was not one this screen issued.
     * A match is not consumed: a MediaController reports its own seek at once
     * and the session reports the same seek again, and a guest that took the
     * second report for someone else's seek undid it, which the room then
     * redid, in a loop. Issued targets simply expire.
     */
    fun isExternal(positionMs: Long): Boolean {
        prune()
        return issued.none { abs(it.targetMs - positionMs) <= TOLERANCE_MS }
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

/** Plain-language text for a room notice, on phone and TV alike. */
fun watchPartyNoticeText(notice: RoomPlaybackNotice): String = when (notice) {
    is RoomPlaybackNotice.Denied -> when (notice.intent) {
        RoomTransportIntent.Seek -> "Only the host can seek."
        RoomTransportIntent.PlayPause -> "Only the host can play or pause."
    }
    RoomPlaybackNotice.Reconnecting -> "Reconnecting to the party…"
    RoomPlaybackNotice.ClockUnavailable -> "Waiting for party timing. If this continues, leave and rejoin."
    RoomPlaybackNotice.Undelivered -> "Couldn't reach the party. Try again."
}
