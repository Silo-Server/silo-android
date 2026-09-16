package org.siloserver.silo.common.data.sync

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.dao.DirtyOperationDao
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.common.data.db.entity.MembershipProjectionEntity
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.MembershipV2Api
import org.siloserver.silo.repository.port.MembershipPort

/** One process instance owns inline and background dispatch with transactional projections. */
class RoomMembershipPort(
    private val db: SiloDatabase,
    private val api: MembershipV2Api,
    tokens: TokenManager,
    authorities: DurableLoginAuthorityProvider,
    private val identityTransitions: IdentityTransitionBarrier,
    private val scheduler: OutboxSyncScheduler = OutboxSyncScheduler.NONE,
) : MembershipPort {
    private val projections = db.membershipProjectionDao()
    override val changes = projections.observeChanges().map { Unit }
    private val queue = db.dirtyOperationDao()
    private val transactionalQueue = object : DirtyOperationDao by queue {
        override suspend fun enqueueMembership(op: DirtyOperationEntity): Long = db.withTransaction {
            val id = queue.enqueueMembership(op)
            projections.put(MembershipProjectionEntity(requireNotNull(op.membershipAuthority),
                op.targetContentId, op.opKind.removePrefix("MEMBERSHIP_"), id,
                op.payloadJson.toBooleanStrict(), null))
            id
        }

        override suspend fun resolveMembership(id: Long, claim: String, authority: String, state: String): Int =
            db.withTransaction {
                val resolved = queue.resolveMembership(id, claim, authority, state)
                if (resolved == 1) projections.resolve(authority, id,
                    if (state == MembershipOutbox.SENDING) "ACKNOWLEDGED" else "RECONCILED")
                resolved
            }

        override suspend fun transitionMembership(id: Long, claim: String, authority: String,
            expectedState: String, state: String): Int = db.withTransaction {
            val changed = queue.transitionMembership(id, claim, authority, expectedState, state)
            if (changed == 1) projections.resolve(authority, id,
                if (state == MembershipOutbox.PAUSED) "PAUSED" else "NEEDS_RECONCILIATION")
            changed
        }


    }
    private val runtime = MembershipRuntime(transactionalQueue, api, tokens, authorities)

    override suspend fun read(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind): ApiResult<Boolean> {
        if (authority != runtime.captureAuthority()) return ApiResult.Error(0, "identity_changed", "The viewer changed")
        val result = if (kind == MembershipPort.Kind.FAVORITE) api.favorite(itemId, authority.scope)
            else api.watchlist(itemId, authority.scope)
        if (authority != runtime.captureAuthority()) return ApiResult.Error(0, "identity_changed", "The viewer changed")
        return result.map { it != null }
    }

    override suspend fun pending(authority: DurableLoginAuthority, limit: Int): List<MembershipPort.Command> {
        require(limit in 1..100)
        if (authority != runtime.captureAuthority()) return emptyList()
        val rows = projections.pending(authority.key(), limit)
        if (authority != runtime.captureAuthority()) return emptyList()
        return rows.map { MembershipPort.Command(it.id, authority, it.targetContentId,
            MembershipPort.Kind.valueOf(it.opKind.removePrefix("MEMBERSHIP_")), it.payloadJson.toBooleanStrict()) }
    }

    override suspend fun readyCount(): Int {
        val authority = captureAuthority() ?: return 0
        return queue.readyMembershipCount(authority.key())
    }

    override suspend fun hasLegacyQuarantine(): Boolean = queue.quarantinedMembershipCount() > 0

    override suspend fun captureAuthority() = runtime.captureAuthority()

    override suspend fun record(authority: DurableLoginAuthority, itemId: String,
        kind: MembershipPort.Kind, present: Boolean): MembershipPort.Command {
        try {
            val id = runtime.enqueueGuarded(authority, itemId, MembershipOutbox.ListKind.valueOf(kind.name), present, identityTransitions)
            return MembershipPort.Command(id, authority, itemId, kind, present)
        } finally {
            // The transaction may commit just before its cancelled return. Scheduling is
            // harmless without work; READY commands still compete through the same claim.
            scheduler.requestSync()
        }
    }

    override suspend fun send(command: MembershipPort.Command): MembershipPort.Completion =
        completion(command, runtime.send(command.id, command.authority))

    override suspend fun reconcile(command: MembershipPort.Command): MembershipPort.Completion =
        completion(command, runtime.reconcile(command.id, command.authority))

    private suspend fun completion(command: MembershipPort.Command, result: MembershipOutbox.Result): MembershipPort.Completion {
        val current = projection(command.authority, command.itemId, command.kind)
        return MembershipPort.Completion(MembershipPort.Disposition.valueOf(result.resolution.name),
            result.resolution in listOf(MembershipOutbox.Resolution.ACKNOWLEDGED, MembershipOutbox.Resolution.RECONCILED) &&
                current?.commandId == command.id && current.disposition in listOf(
                MembershipPort.Disposition.ACKNOWLEDGED, MembershipPort.Disposition.RECONCILED))
    }

    override suspend fun projection(authority: DurableLoginAuthority, itemId: String,
        kind: MembershipPort.Kind): MembershipPort.Projection? {
        if (authority != runtime.captureAuthority()) return null
        val projection = projections.get(authority.key(), itemId, kind.name) ?: return null
        val command = queue.getById(projection.commandId)
        val disposition = when (command?.state) {
            MembershipOutbox.RECONCILE -> MembershipPort.Disposition.NEEDS_RECONCILIATION
            MembershipOutbox.PAUSED -> MembershipPort.Disposition.PAUSED
            else -> projection.disposition?.let(MembershipPort.Disposition::valueOf)
        }
        if (authority != runtime.captureAuthority()) return null
        return MembershipPort.Projection(projection.commandId, projection.present, disposition)
    }

    /** A single bounded pass; never loops over uncertain work or schedules automatic writes. */
    override suspend fun dispatch(limit: Int): List<MembershipPort.Completion> {
        val authority = captureAuthority() ?: return emptyList()
        val ids = runtime.readyCommands(authority, limit)
        val results = mutableListOf<MembershipPort.Completion>()
        for (id in ids) {
            if (authority != captureAuthority()) break
            val row = queue.getById(id) ?: continue
            results += send(MembershipPort.Command(id, authority, row.targetContentId,
                MembershipPort.Kind.valueOf(row.opKind.removePrefix("MEMBERSHIP_")), row.payloadJson.toBooleanStrict()))
        }
        return results
    }

    private fun DurableLoginAuthority.key() = SiloJson.encodeToString(listOf(scope.serverId, loginId, scope.profileId))
}
