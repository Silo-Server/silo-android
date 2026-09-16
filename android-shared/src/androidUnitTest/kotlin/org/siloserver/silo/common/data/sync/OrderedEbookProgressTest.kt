package org.siloserver.silo.common.data.sync

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.repository.RoomUserItemStateRepository
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.network.apiv2.EbookReaderV2Api
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class OrderedEbookProgressTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SiloDatabase::class.java)
        .allowMainThreadQueries().build()
    private val barrier = DefaultIdentityTransitionBarrier()
    private val scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof", isIdentityGenerationStamped = true)
    private var login = "login-1"
    private var temporary = false
    private val tokens = object : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
        override suspend fun snapshotCurrentScope() = scope
        override suspend fun snapshotDurableLoginAuthority() = if (temporary) null else DurableLoginAuthority(login, scope)
    }
    @AfterTest fun close() = db.close()
    private fun recorder() = RoomUserItemStateRepository(db, { scope }, ebookAuthorities = tokens, identityTransitions = barrier)

    @Test fun eventTimeSurvivesRetryAndBackwardMoveDoesNotUseLegacyGetGuard() = runTest {
        val bodies = mutableListOf<String>(); var fail = true
        val c = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            bodies += request.body.toByteArray().decodeToString()
            respond(if (fail) "{}" else """{"progress":{"content_id":"book","file_id":"7","location":"back","progress":0.2,"updated_at":"1970-01-01T00:00:01Z"}}""",
                if (fail) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val authority = requireNotNull(tokens.snapshotDurableLoginAuthority())
            recorder().recordEbookProgress(authority, "book", 7, "back", 0.2, 1000)
            var now = 2000L
            val engine = SyncEngine(db, PersonalDataApi(c), EbookReaderApi(EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted)), { scope },
                now = { now }, ebookAuthorities = tokens)
            assertEquals(1, engine.drainOnce().retriable)
            fail = false; now = 100_000
            assertEquals(1, engine.drainOnce().synced)
            assertEquals(2, bodies.size); assertEquals(bodies.first(), bodies.last())
            assertTrue(bodies.first().contains("1970-01-01T00:00:01Z"))
        } finally { c.close() }
    }

    @Test fun temporaryOverlayPausesAndReplacementLoginQuarantinesWithoutSending() = runTest {
        var sends = 0
        val c = HttpClient(MockEngine { sends++; respond("{}") })
        try {
            recorder().recordEbookProgress(requireNotNull(tokens.snapshotDurableLoginAuthority()), "book", 7, "page", 0.2, 1000)
            val id = db.dirtyOperationDao().dueBatch("server", "profile", 2000, 10).single().id
            val engine = SyncEngine(db, PersonalDataApi(c), EbookReaderApi(EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted)), { scope }, now = { 2000 }, ebookAuthorities = tokens)
            temporary = true
            engine.drainOnce()
            assertEquals("pending", db.dirtyOperationDao().getById(id)?.state)
            temporary = false; login = "replacement"
            engine.drainOnce()
            assertEquals("ebook_identity_quarantined", db.dirtyOperationDao().getById(id)?.state)
            assertEquals(0, sends)
        } finally { c.close() }
    }

    @Test fun lateOlderEventCannotReplaceNewerLocalProjectionOrOutbox() = runTest {
        val authority = requireNotNull(tokens.snapshotDurableLoginAuthority())
        val recorder = recorder()
        recorder.recordEbookProgress(authority, "book", 7, "new", 0.2, 2000)
        recorder.recordEbookProgress(authority, "book", 7, "old", 0.8, 1000)
        recorder.recordEbookProgress(authority, "book", 8, "old-version", 0.9, 1500)
        assertNull(recorder.localEbookProgress("book", 8))
        assertEquals("new", recorder.localEbookProgress("book", 7)?.location)
        val entry = db.dirtyOperationDao().dueBatch("server", "profile", 3000, 10).single()
        assertEquals("new", OutboxOperation.decodeEbookProgressPayload(entry.payloadJson).location)
    }
    @Test fun missingSuccessReceiptRetainsOriginalOutboxEvent() = runTest {
        val c = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        try {
            recorder().recordEbookProgress(requireNotNull(tokens.snapshotDurableLoginAuthority()), "book", 7, "page", 0.2, 1000)
            val before = db.dirtyOperationDao().dueBatch("server", "profile", 2000, 10).single()
            val engine = SyncEngine(db, PersonalDataApi(c), EbookReaderApi(EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted)), { scope },
                now = { 2000 }, ebookAuthorities = tokens)
            assertEquals(1, engine.drainOnce().retriable)
            assertEquals(before.payloadJson, db.dirtyOperationDao().getById(before.id)?.payloadJson)
        } finally { c.close() }
    }

}
