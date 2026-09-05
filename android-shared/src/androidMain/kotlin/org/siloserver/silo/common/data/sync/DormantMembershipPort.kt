package org.siloserver.silo.common.data.sync

import androidx.room.withTransaction
import kotlinx.serialization.encodeToString
import org.siloserver.silo.common.data.db.DormantMembershipDatabase
import org.siloserver.silo.common.data.db.dao.DirtyOperationDao
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.common.data.db.entity.MembershipProjectionEntity
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.MembershipV2Api
import org.siloserver.silo.repository.port.MembershipPort

/** Not bound in either app. One instance must own future inline and background dispatch. */
class DormantMembershipPort(
    private val db: DormantMembershipDatabase,
    api: MembershipV2Api,
    tokens: TokenManager,
    authorities: DurableLoginAuthorityProvider,
    private val identityTransitions: IdentityTransitionBarrier,
) : MembershipPort {
    private val projections = db.membershipProjectionDao()
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

    override suspend fun captureAuthority() = runtime.captureAuthority()

    override suspend fun record(authority: DurableLoginAuthority, itemId: String,
        kind: MembershipPort.Kind, present: Boolean): MembershipPort.Command {
        val id = runtime.enqueueGuarded(authority, itemId, MembershipOutbox.ListKind.valueOf(kind.name), present, identityTransitions)
        return MembershipPort.Command(id, authority, itemId, kind)
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
    suspend fun dispatch(limit: Int = 25): List<MembershipPort.Completion> {
        val authority = captureAuthority() ?: return emptyList()
        val ids = runtime.readyCommands(authority, limit)
        val results = mutableListOf<MembershipPort.Completion>()
        for (id in ids) {
            if (authority != captureAuthority()) break
            val row = queue.getById(id) ?: continue
            results += send(MembershipPort.Command(id, authority, row.targetContentId,
                MembershipPort.Kind.valueOf(row.opKind.removePrefix("MEMBERSHIP_"))))
        }
        return results
    }

    private fun DurableLoginAuthority.key() = SiloJson.encodeToString(listOf(scope.serverId, loginId, scope.profileId))
}
