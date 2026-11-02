package org.siloserver.silo.watchtogether

import kotlinx.coroutines.flow.MutableStateFlow

/** Local lifetime only; room membership deliberately outlives a player screen. */
class RoomPlaybackLease(private val sessionId: () -> String?) {
    private val active = MutableStateFlow(true)
    fun revoke() { active.value = false }
    fun intent(): RoomPlaybackIntent = RoomPlaybackIntent(this, sessionId())
    internal fun owns(session: String?): Boolean = active.value && session != null && sessionId() == session
}

/** Created at input time, before a coalesced seek has a wire request ID. */
class RoomPlaybackIntent internal constructor(private val lease: RoomPlaybackLease, private val session: String?) {
    val isCurrent: Boolean get() = lease.owns(session)
}
