package org.siloserver.silo.common.player.video

/** A late read must not replace a newer marker event or reconnect snapshot. */
class MarkerReconciliationFence {
    private var generation = 0L
    fun begin(): Long = ++generation
    fun invalidate() { generation++ }
    fun isCurrent(ticket: Long): Boolean = ticket == generation
}
