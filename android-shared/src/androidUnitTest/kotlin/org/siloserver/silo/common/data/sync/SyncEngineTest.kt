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
    fun legacyPersonalRowsRemainByteForByteAcrossRepeatedDrains() = runTest {
        val dao = db.dirtyOperationDao()
        val rows = listOf(OutboxOperation.SET_WATCHED, OutboxOperation.SET_RATING, OutboxOperation.SET_POSITION)
            .flatMap { kind -> listOf("pending", "in_flight").map { state ->
                op(idempotencyKey = "$kind-$state", opKind = kind, payload = "{unconverted legacy bytes}")
                    .copy(state = state, attemptCount = 3, lastAttemptAtMs = 17, lastError = "uncertain")
            } }
        val saved = rows.map { row -> val id = dao.insert(row); row.copy(id = id) }
        repeat(2) {
            assertEquals(SyncEngine.DrainResult(), engine(batchLimit = 1).drainOnce())
            assertEquals(saved, saved.map { dao.getById(it.id) })
        }
    }

    @Test
    fun heldPersonalRowDoesNotBlockEbookForSameItem() = runTest {
        val dao = db.dirtyOperationDao()
        val heldId = dao.insert(op(idempotencyKey = "held"))
        val before = dao.getById(heldId)
        dao.insert(op(idempotencyKey = "ebook", opKind = OutboxOperation.SET_EBOOK_PROGRESS,
            coalesceKey = "ebook", payload = OutboxOperation.encodeEbookProgressPayload(7, "epubcfi(/6/4!/4)", 0.5)))
        assertEquals(1, engine().drainOnce().synced)
        assertEquals(before, dao.getById(heldId))
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
