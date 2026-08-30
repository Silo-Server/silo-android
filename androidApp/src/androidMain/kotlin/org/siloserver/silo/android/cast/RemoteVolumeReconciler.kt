package org.siloserver.silo.android.cast

import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Holds locally requested volume and mute values until the TV acknowledges
 * them in order.
 *
 * SiloCast sends absolute values and the TV answers every command with a full
 * state frame. During a burst, an older reply must not rewind the optimistic
 * level used by the next hardware-button step. Tracking the ordered requests
 * also handles reversals such as `0.5 -> 0.5625 -> 0.5`: a pre-command `0.5`
 * snapshot cannot acknowledge the second request while the first is pending.
 */
internal class RemoteVolumeReconciler {
    private data class PendingVolumeRequest(
        val volume: Double,
        val requestedAtMs: Long,
    )

    private data class PendingMuteRequest(
        val isMuted: Boolean,
        val requestedAtMs: Long,
    )

    private val pendingVolumes = ArrayDeque<PendingVolumeRequest>()
    private val pendingMutes = ArrayDeque<PendingMuteRequest>()

    fun requested(volume: Double, atMs: Long) {
        pendingVolumes.addLast(PendingVolumeRequest(volume = volume, requestedAtMs = atMs))
    }

    fun requestedMuted(isMuted: Boolean, atMs: Long) {
        pendingMutes.addLast(PendingMuteRequest(isMuted = isMuted, requestedAtMs = atMs))
    }

    fun clearVolume() {
        pendingVolumes.clear()
    }

    fun clear() {
        pendingVolumes.clear()
        pendingMutes.clear()
    }

    fun reconcile(inbound: Double, atMs: Long): Double {
        val latest = pendingVolumes.peekLast() ?: return inbound
        if (atMs - latest.requestedAtMs >= WINDOW_MS) {
            pendingVolumes.clear()
            return inbound
        }

        val earliest = pendingVolumes.peekFirst()
        if (earliest != null && abs(inbound - earliest.volume) < TOLERANCE) {
            pendingVolumes.removeFirst()
            return pendingVolumes.peekLast()?.volume ?: inbound
        }

        return latest.volume
    }

    fun reconcileMuted(inbound: Boolean, atMs: Long): Boolean {
        val latest = pendingMutes.peekLast() ?: return inbound
        if (atMs - latest.requestedAtMs >= WINDOW_MS) {
            pendingMutes.clear()
            return inbound
        }

        val earliest = pendingMutes.peekFirst()
        if (earliest != null && inbound == earliest.isMuted) {
            pendingMutes.removeFirst()
            return pendingMutes.peekLast()?.isMuted ?: inbound
        }

        return latest.isMuted
    }

    private companion object {
        const val WINDOW_MS = 4_000L
        const val TOLERANCE = 0.001
    }
}
