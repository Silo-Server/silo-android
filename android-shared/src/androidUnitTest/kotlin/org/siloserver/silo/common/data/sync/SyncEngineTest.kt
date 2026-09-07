package org.siloserver.silo.common.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.PersonalDataApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        SiloDatabase::class.java,
    ).allowMainThreadQueries().build()

    private var status = HttpStatusCode.OK
    private var clock = 1_000L

    private val snapshot = AuthScopeSnapshot(
        serverId = "s1",
        profileId = "p1",
        serverUrl = "https://s1.example",
        profileToken = "pt",
    )

    private fun mockClient() = HttpClient(
        MockEngine { respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json")) },
    ) {
        install(ContentNegotiation) { json(SiloJson) }
    }

    private val api = PersonalDataApi(mockClient())
    private val ebookApi = org.siloserver.silo.network.api.EbookReaderApi(mockClient())

    private fun engine(batchLimit: Int = 50) = SyncEngine(
        db = db,
        personalDataApi = api,
        ebookReaderApi = ebookApi,
        snapshotProvider = { snapshot },
        now = { clock },
        batchLimit = batchLimit,
    )

    private val confirmed = mutableListOf<String>()
    private val confirmedScopes = mutableListOf<AuthScopeSnapshot>()
    private var episodesStatus = HttpStatusCode.OK
    private fun reconcilingEngine() = SyncEngine(
        db = db,
        personalDataApi = api,
        ebookReaderApi = ebookApi,
        snapshotProvider = { snapshot },
        now = { clock },
        catalogApi = org.siloserver.silo.network.api.CatalogApi(
            HttpClient(
                MockEngine {
                    respond(
                        """{"episodes":[{"content_id":"e1","season_number":2,"episode_number":1},{"content_id":"e2","season_number":2,"episode_number":2}]}""",
                        episodesStatus,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ) { install(ContentNegotiation) { json(SiloJson) } },
        ),
        childConfirmer = SyncEngine.WatchedChildConfirmer { scope, contentId, watched, _ ->
            confirmedScopes += scope
            confirmed += "$contentId=$watched"
        },
    )

    private fun reconcileOp(idempotencyKey: String) = DirtyOperationEntity(
        opKind = OutboxOperation.RECONCILE_WATCHED_CHILDREN,
        serverId = "s1",
        profileId = "p1",
        targetContentId = "season-1",
        targetFileId = null,
        coalesceKey = "s1|p1|season-1|${OutboxOperation.RECONCILE_WATCHED_CHILDREN}",
        idempotencyKey = idempotencyKey,
        payloadJson = OutboxOperation.encodeReconcilePayload(
            OutboxOperation.ReconcileWatchedChildrenPayload(
                watched = true, seriesId = "series-1", seasonNumber = 2,
                knownChildIds = listOf("e1"), containerAtMs = 500L,
            ),
        ),
        createdAtMs = 1L,
        nextAttemptAtMs = 0L,
    )

    /** Known children first, then every episode the catalog reports, once, all pinned to the drain scope. */
    @Test
    fun reconcileWatchedChildrenConfirmsKnownThenDiscoveredEpisodes() = runTest {
        db.dirtyOperationDao().insert(reconcileOp("r1"))
        val result = reconcilingEngine().drainOnce()
        assertEquals(1, result.synced)
        assertEquals(listOf("e1=true", "e2=true"), confirmed)
        assertTrue(confirmedScopes.all { it == snapshot })
        assertEquals(0, db.dirtyOperationDao().count())
    }

    /**
     * A transient lookup failure keeps the op queued with backoff instead of
     * abandoning discovery; the known children were still confirmed.
     */
    @Test
    fun reconcileWatchedChildrenRetriesDiscoveryOnTransientFailure() = runTest {
        db.dirtyOperationDao().insert(reconcileOp("r1"))
        episodesStatus = HttpStatusCode.ServiceUnavailable
        val result = reconcilingEngine().drainOnce()
        assertEquals(1, result.retriable)
        assertEquals(listOf("e1=true"), confirmed)
        assertEquals(1, db.dirtyOperationDao().count())
    }

    /** The reconciliation waits behind its container's own write (per-item FIFO). */
    @Test
    fun reconcileWatchedChildrenWaitsForTheContainerWrite() = runTest {
        db.dirtyOperationDao().insert(
            op(idempotencyKey = "season", targetContentId = "season-1", coalesceKey = "s1|p1|season-1|SET_WATCHED", nextAttemptAtMs = 10_000L),
        )
        db.dirtyOperationDao().insert(reconcileOp("r1"))
        val result = reconcilingEngine().drainOnce()
        assertEquals(0, result.synced)
        assertTrue(confirmed.isEmpty())
    }

    /** A season write rejected during the drain must not leave its reconciliation to run later. */
    @Test
    fun terminalContainerWriteDropsItsQueuedReconciliation() = runTest {
        db.dirtyOperationDao().insert(
            op(idempotencyKey = "season", targetContentId = "season-1", coalesceKey = "s1|p1|season-1|SET_WATCHED"),
        )
        db.dirtyOperationDao().insert(reconcileOp("r1"))
        status = HttpStatusCode.Forbidden
        val result = reconcilingEngine().drainOnce()
        assertEquals(1, result.dropped)
        assertEquals(0, db.dirtyOperationDao().count())
        assertTrue(confirmed.isEmpty())
    }

    /** An engine without a catalog API cannot honour the op; drop rather than spin. */
    @Test
    fun reconcileWatchedChildrenIsDroppedWhenNoCatalogApiIsBound() = runTest {
        db.dirtyOperationDao().insert(reconcileOp("r1"))
        val result = engine().drainOnce()
        assertEquals(1, result.dropped)
        assertEquals(0, db.dirtyOperationDao().count())
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun op(
        serverId: String = "s1",
        targetContentId: String = "c1",
        coalesceKey: String = "s1|p1|c1|${OutboxOperation.SET_WATCHED}",
        idempotencyKey: String,
        payload: String = "true",
        opKind: String = OutboxOperation.SET_WATCHED,
        nextAttemptAtMs: Long = 0L,
    ) = DirtyOperationEntity(
        opKind = opKind,
        serverId = serverId,
        profileId = "p1",
        targetContentId = targetContentId,
        targetFileId = null,
        coalesceKey = coalesceKey,
        idempotencyKey = idempotencyKey,
        payloadJson = payload,
        createdAtMs = 1L,
        nextAttemptAtMs = nextAttemptAtMs,
    )

    @Test
    fun successSyncsAndDeletes() = runTest {
        db.dirtyOperationDao().insert(op(idempotencyKey = "i1"))
        status = HttpStatusCode.OK
        val result = engine().drainOnce()
        assertEquals(1, result.synced)
        assertEquals(0, db.dirtyOperationDao().count())
    }

    @Test
    fun malformedPayloadIsDroppedTerminallyInsteadOfBrickingTheDrain() = runTest {
        // A payload that fails to decode (pre-validation rows, foreign
        // writers) must not rethrow out of drainOnce: SyncWorker would retry
        // forever and every other op for the scope would never sync again.
        db.dirtyOperationDao().insert(op(idempotencyKey = "poison", payload = "{not json"))
        db.dirtyOperationDao().insert(op(idempotencyKey = "healthy", coalesceKey = "s1|p1|c2|SET_WATCHED"))
        status = HttpStatusCode.OK

        val result = engine().drainOnce()

        assertEquals(1, result.dropped)
        assertEquals(1, result.synced)
        assertEquals(0, db.dirtyOperationDao().count())
    }

    @Test
    fun retriableKeepsOpAndBacksOff() = runTest {
        db.dirtyOperationDao().insert(op(idempotencyKey = "i1"))
        status = HttpStatusCode.InternalServerError
        val result = engine().drainOnce()
        assertEquals(1, result.retriable)
        val row = db.dirtyOperationDao().dueBatch("s1", "p1", nowMs = Long.MAX_VALUE, limit = 10).single()
        assertEquals(DirtyOperationEntity.STATE_PENDING, row.state)
        assertEquals(1, row.attemptCount)
        assertTrue(row.nextAttemptAtMs > clock, "backoff should push nextAttemptAtMs into the future")
    }

    @Test
    fun newerOpForTheSameItemWaitsWhileAnOlderOpIsBackingOff() = runTest {
        // The state after an older op fails a SECOND time: recordFailure pushes it
        // into the future again, so the enqueue-time clamp
        // (enqueueCoalescingRestoringItemOrder) has already been undone and cannot
        // help. dueBatch only returns due rows, so without per-item FIFO the newer
        // SET_POSITION drains now and the retried SET_WATCHED lands after it and
        // clears the resume position the user just created.
        val dao = db.dirtyOperationDao()
        dao.insert(
            op(
                idempotencyKey = "watched-retrying",
                opKind = OutboxOperation.SET_WATCHED,
                coalesceKey = "s1|p1|c1|${OutboxOperation.SET_WATCHED}",
                nextAttemptAtMs = clock + 30_000L,
            ),
        )
        dao.insert(
            op(
                idempotencyKey = "position-newer",
                opKind = OutboxOperation.SET_POSITION,
                coalesceKey = "s1|p1|c1|${OutboxOperation.SET_POSITION}",
                payload = """{"positionTicks":1,"playedPercentage":1.0}""",
                nextAttemptAtMs = 0L,
            ),
        )
        status = HttpStatusCode.OK

        val result = engine().drainOnce()

        assertEquals(0, result.synced, "newer op must not overtake an older op for the same item")
        assertEquals(2, dao.count(), "both ops stay queued until the older one clears")
    }

    @Test
    fun olderBackoffOutsideSqlPageStillBlocksNewerSibling() = runTest {
        val dao = db.dirtyOperationDao()
        dao.insert(
            op(
                targetContentId = "blocked",
                idempotencyKey = "blocked-older",
                coalesceKey = "s1|p1|blocked|${OutboxOperation.SET_WATCHED}",
                nextAttemptAtMs = clock + 30_000L,
            ),
        )
        dao.insert(
            op(
                targetContentId = "blocked",
                idempotencyKey = "blocked-newer",
                opKind = OutboxOperation.SET_POSITION,
                coalesceKey = "s1|p1|blocked|${OutboxOperation.SET_POSITION}",
                payload = """{"positionTicks":1,"playedPercentage":1.0}""",
            ),
        )
        dao.insert(
            op(
                targetContentId = "independent",
                idempotencyKey = "independent",
                coalesceKey = "s1|p1|independent|${OutboxOperation.SET_WATCHED}",
            ),
        )
        status = HttpStatusCode.OK

        val result = engine(batchLimit = 2).drainOnce()

        assertEquals(1, result.synced, "only the independent target may pass")
        assertEquals(2, dao.count(), "both blocked siblings remain queued in creation order")
    }

    @Test
    fun terminalDropsOp() = runTest {
        db.dirtyOperationDao().insert(op(idempotencyKey = "i1"))
        status = HttpStatusCode.Forbidden
        val result = engine().drainOnce()
        assertEquals(1, result.dropped)
        assertEquals(0, db.dirtyOperationDao().count())
    }

    @Test
    fun reclaimsCrashStrandedInFlightThenSyncs() = runTest {
        val dao = db.dirtyOperationDao()
        val id = dao.insert(op(idempotencyKey = "i1"))
        assertEquals(1, dao.claim(id)) // simulate crash mid-send (left in_flight)
        status = HttpStatusCode.OK
        val result = engine().drainOnce()
        assertEquals(1, result.synced)
        assertEquals(0, dao.count())
    }

    @Test
    fun reclaimDropsStrandedInFlightSupersededByNewerPending() = runTest {
        val dao = db.dirtyOperationDao()
        val key = "s1|p1|c1|${OutboxOperation.SET_WATCHED}"
        val stranded = dao.insert(op(coalesceKey = key, idempotencyKey = "i1", payload = "true"))
        assertEquals(1, dao.claim(stranded)) // in_flight
        dao.insert(op(coalesceKey = key, idempotencyKey = "i2", payload = "false")) // newer pending, same key
        status = HttpStatusCode.OK
        engine().drainOnce()
        // Stranded stale op dropped at reclaim; only the newer op synced.
        assertEquals(0, dao.count())
    }

    @Test
    fun drainIsScopedToCurrentServer() = runTest {
        db.dirtyOperationDao().insert(op(serverId = "s2", coalesceKey = "s2|p1|c1|w", idempotencyKey = "i1"))
        status = HttpStatusCode.OK
        val result = engine().drainOnce()
        assertEquals(0, result.synced)
        // The other server's op is untouched (snapshot pins s1/p1).
        assertEquals(1, db.dirtyOperationDao().count())
    }

    @Test
    fun drainsBacklogLargerThanBatchLimitInOneRun() = runTest {
        repeat(5) { i ->
            db.dirtyOperationDao().insert(op(coalesceKey = "s1|p1|c$i|w", idempotencyKey = "i$i"))
        }
        status = HttpStatusCode.OK
        val result = engine(batchLimit = 2).drainOnce()
        assertEquals(5, result.synced)
        assertEquals(0, result.remaining)
        assertEquals(0, db.dirtyOperationDao().count())
    }

    @Test
    fun setPositionDrainsViaSyncProgress() = runTest {
        db.dirtyOperationDao().insert(
            op(
                coalesceKey = "s1|p1|c1|${OutboxOperation.SET_POSITION}",
                idempotencyKey = "i1",
                opKind = OutboxOperation.SET_POSITION,
                payload = OutboxOperation.encodePositionPayload(123.0, 3600.0),
            ),
        )
        status = HttpStatusCode.OK
        val result = engine().drainOnce()
        assertEquals(1, result.synced)
        assertEquals(0, db.dirtyOperationDao().count())
    }

    @Test
    fun noScopeDrainsNothing() = runTest {
        db.dirtyOperationDao().insert(op(idempotencyKey = "i1"))
        val noScopeEngine = SyncEngine(
            db = db,
            personalDataApi = api,
            ebookReaderApi = ebookApi,
            snapshotProvider = { null },
            now = { clock },
        )
        val result = noScopeEngine.drainOnce()
        assertEquals(0, result.synced)
        assertEquals(1, db.dirtyOperationDao().count())
    }

    @Test
    fun ebookProgressDrainsWhenLocalAheadOfServer() = runTest {
        // MockEngine getProgress returns "{}" → server progress 0.0; local 0.5 is
        // ahead → saveProgress runs → 200 → synced.
        db.dirtyOperationDao().insert(
            op(
                coalesceKey = "s1|p1|c1|${OutboxOperation.SET_EBOOK_PROGRESS}",
                idempotencyKey = "i1",
                opKind = OutboxOperation.SET_EBOOK_PROGRESS,
                payload = OutboxOperation.encodeEbookProgressPayload(7, "epubcfi(/6/4!/4)", 0.5),
            ),
        )
        status = HttpStatusCode.OK
        val result = engine().drainOnce()
        assertEquals(1, result.synced)
        assertEquals(0, db.dirtyOperationDao().count())
    }
}
