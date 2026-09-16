package org.siloserver.silo.common.data.sync

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.common.data.db.*
import org.siloserver.silo.common.data.db.entity.*
import org.siloserver.silo.common.data.repository.RoomUserItemStateRepository
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.MembershipV2Api
import org.siloserver.silo.repository.port.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class DormantMembershipTest {
    @get:org.junit.Rule
    val migrationHelper = androidx.room.testing.MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(), SiloDatabase::class.java)

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "dormant-${java.util.UUID.randomUUID()}"
    private var db: SiloDatabase? = null
    private val clients = mutableListOf<HttpClient>()
    private var authority = DurableLoginAuthority("login-one", AuthScopeSnapshot("s", "p", "https://example.invalid", null, identityGeneration = 0))
    private val original = authority
    private val barrier = DefaultIdentityTransitionBarrier()
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = authority.scope
    }
    private val authorities = object : DurableLoginAuthorityProvider {
        override suspend fun snapshotDurableLoginAuthority() = authority
    }
    private fun open(): SiloDatabase = Room.databaseBuilder(context,
        SiloDatabase::class.java, name)
        .addMigrations(SiloDatabase.MIGRATION_9_10)
        .addCallback(SiloDatabase.CALLBACK).allowMainThreadQueries().build().also { db = it }
    private fun port(engine: MockEngine): RoomMembershipPort {
        val client = HttpClient(engine).also { clients += it }
        return RoomMembershipPort(requireNotNull(db), MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens), tokens, authorities, barrier)
    }
    @AfterTest fun close() { clients.forEach { it.close() }; db?.close(); context.deleteDatabase(name) }

    @Test fun migrationRetainsEveryLegacyFieldAndExcludesEveryGenericCleanupPath() = runTest {
        val legacy = migrationHelper.createDatabase(name, 9)
        val rows = listOf("pending", "in_flight", "custom_old_state").mapIndexed { index, state ->
            DirtyOperationEntity(opKind = "SET_FAVORITE", serverId = "s", profileId = "p",
                targetContentId = "item", targetFileId = null, coalesceKey = "legacy-$index",
                idempotencyKey = "key-$index", payloadJson = "true", state = state,
                createdAtMs = 12, attemptCount = 4, lastAttemptAtMs = 13, nextAttemptAtMs = 500,
                lastError = "old-error", opVersion = 2)
        }.mapIndexed { index, row -> row.copy(id = index.toLong() + 1) }
        rows.forEach { row ->
            legacy.execSQL("INSERT INTO dirty_operations (id,opKind,serverId,profileId,targetContentId,targetFileId,coalesceKey,idempotencyKey,opVersion,payloadJson,state,createdAtMs,attemptCount,lastAttemptAtMs,nextAttemptAtMs,lastError,membershipAuthority,membershipClaim,membershipOwner) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(row.id,row.opKind,row.serverId,row.profileId,row.targetContentId,row.targetFileId,row.coalesceKey,row.idempotencyKey,row.opVersion,row.payloadJson,row.state,row.createdAtMs,row.attemptCount,row.lastAttemptAtMs,row.nextAttemptAtMs,row.lastError,row.membershipAuthority,row.membershipClaim,row.membershipOwner))
        }
        legacy.execSQL("INSERT INTO content_item_state (serverId,profileId,contentId,watched,ratingValue,favorite,clientUpdatedAtMs,serverUpdatedAtMs) VALUES ('s','p','item',NULL,NULL,1,12,NULL)")
        legacy.close()
        val future = open()
        val dao = future.dirtyOperationDao()
        val repository = RoomUserItemStateRepository(future, { authority.scope })
        for (row in rows) {
            assertEquals(row.copy(state = "legacy_membership_quarantined"), dao.getById(row.id))
            future.openHelper.writableDatabase.query("SELECT originalState FROM legacy_membership_quarantine WHERE commandId = ${row.id}").use {
                assertTrue(it.moveToFirst()); assertEquals(row.state, it.getString(0))
            }
            assertEquals(0, dao.claim(row.id))
            dao.deleteById(row.id)
            dao.recordFailure(row.id, 99, 100, "new-error")
            dao.supersedeOrRecordFailure(row.id, row.coalesceKey, 99, 100, "new-error")
            dao.deletePendingByCoalesceKey(row.coalesceKey)
            repository.resolve(OutboxHandle(row.id, authority.scope), WriteOutcome.SYNCED)
            repository.resolve(OutboxHandle(row.id, authority.scope), WriteOutcome.TERMINAL)
            assertNull(dao.getLatestByCoalesceKey(row.coalesceKey))
            assertEquals(row.copy(state = "legacy_membership_quarantined"), dao.getById(row.id))
        }
        dao.resetInFlightToPending("s", "p")
        dao.deleteSupersededInFlight("s", "p")
        dao.deletePendingForTargetKind("s", "p", "item", "SET_FAVORITE")
        dao.clearBackoffForTargetContent("s", "p", "item", 0)
        assertEquals(0, dao.count())
        assertEquals(0, dao.countForScope("s", "p"))
        assertTrue(dao.dueBatch("s", "p", Long.MAX_VALUE, 10).isEmpty())
        assertEquals(true, future.contentItemStateDao().get("s", "p", "item")?.favorite)
        // Quarantine cannot block unrelated item FIFO, even when its IDs are older.
        val unrelated = dao.insert(rows.first().copy(id = 0, opKind = "SET_EBOOK_PROGRESS", state = "pending",
            coalesceKey = "ebook", idempotencyKey = "ebook", nextAttemptAtMs = 0))
        assertEquals(listOf(unrelated), dao.dueTargetHeads("s", "p", Long.MAX_VALUE, 10).map { it.id })
        // Old producer must abort its whole projection+enqueue transaction after eventual cutover.
        assertFailsWith<IllegalStateException> { repository.recordFavorite("item", false) }
        assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
            future.withTransaction {
                future.contentItemStateDao().upsert(requireNotNull(future.contentItemStateDao().get("s", "p", "item")).copy(favorite = false))
                dao.insert(rows.first().copy(id = 0, idempotencyKey = "obsolete-producer"))
            }
        }
        assertEquals(true, future.contentItemStateDao().get("s", "p", "item")?.favorite)
        rows.forEach { assertEquals(it.copy(state = "legacy_membership_quarantined"), dao.getById(it.id)) }
    }

    @Test fun identityChangeWaitsForAdmissionTransactionAndNewLoginOwnsItsProjection() = runTest {
        val future = open()
        val port = port(MockEngine { error("No transport during admission") })
        port.captureAuthority()
        val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val admissionEntered = CompletableDeferred<Unit>()
        // The real barrier is held while the Room transaction waits for its lock.
        val observer = object : IdentityTransitionBarrier by barrier {
            override suspend fun <T : Any> withCurrentGeneration(expectedGeneration: Long, block: suspend () -> T): T? =
                barrier.withCurrentGeneration(expectedGeneration) {
                    admissionEntered.complete(Unit)
                    block()
                }
        }
        val client = HttpClient(MockEngine { error("No writes") }).also { clients += it }
        val guarded = RoomMembershipPort(future, MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens), tokens, authorities, observer)
        guarded.captureAuthority()
        val blocker = async { future.withTransaction { locked.complete(Unit); release.await() } }
        locked.await()
        val oldAdmission = async { guarded.record(original, "item", MembershipPort.Kind.FAVORITE, true) }
        admissionEntered.await()
        val switching = async { barrier.changing(IdentityTransitionKind.ACCOUNT_REPLACE) {
            authority = original.copy(loginId = "login-two", scope = original.scope.copy(identityGeneration = 1))
        } }
        assertFalse(switching.isCompleted)
        release.complete(Unit); blocker.await()
        val oldCommand = oldAdmission.await(); switching.await()
        val newer = guarded.record(authority, "item", MembershipPort.Kind.FAVORITE, false)
        assertNotEquals(oldCommand.id, newer.id)
        assertNull(guarded.projection(original, "item", MembershipPort.Kind.FAVORITE))
        assertEquals(newer.id, guarded.projection(authority, "item", MembershipPort.Kind.FAVORITE)?.commandId)
        assertEquals(false, guarded.projection(authority, "item", MembershipPort.Kind.FAVORITE)?.present)
        assertEquals(MembershipPort.Disposition.NOT_CLAIMED, guarded.send(oldCommand).disposition)
        assertFailsWith<IllegalStateException> { guarded.record(original, "item", MembershipPort.Kind.FAVORITE, true) }
    }

    @Test fun inlineAndBoundedDispatcherShareClaimAndOldAckCannotResolveIdenticalNewProjection() = runTest {
        open()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var sends = 0
        val port = port(MockEngine { sends++; entered.complete(Unit); release.await(); respond("", HttpStatusCode.NoContent) })
        val old = port.record(authority, "item", MembershipPort.Kind.FAVORITE, true)
        val inline = async { port.send(old) }; entered.await()
        assertTrue(port.dispatch().isEmpty())
        val newer = port.record(authority, "item", MembershipPort.Kind.FAVORITE, true)
        assertEquals(MembershipPort.Disposition.NOT_CLAIMED, port.dispatch().single().disposition)
        release.complete(Unit)
        assertFalse(inline.await().mayPublish)
        val projection = port.projection(authority, "item", MembershipPort.Kind.FAVORITE)
        assertEquals(newer.id, projection?.commandId); assertNull(projection?.disposition)
        assertEquals(1, sends)
        assertEquals(MembershipPort.Disposition.ACKNOWLEDGED, port.dispatch(1).single().disposition)
        assertEquals(MembershipPort.Disposition.ACKNOWLEDGED,
            port.projection(authority, "item", MembershipPort.Kind.FAVORITE)?.disposition)
    }

    @Test fun uncertainCommandsRequireExplicitGetAndNeverDispatchAgain() = runTest {
        open()
        val methods = mutableListOf<HttpMethod>()
        val port = port(MockEngine {
            methods += it.method
            respond("", if (it.method == HttpMethod.Get) HttpStatusCode.NotFound else HttpStatusCode.ServiceUnavailable)
        })
        val command = port.record(authority, "item", MembershipPort.Kind.WATCHLIST, false)
        assertEquals(MembershipPort.Disposition.NEEDS_RECONCILIATION, port.dispatch(1).single().disposition)
        assertTrue(port.dispatch().isEmpty())
        assertEquals(MembershipPort.Disposition.NEEDS_RECONCILIATION,
            port.projection(authority, "item", MembershipPort.Kind.WATCHLIST)?.disposition)
        assertEquals(MembershipPort.Disposition.RECONCILED, port.reconcile(command).disposition)
        assertEquals(MembershipPort.Disposition.RECONCILED,
            port.projection(authority, "item", MembershipPort.Kind.WATCHLIST)?.disposition)
        assertEquals(listOf(HttpMethod.Delete, HttpMethod.Get), methods)
    }
    @Test fun restartProjectsAbandonedClaimAsReadRecoveryAndMismatchPauses() = runTest {
        var future = open()
        val first = port(MockEngine { error("No initial transport") })
        val command = first.record(authority, "item", MembershipPort.Kind.FAVORITE, true)
        val row = requireNotNull(future.dirtyOperationDao().getById(command.id))
        future.dirtyOperationDao().claimMembership(command.id, requireNotNull(row.membershipAuthority), "claim", "dead-process")
        future.close(); future = open()
        val methods = mutableListOf<HttpMethod>()
        val restarted = port(MockEngine { methods += it.method; respond("", HttpStatusCode.NotFound) })
        assertEquals(MembershipPort.Disposition.NEEDS_RECONCILIATION,
            restarted.projection(authority, "item", MembershipPort.Kind.FAVORITE)?.disposition)
        assertTrue(restarted.dispatch().isEmpty())
        assertEquals(MembershipPort.Disposition.PAUSED, restarted.reconcile(command).disposition)
        assertEquals(MembershipPort.Disposition.PAUSED,
            restarted.projection(authority, "item", MembershipPort.Kind.FAVORITE)?.disposition)
        assertTrue(restarted.dispatch().isEmpty())
        assertNotNull(future.dirtyOperationDao().getById(command.id))
        assertEquals(0, future.dirtyOperationDao().runnableLegacyCountForScope("s", "p"))
        assertEquals(listOf(HttpMethod.Get), methods)
    }

    @Test fun dispatcherLimitBoundsRequestsAndOldIdentityAckDoesNotPublish() = runTest {
        open()
        var calls = 0
        val port = port(MockEngine { calls++; respond("", HttpStatusCode.NoContent) })
        repeat(3) { port.record(authority, "item-$it", MembershipPort.Kind.WATCHLIST, true) }
        assertEquals(2, port.dispatch(2).size)
        assertEquals(2, calls)
        assertEquals(1, port.dispatch(2).size)
        assertEquals(3, calls)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delayed = port(MockEngine { entered.complete(Unit); release.await(); respond("", HttpStatusCode.NoContent) })
        val old = delayed.record(authority, "item", MembershipPort.Kind.FAVORITE, true)
        val sending = async { delayed.send(old) }; entered.await()
        barrier.changing(IdentityTransitionKind.ACCOUNT_REPLACE) {
            authority = original.copy(loginId = "login-two", scope = original.scope.copy(identityGeneration = 1))
        }
        val new = delayed.record(authority, "item", MembershipPort.Kind.FAVORITE, false)
        release.complete(Unit)
        val result = sending.await()
        assertEquals(MembershipPort.Disposition.ACKNOWLEDGED, result.disposition)
        assertFalse(result.mayPublish)
        assertEquals(new.id, delayed.projection(authority, "item", MembershipPort.Kind.FAVORITE)?.commandId)
        assertNull(delayed.projection(authority, "item", MembershipPort.Kind.FAVORITE)?.disposition)
    }

}
