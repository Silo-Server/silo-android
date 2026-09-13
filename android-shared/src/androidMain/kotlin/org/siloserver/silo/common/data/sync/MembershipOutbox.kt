package org.siloserver.silo.common.data.sync

import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.siloserver.silo.common.data.db.dao.DirtyOperationDao
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.MembershipV2Api

/**
 * Storage foundation only. Both inline and worker sends must use [send] after activation.
 * [authority] is a persisted login identity, NOT a server/profile pair or process counter.
 * The caller must verify its association with [scope] before constructing an Authority.
 * No credentials are persisted in Room. A process owns one instance and calls [recover]
 * before starting any senders; a second live process must not reclaim its claims.
 */
class MembershipOutbox(
    private val dao: DirtyOperationDao,
    private val api: MembershipV2Api,
    private val tokens: TokenManager,
    private val processOwner: String,
) {
    data class Authority(val key: String, val scope: AuthScopeSnapshot)
    enum class ListKind { FAVORITE, WATCHLIST }
    enum class Resolution { ACKNOWLEDGED, RECONCILED, NEEDS_RECONCILIATION, PAUSED, NOT_CLAIMED }
    data class Result(val resolution: Resolution, val mayPublish: Boolean = false)

    init { require(processOwner.isNotBlank()) }

    suspend fun recover(): Int = dao.recoverMembership(processOwner)

    suspend fun enqueue(authority: Authority, itemId: String, kind: ListKind, present: Boolean): Long {
        require(authority.key.isNotBlank() && itemId.isNotBlank())
        require(authority.scope == tokens.snapshotCurrentScope()) { "The active authority changed" }
        return enqueueCaptured(authority, itemId, kind, present)
    }

    /** Caller holds the identity generation barrier and already validated durable authority. */
    internal suspend fun enqueueCaptured(authority: Authority, itemId: String, kind: ListKind, present: Boolean): Long {
        require(authority.key.isNotBlank() && itemId.isNotBlank())
        // Fresh immutable command identity even if a repeated intent has identical payload.
        return dao.enqueueMembership(DirtyOperationEntity(
            opKind = "MEMBERSHIP_${kind.name}", serverId = authority.scope.serverId,
            profileId = authority.scope.profileId.orEmpty(), targetContentId = itemId,
            targetFileId = null, coalesceKey = "membership:${authority.key}:${kind.name}:$itemId",
            idempotencyKey = UUID.randomUUID().toString(), payloadJson = present.toString(),
            state = READY, createdAtMs = System.currentTimeMillis(), membershipAuthority = authority.key,
        ))
    }

    suspend fun send(id: Long, authority: Authority): Result {
        if (authority.scope != tokens.snapshotCurrentScope()) return Result(Resolution.NOT_CLAIMED)
        val claim = UUID.randomUUID().toString()
        var resolved = false
        try {
            if (dao.claimMembership(id, authority.key, claim, processOwner) != 1) return Result(Resolution.NOT_CLAIMED)
            val command = requireNotNull(dao.getById(id))
            require(command.serverId == authority.scope.serverId && command.profileId == authority.scope.profileId.orEmpty())
            val present = command.payloadJson.toBooleanStrict()
            val response = when (command.opKind) {
                "MEMBERSHIP_FAVORITE" -> if (present) api.addFavorite(command.targetContentId, authority.scope)
                    else api.removeFavorite(command.targetContentId, authority.scope)
                "MEMBERSHIP_WATCHLIST" -> if (present) api.addToWatchlist(command.targetContentId, authority.scope)
                    else api.removeFromWatchlist(command.targetContentId, authority.scope)
                else -> error("Invalid membership operation")
            }
            if (response is ApiResult.Success) {
                resolved = dao.resolveMembership(id, claim, authority.key, SENDING) == 1
                if (resolved) return Result(Resolution.ACKNOWLEDGED,
                    dao.getLatestByCoalesceKey(command.coalesceKey) == null && authority.scope == tokens.snapshotCurrentScope())
            }
            return Result(Resolution.NEEDS_RECONCILIATION)
        } finally {
            // Claim commit can precede cancellation of its suspended return. Always attempt
            // exact-token cleanup, even when admission never returned to this coroutine.
            // A lost claim is a harmless CAS miss; it cannot affect the winning sender.
            if (!resolved) withContext(NonCancellable) {
                dao.transitionMembership(id, claim, authority.key, SENDING, RECONCILE)
            }
        }
    }

    /** Explicit read-only recovery; a mismatch pauses for a new user command, never resends. */
    suspend fun reconcile(id: Long, authority: Authority): Result {
        val command = dao.getById(id) ?: return Result(Resolution.NOT_CLAIMED)
        if (command.membershipAuthority != authority.key || command.serverId != authority.scope.serverId ||
            command.profileId != authority.scope.profileId.orEmpty() || command.state !in listOf(RECONCILE, PAUSED) ||
            authority.scope != tokens.snapshotCurrentScope()) return Result(Resolution.NOT_CLAIMED)
        val claim = command.membershipClaim ?: return Result(Resolution.NOT_CLAIMED)
        val response = when (command.opKind) {
            "MEMBERSHIP_FAVORITE" -> api.favorite(command.targetContentId, authority.scope)
            "MEMBERSHIP_WATCHLIST" -> api.watchlist(command.targetContentId, authority.scope)
            else -> return Result(Resolution.PAUSED)
        }
        if (authority.scope != tokens.snapshotCurrentScope()) return Result(Resolution.NEEDS_RECONCILIATION)
        if (response !is ApiResult.Success) return Result(Resolution.NEEDS_RECONCILIATION)
        if ((response.data != null) == command.payloadJson.toBooleanStrict()) {
            val removed = dao.resolveMembership(id, claim, authority.key, command.state) == 1
            // Observed state is not an acknowledgement that mutation side effects were replayed.
            return Result(if (removed) Resolution.RECONCILED else Resolution.NOT_CLAIMED)
        }
        dao.transitionMembership(id, claim, authority.key, command.state, PAUSED)
        return Result(Resolution.PAUSED)
    }

    companion object {
        const val READY = "membership_ready"
        const val SENDING = "membership_sending"
        const val RECONCILE = "membership_reconcile"
        const val PAUSED = "membership_paused"
    }
}
