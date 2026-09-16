package org.siloserver.silo.repository.port

import org.siloserver.silo.network.DurableLoginAuthority

/** UI retains the command witness through completion and explicit read recovery. */
interface MembershipPort {
    enum class Kind { FAVORITE, WATCHLIST }
    enum class Disposition { ACKNOWLEDGED, RECONCILED, NEEDS_RECONCILIATION, PAUSED, NOT_CLAIMED }
    data class Command(val id: Long, val authority: DurableLoginAuthority, val itemId: String, val kind: Kind, val present: Boolean)
    data class Completion(val disposition: Disposition, val mayPublish: Boolean)
    data class Projection(val commandId: Long, val present: Boolean, val disposition: Disposition?)

    val changes: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.flowOf(Unit)

    suspend fun read(authority: DurableLoginAuthority, itemId: String, kind: Kind): org.siloserver.silo.network.ApiResult<Boolean>
    suspend fun pending(authority: DurableLoginAuthority, limit: Int = 100): List<Command>
    suspend fun hasLegacyQuarantine(): Boolean
    suspend fun dispatch(limit: Int = 25): List<Completion>
    suspend fun readyCount(): Int
    suspend fun captureAuthority(): DurableLoginAuthority?
    suspend fun record(authority: DurableLoginAuthority, itemId: String, kind: Kind, present: Boolean): Command
    suspend fun send(command: Command): Completion
    suspend fun reconcile(command: Command): Completion
    suspend fun projection(authority: DurableLoginAuthority, itemId: String, kind: Kind): Projection?
}
