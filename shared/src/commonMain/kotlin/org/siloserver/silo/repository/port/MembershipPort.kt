package org.siloserver.silo.repository.port

import org.siloserver.silo.network.DurableLoginAuthority

/** Dormant contract: UI cutover must retain the command witness through completion/reload. */
interface MembershipPort {
    enum class Kind { FAVORITE, WATCHLIST }
    enum class Disposition { ACKNOWLEDGED, RECONCILED, NEEDS_RECONCILIATION, PAUSED, NOT_CLAIMED }
    data class Command(val id: Long, val authority: DurableLoginAuthority, val itemId: String, val kind: Kind)
    data class Completion(val disposition: Disposition, val mayPublish: Boolean)
    data class Projection(val commandId: Long, val present: Boolean, val disposition: Disposition?)

    suspend fun captureAuthority(): DurableLoginAuthority?
    suspend fun record(authority: DurableLoginAuthority, itemId: String, kind: Kind, present: Boolean): Command
    suspend fun send(command: Command): Completion
    suspend fun reconcile(command: Command): Completion
    suspend fun projection(authority: DurableLoginAuthority, itemId: String, kind: Kind): Projection?
}
