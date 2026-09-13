package org.siloserver.silo.common.data.sync

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.dao.DirtyOperationDao
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.MembershipV2Api
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class MembershipOutboxTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "membership-${java.util.UUID.randomUUID()}"
    private var db = Room.databaseBuilder(context, SiloDatabase::class.java, name).allowMainThreadQueries().build()
    private val dao get() = db.dirtyOperationDao()
    private var scope = AuthScopeSnapshot("s", "p", "https://example.invalid", null, identityGeneration = 1)
    private val original = scope
    private val authority = MembershipOutbox.Authority("persisted-login-uuid", original)
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = scope
    }
    private val clients = mutableListOf<HttpClient>()
    private fun outbox(engine: MockEngine, owner: String = "process-one"): MembershipOutbox {
        val client = HttpClient(engine).also { clients += it }
        return MembershipOutbox(dao, MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens), tokens, owner)
    }
    @AfterTest fun close() { clients.forEach { it.close() }; db.close(); context.deleteDatabase(name) }

    @Test fun competingSendersClaimOnceAndIdenticalNewIntentSurvivesOldAck() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var sends = 0
        val box = outbox(MockEngine { sends++; entered.complete(Unit); release.await(); respond("", HttpStatusCode.NoContent) })
        val first = box.enqueue(authority, "item", MembershipOutbox.ListKind.FAVORITE, true)
        val pending = async { box.send(first, authority) }; entered.await()
        assertEquals(MembershipOutbox.Resolution.NOT_CLAIMED, box.send(first, authority).resolution)
        val newer = box.enqueue(authority, "item", MembershipOutbox.ListKind.FAVORITE, true)
        assertNotEquals(first, newer)
        assertEquals(MembershipOutbox.Resolution.NOT_CLAIMED, box.send(newer, authority).resolution)
        release.complete(Unit)
        val result = pending.await()
        assertEquals(MembershipOutbox.Resolution.ACKNOWLEDGED, result.resolution)
        assertFalse(result.mayPublish)
        assertNull(dao.getById(first)); assertNotNull(dao.getById(newer)); assertEquals(1, sends)
        assertTrue(dao.dueBatch("s", "p", Long.MAX_VALUE, 100).isEmpty())
    }

    @Test fun oldScopeAckRemovesOnlyClaimAndCannotPublish() = runTest {
        val box = outbox(MockEngine { scope = scope.copy(profileId = "other", identityGeneration = 2); respond("", HttpStatusCode.NoContent) })
        val id = box.enqueue(authority, "item", MembershipOutbox.ListKind.WATCHLIST, false)
        val result = box.send(id, authority)
        assertEquals(MembershipOutbox.Resolution.ACKNOWLEDGED, result.resolution)
        assertFalse(result.mayPublish); assertNull(dao.getById(id))
    }

    @Test fun uncertainWriteNeverResendsAndExplicitReadReconciles() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val box = outbox(MockEngine { request ->
            methods += request.method
            if (request.method == HttpMethod.Get) respond("", HttpStatusCode.NotFound)
            else respond("", HttpStatusCode.ServiceUnavailable)
        })
        val id = box.enqueue(authority, "item", MembershipOutbox.ListKind.WATCHLIST, false)
        assertEquals(MembershipOutbox.Resolution.NEEDS_RECONCILIATION, box.send(id, authority).resolution)
        assertEquals(MembershipOutbox.Resolution.NOT_CLAIMED, box.send(id, authority).resolution)
        assertEquals(MembershipOutbox.Resolution.RECONCILED, box.reconcile(id, authority).resolution)
        assertEquals(listOf(HttpMethod.Delete, HttpMethod.Get), methods); assertNull(dao.getById(id))
    }

    @Test fun abandonedClaimSurvivesRestartAsReconciliationAndMismatchPauses() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val box = outbox(engine)
        val id = box.enqueue(authority, "item", MembershipOutbox.ListKind.FAVORITE, true)
        assertEquals(1, dao.claimMembership(id, authority.key, "old-claim", "process-one"))
        assertEquals(0, box.recover()) // A concurrent drain in this process cannot steal a live claim.
        db.close()
        db = Room.databaseBuilder(context, SiloDatabase::class.java, name).allowMainThreadQueries().build()
        val restarted = outbox(MockEngine { respond("", HttpStatusCode.NotFound) }, "process-two")
        assertEquals(1, restarted.recover())
        dao.resetInFlightToPending("s", "p")
        assertEquals(MembershipOutbox.RECONCILE, dao.getById(id)?.state)
        assertEquals(0, dao.resolveMembership(id, "wrong-claim", authority.key, MembershipOutbox.RECONCILE))
        assertEquals(MembershipOutbox.Resolution.NOT_CLAIMED, restarted.reconcile(id, authority.copy(key = "different-login")).resolution)
        assertEquals(MembershipOutbox.Resolution.PAUSED, restarted.reconcile(id, authority).resolution)
        assertEquals(MembershipOutbox.PAUSED, dao.getById(id)?.state)
        assertEquals(MembershipOutbox.Resolution.NOT_CLAIMED, restarted.send(id, authority).resolution)
    }

    @Test fun cancellationLeavesReadRecoveryAndNeverPendingRetry() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val box = outbox(MockEngine { entered.complete(Unit); release.await(); respond("", HttpStatusCode.NoContent) })
        val id = box.enqueue(authority, "item", MembershipOutbox.ListKind.FAVORITE, true)
        val pending = async { box.send(id, authority) }; entered.await(); pending.cancel(); pending.join()
        assertEquals(MembershipOutbox.RECONCILE, dao.getById(id)?.state)
        assertEquals(0, dao.claim(id))
    }

    @Test fun delayedReconciliationFromOldViewerCannotResolveCommand() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val box = outbox(MockEngine { request ->
            calls++
            if (request.method == HttpMethod.Get) { entered.complete(Unit); release.await(); respond("", HttpStatusCode.NotFound) }
            else respond("", HttpStatusCode.ServiceUnavailable)
        })
        val id = box.enqueue(authority, "item", MembershipOutbox.ListKind.FAVORITE, false)
        box.send(id, authority)
        val read = async { box.reconcile(id, authority) }; entered.await()
        scope = scope.copy(profileId = "other", identityGeneration = 2)
        release.complete(Unit)
        assertEquals(MembershipOutbox.Resolution.NEEDS_RECONCILIATION, read.await().resolution)
        assertEquals(MembershipOutbox.RECONCILE, dao.getById(id)?.state)
        assertEquals(MembershipOutbox.Resolution.NOT_CLAIMED, box.reconcile(id, authority).resolution)
        assertEquals(2, calls)
    }

    @Test fun failedReconciliationKeepsCommandWithoutSendingAgain() = runTest {
        var calls = 0
        val box = outbox(MockEngine { calls++; respond("", HttpStatusCode.Forbidden) })
        val id = box.enqueue(authority, "item", MembershipOutbox.ListKind.WATCHLIST, true)
        box.send(id, authority)
        assertEquals(MembershipOutbox.Resolution.NEEDS_RECONCILIATION, box.reconcile(id, authority).resolution)
        assertEquals(MembershipOutbox.RECONCILE, dao.getById(id)?.state)
        assertEquals(MembershipOutbox.Resolution.NOT_CLAIMED, box.send(id, authority).resolution)
        assertEquals(2, calls)
    }

    @Test fun cancellationAfterClaimCommitBeforeReturnReconcilesExactCommand() = runTest {
        val committed = CompletableDeferred<Unit>()
        val realDao = dao
        var reads = 0
        val controlledDao = object : DirtyOperationDao by realDao {
            override suspend fun claimMembership(id: Long, authority: String, claim: String, owner: String): Int {
                assertEquals(1, realDao.claimMembership(id, authority, claim, owner))
                committed.complete(Unit)
                awaitCancellation()
            }
            override suspend fun getById(id: Long): DirtyOperationEntity? {
                reads++
                return realDao.getById(id)
            }
        }
        var sends = 0
        val client = HttpClient(MockEngine { sends++; respond("", HttpStatusCode.NoContent) }).also { clients += it }
        val box = MembershipOutbox(controlledDao, MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens), tokens, "process-one")
        val id = box.enqueue(authority, "item", MembershipOutbox.ListKind.FAVORITE, true)
        val pending = async { box.send(id, authority) }; committed.await()
        assertEquals(MembershipOutbox.SENDING, realDao.getById(id)?.state)
        val newer = box.enqueue(authority, "item", MembershipOutbox.ListKind.FAVORITE, true)
        pending.cancel(); pending.join()
        assertEquals(0, reads); assertEquals(0, sends)
        assertEquals(MembershipOutbox.RECONCILE, realDao.getById(id)?.state)
        assertEquals(MembershipOutbox.READY, realDao.getById(newer)?.state)
        assertEquals(listOf(id, newer), realDao.membershipCommands(authority.key, 10).map { it.id })
        assertEquals(0, box.recover())
    }

    @Test fun cancellationDuringClaimedRowReadAlsoReconcilesWithoutRestart() = runTest {
        val reading = CompletableDeferred<Unit>()
        val realDao = dao
        val controlledDao = object : DirtyOperationDao by realDao {
            override suspend fun getById(id: Long): DirtyOperationEntity? {
                assertEquals(MembershipOutbox.SENDING, realDao.getById(id)?.state)
                reading.complete(Unit)
                awaitCancellation()
            }
        }
        val client = HttpClient(MockEngine { error("No mutation may start") }).also { clients += it }
        val box = MembershipOutbox(controlledDao, MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens), tokens, "process-one")
        val id = box.enqueue(authority, "item", MembershipOutbox.ListKind.FAVORITE, true)
        val pending = async { box.send(id, authority) }; reading.await()
        pending.cancel(); pending.join()
        assertEquals(MembershipOutbox.RECONCILE, realDao.getById(id)?.state)
        assertEquals(listOf(id), realDao.membershipCommands(authority.key, 10).map { it.id })
    }

    @Test fun singletonRuntimeRecoversReopenedDatabaseBeforeConcurrentEntries() = runTest {
        val old = outbox(MockEngine { error("No send") })
        val id = old.enqueue(authority, "item", MembershipOutbox.ListKind.FAVORITE, true)
        dao.claimMembership(id, authority.key, "old-claim", "dead-process")
        db.close()
        db = Room.databaseBuilder(context, SiloDatabase::class.java, name).allowMainThreadQueries().build()
        val realDao = dao
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var recoveries = 0
        val controlled = object : DirtyOperationDao by realDao {
            override suspend fun recoverMembership(owner: String): Int {
                recoveries++; entered.complete(Unit); release.await()
                return realDao.recoverMembership(owner)
            }
        }
        val login = DurableLoginAuthority("login", scope)
        val provider = object : DurableLoginAuthorityProvider {
            override suspend fun snapshotDurableLoginAuthority() = login
        }
        val client = HttpClient(MockEngine { error("Recovery must never send") }).also { clients += it }
        val runtime = MembershipRuntime(controlled, MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens), tokens, provider)
        val first = async { runtime.captureAuthority() }; entered.await()
        val second = async { runtime.send(id, login) }
        assertEquals(MembershipOutbox.SENDING, realDao.getById(id)?.state)
        assertFalse(first.isCompleted); assertFalse(second.isCompleted)
        release.complete(Unit)
        assertEquals(login, first.await())
        assertEquals(MembershipOutbox.Resolution.NOT_CLAIMED, second.await().resolution)
        assertEquals(1, recoveries)
        assertEquals(MembershipOutbox.RECONCILE, realDao.getById(id)?.state)
    }

    @Test fun cancelledOrFailedRuntimeRecoveryCanRetryBeforeAdmission() = runTest {
        val realDao = dao
        val committed = CompletableDeferred<Unit>()
        var recoveries = 0
        val controlled = object : DirtyOperationDao by realDao {
            override suspend fun recoverMembership(owner: String): Int {
                recoveries++
                if (recoveries == 1) error("Storage unavailable")
                val result = realDao.recoverMembership(owner)
                if (recoveries == 2) { committed.complete(Unit); awaitCancellation() }
                return result
            }
        }
        val provider = object : DurableLoginAuthorityProvider {
            override suspend fun snapshotDurableLoginAuthority() = DurableLoginAuthority("login", scope)
        }
        val client = HttpClient(MockEngine { error("No mutation") }).also { clients += it }
        val runtime = MembershipRuntime(controlled, MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens), tokens, provider)
        assertFailsWith<IllegalStateException> { runtime.captureAuthority() }
        val cancelled = async { runtime.captureAuthority() }; committed.await(); cancelled.cancel(); cancelled.join()
        assertNotNull(runtime.captureAuthority())
        assertNotNull(runtime.captureAuthority())
        assertEquals(3, recoveries)
    }

    @Test fun runtimeSeparatesProfilesAndRejectsStaleLoginCallbacks() = runTest {
        var loginId = "login-one"
        val provider = object : DurableLoginAuthorityProvider {
            override suspend fun snapshotDurableLoginAuthority() = DurableLoginAuthority(loginId, scope)
        }
        val client = HttpClient(MockEngine { error("Stale callbacks cannot send") }).also { clients += it }
        val runtime = MembershipRuntime(dao, MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens), tokens, provider)
        val first = assertNotNull(runtime.captureAuthority())
        val a = runtime.enqueue(first, "item", MembershipOutbox.ListKind.FAVORITE, true)
        scope = scope.copy(profileId = "p2", identityGeneration = 2)
        val b = runtime.enqueue(assertNotNull(runtime.captureAuthority()), "item", MembershipOutbox.ListKind.FAVORITE, true)
        assertNotEquals(dao.getById(a)?.membershipAuthority, dao.getById(b)?.membershipAuthority)
        assertNotNull(dao.getById(a))
        scope = first.scope // Even matching runtime counters cannot bless a different persisted login.
        loginId = "login-two"
        assertEquals(MembershipOutbox.Resolution.NOT_CLAIMED, runtime.send(a, first).resolution)
        assertFailsWith<IllegalStateException> { runtime.enqueue(first, "item", MembershipOutbox.ListKind.FAVORITE, false) }
        assertEquals(2, dao.count())
    }
}
