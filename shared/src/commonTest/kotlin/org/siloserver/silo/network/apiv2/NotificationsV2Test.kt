package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.network.*
import org.siloserver.silo.repository.*
import kotlin.test.*

class NotificationsV2Test {
    private class Identity : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
        var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof", identityGeneration = 1)
        var login = "login-1"
        override suspend fun snapshotCurrentScope() = scope
        override suspend fun snapshotDurableLoginAuthority() = DurableLoginAuthority(login, scope)
    }
    private class Store : NotificationSyncStore {
        val checkpoints = mutableMapOf<String, String>()
        override suspend fun read(key: String) = checkpoints[key]
        override suspend fun write(key: String, cursor: String) { checkpoints[key] = cursor }
    }
    private fun row(id: String, read: Boolean = false) = """{"id":"$id","type":"episode.available","profile_id":"profile","library_id":"opaque-library","created_at":"2026-09-05T12:00:00.000Z","read_at":${if (read) "\"2026-09-05T12:01:00.000Z\"" else "null"},"reason_flags":{}}"""
    private fun page(items: String, cutoff: String = "frozen") = """{"items":[$items],"page":{"has_more":false},"read_cutoff":"$cutoff"}"""
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test fun readAllUsesDisplayedCutoffAndRereadsNewRowsWithoutMarkingThemRead() = runTest {
        val identity = Identity(); var marked = false; var posts = 0
        val c = client { request -> when (request.url.encodedPath) {
            "/api/v2/notifications/unread-count" -> reply("""{"count":1}""")
            "/api/v2/notifications" -> reply(page(if (marked) row("new") + "," + row("old", true) else row("old"), if (marked) "new-cutoff" else "frozen"))
            "/api/v2/notifications/read-all" -> {
                posts++
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(request.attributes[SingleAttemptAttributeKey])
                assertEquals(identity.scope, request.attributes[AuthScopeAttributeKey])
                assertEquals("frozen", SiloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["through"]!!.jsonPrimitive.content)
                marked = true
                reply("", HttpStatusCode.NoContent)
            }
            else -> error("Unexpected request")
        } }
        try {
            val repository = NotificationsRepository(NotificationsV2Api(c, identity, ApiV2Gate.Unrestricted), tokens = identity)
            repository.refresh(); repository.markAllRead()
            assertEquals(1, posts); assertFalse(repository.rows.value.first().isRead)
            assertTrue(repository.rows.value.last().isRead); assertEquals(1, repository.unreadCount.value)
            assertEquals("opaque-library", repository.rows.value.first().libraryId)
        } finally { c.close() }
    }

    @Test fun finalEmptySyncCheckpointPersistsAndDifferentLoginDoesNotReuseIt() = runTest {
        val identity = Identity(); val store = Store(); val cursors = mutableListOf<String?>()
        val c = client { request -> when (request.url.encodedPath) {
            "/api/v2/notifications/unread-count" -> reply("""{"count":0}""")
            "/api/v2/notifications" -> reply(page(""))
            "/api/v2/notifications/sync" -> {
                cursors += request.url.parameters["cursor"]
                assertNull(request.url.parameters["since"])
                reply("""{"items":[],"page":{"has_more":false},"sync_cursor":"checkpoint-${cursors.size}","unread_count":0,"initial_snapshot":${cursors.last() == null}}""")
            }
            else -> error("Unexpected request")
        } }
        try {
            fun repo() = NotificationsRepository(NotificationsV2Api(c, identity, ApiV2Gate.Unrestricted), tokens = identity, authorities = identity, checkpoints = store)
            repo().refresh(); repo().refresh()
            identity.login = "login-2"
            repo().refresh()
            assertEquals(listOf(null, "checkpoint-1", null), cursors)
            assertEquals(2, store.checkpoints.size)
            assertTrue(store.checkpoints.values.contains("checkpoint-2"))
        } finally { c.close() }
    }

    @Test fun lateProfileResponseCannotPopulateReplacementInbox() = runTest {
        val identity = Identity()
        val c = client { request -> when (request.url.encodedPath) {
            "/api/v2/notifications/unread-count" -> reply("""{"count":1}""")
            else -> {
                identity.scope = identity.scope.copy(identityGeneration = 2, profileId = "replacement")
                reply(page(row("old")))
            }
        } }
        try {
            val repository = NotificationsRepository(NotificationsV2Api(c, identity, ApiV2Gate.Unrestricted), tokens = identity)
            repository.refresh()
            assertTrue(repository.rows.value.isEmpty()); assertEquals(0, repository.unreadCount.value)
        } finally { c.close() }
    }

    @Test fun uncertainReadAllIsSingleAttemptAndDoesNotOptimisticallyClearInbox() = runTest {
        val identity = Identity(); var posts = 0
        val c = client { request -> when (request.url.encodedPath) {
            "/api/v2/notifications/unread-count" -> reply("""{"count":1}""")
            "/api/v2/notifications" -> reply(page(row("old")))
            else -> { posts++; reply("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized) }
        } }
        try {
            val repository = NotificationsRepository(NotificationsV2Api(c, identity, ApiV2Gate.Unrestricted), tokens = identity)
            repository.refresh(); repository.markAllRead()
            assertEquals(1, posts); assertFalse(repository.rows.value.single().isRead)
            assertEquals(1, repository.unreadCount.value)
        } finally { c.close() }
    }
    @Test fun accountTransitionClearsInboxBeforeNewCredentialsBecomeVisible() = runTest {
        val identity = Identity().apply { scope = scope.copy(identityGeneration = 0) }
        val barrier = DefaultIdentityTransitionBarrier()
        val c = client { request -> if (request.url.encodedPath.endsWith("unread-count")) reply("""{"count":1}""") else reply(page(row("old"))) }
        try {
            val repository = NotificationsRepository(NotificationsV2Api(c, identity, ApiV2Gate.Unrestricted), tokens = identity, identityTransitions = barrier)
            repository.refresh()
            assertEquals(1, repository.rows.value.size)
            barrier.changing(IdentityTransitionKind.ACCOUNT_REPLACE) {
                assertTrue(repository.rows.value.isEmpty())
                assertEquals(0, repository.unreadCount.value)
                identity.login = "new-login"
                identity.scope = identity.scope.copy(identityGeneration = barrier.generation.value)
            }
        } finally { c.close() }
    }

}
