package org.siloserver.silo.common.data.sync

import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import org.siloserver.silo.common.data.db.dao.DirtyOperationDao
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.MembershipV2Api

/** Owned by the single Room membership port used by inline and worker entry points. */
class MembershipRuntime(
    private val dao: DirtyOperationDao,
    api: MembershipV2Api,
    tokens: TokenManager,
    private val authorities: DurableLoginAuthorityProvider,
) {
    constructor(
        db: org.siloserver.silo.common.data.db.SiloDatabase,
        api: MembershipV2Api,
        tokens: TokenManager,
        authorities: DurableLoginAuthorityProvider,
    ) : this(db.dirtyOperationDao(), api, tokens, authorities)

    private val outbox = MembershipOutbox(dao, api, tokens, UUID.randomUUID().toString())
    private val initialization = Mutex()
    private var ready = false

    private suspend fun awaitRecovery() = initialization.withLock {
        if (!ready) {
            outbox.recover()
            currentCoroutineContext().ensureActive()
            ready = true
        }
    }

    suspend fun captureAuthority(): DurableLoginAuthority? {
        awaitRecovery()
        return authorities.snapshotDurableLoginAuthority()
    }

    suspend fun enqueue(authority: DurableLoginAuthority, itemId: String, kind: MembershipOutbox.ListKind, present: Boolean): Long {
        awaitRecovery()
        check(authority == authorities.snapshotDurableLoginAuthority()) { "The login authority changed" }
        return outbox.enqueue(authority.commandAuthority(), itemId, kind, present)
    }

    /** No nested token-store capture inside the barrier: that store shares the same lock. */
    internal suspend fun enqueueGuarded(authority: DurableLoginAuthority, itemId: String,
        kind: MembershipOutbox.ListKind, present: Boolean, barrier: IdentityTransitionBarrier): Long {
        awaitRecovery()
        check(authority == authorities.snapshotDurableLoginAuthority()) { "The login authority changed" }
        return checkNotNull(barrier.withCurrentGeneration(authority.scope.identityGeneration) {
            outbox.enqueueCaptured(authority.commandAuthority(), itemId, kind, present)
        }) { "The login authority changed" }
    }

    suspend fun send(id: Long, authority: DurableLoginAuthority): MembershipOutbox.Result {
        awaitRecovery()
        if (authority != authorities.snapshotDurableLoginAuthority()) return MembershipOutbox.Result(MembershipOutbox.Resolution.NOT_CLAIMED)
        return outbox.send(id, authority.commandAuthority())
    }

    suspend fun reconcile(id: Long, authority: DurableLoginAuthority): MembershipOutbox.Result {
        awaitRecovery()
        if (authority != authorities.snapshotDurableLoginAuthority()) return MembershipOutbox.Result(MembershipOutbox.Resolution.NOT_CLAIMED)
        return outbox.reconcile(id, authority.commandAuthority())
    }

    /** Bounded READY-only selection. Uncertain/paused rows never trigger mutation retries. */
    suspend fun readyCommands(authority: DurableLoginAuthority, limit: Int): List<Long> {
        require(limit in 1..100)
        awaitRecovery()
        if (authority != authorities.snapshotDurableLoginAuthority()) return emptyList()
        return dao.readyMembershipCommands(authority.commandAuthority().key, limit).map { it.id }
    }

    internal fun DurableLoginAuthority.commandAuthority(): MembershipOutbox.Authority {
        require(loginId.isNotBlank() && !scope.profileId.isNullOrBlank() && scope.credentialGenerationId == null)
        return MembershipOutbox.Authority(SiloJson.encodeToString(listOf(scope.serverId, loginId, scope.profileId)), scope)
    }
}
