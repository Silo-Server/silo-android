package org.siloserver.silo.android.cast

import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Holds locally requested absolute volume levels until the TV acknowledges
 * them in order.
 *
 * SiloCast sends absolute values and the TV answers every command with a full
 * state frame. During a burst, an older reply must not rewind the optimistic
 * level used by the next hardware-button step. Tracking the ordered requests
 * also handles reversals such as `0.5 -> 0.5625 -> 0.5`: a pre-command `0.5`
 * snapshot cannot acknowledge the second request while the first is pending.
 */
internal class RemoteVolumeReconciler {
    private data class PendingRequest(
        val volume: Double,
        val requestedAtMs: Long,
    )

    private val pending = ArrayDeque<PendingRequest>()

    fun requested(volume: Double, atMs: Long) {
        pending.addLast(PendingRequest(volume = volume, requestedAtMs = atMs))
    }

    fun clear() {
        pending.clear()
    }

    fun reconcile(inbound: Double, atMs: Long): Double {
        val latest = pending.peekLast() ?: return inbound
        if (atMs - latest.requestedAtMs >= WINDOW_MS) {
            pending.clear()
            return inbound
        }

        val earliest = pending.peekFirst()
        if (earliest != null && abs(inbound - earliest.volume) < TOLERANCE) {
            pending.removeFirst()
            return pending.peekLast()?.volume ?: inbound
        }

        return latest.volume
    }

    private companion object {
        const val WINDOW_MS = 4_000L
        const val TOLERANCE = 0.001
    }
}
