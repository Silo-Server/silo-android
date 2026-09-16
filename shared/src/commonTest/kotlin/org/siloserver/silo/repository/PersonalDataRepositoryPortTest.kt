package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.repository.port.*
import kotlin.test.*

class PersonalDataRepositoryPortTest {
    private class Identity : TokenManager by TokenManagerImpl() {
        var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof", identityGeneration = 1)
        override suspend fun snapshotCurrentScope() = scope
    }
    private class Port(val identity: Identity) : UserItemStatePort by NoOpUserItemStatePort {
        var pending: PersonalWriteHandle? = null
        var completed = 0
        override suspend fun beginPersonalWrite(command: PersonalWrite): PersonalWriteHandle? {
            if (pending != null) return null
            return PersonalWriteHandle(1, identity.scope, command).also { pending = it }
        }
        override suspend fun completePersonalWrite(handle: PersonalWriteHandle) { assertEquals(pending, handle); pending = null; completed++ }
        override suspend fun abandonPersonalWrite(handle: PersonalWriteHandle) { assertEquals(pending, handle); pending = null }
    }

    @Test fun freshTypedWritesUseExactV2BodyAndSettleOnly204() = runTest {
        val identity = Identity(); val port = Port(identity); val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine { req ->
            val saved = requireNotNull(port.pending)
            assertEquals(saved.scope, req.attributes[AuthScopeAttributeKey])
            assertTrue(req.attributes[SingleAttemptAttributeKey])
            assertEquals(saved.command.body ?: "", req.body.toByteArray().decodeToString())
            calls += "${req.method.value} ${req.url.encodedPath}"
            respond("", HttpStatusCode.NoContent)
        })
        try {
            val repo = PersonalDataRepository(PersonalDataApi(client, tokenManager = identity), port)
            assertIs<ApiResult.Success<*>>(repo.setRating("item", 4))
            assertIs<ApiResult.Success<*>>(repo.deleteRating("item"))
            assertIs<ApiResult.Success<*>>(repo.setWatched("item", true))
            assertIs<ApiResult.Success<*>>(repo.setWatched("item", false))
            assertEquals(listOf("PUT /api/v2/ratings/item", "DELETE /api/v2/ratings/item", "POST /api/v2/watched/item", "DELETE /api/v2/watched/item"), calls)
            assertEquals(4, port.completed)
        } finally { client.close() }
    }

    @Test fun unknownResponseReleasesItemForNextWriteWithoutProjecting() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.InternalServerError, HttpStatusCode.OK)) {
            val identity = Identity(); val port = Port(identity); var sends = 0
            val client = HttpClient(MockEngine { sends++; respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json")) })
            try {
                val repo = PersonalDataRepository(PersonalDataApi(client, tokenManager = identity), port)
                assertFalse(repo.setRating("item", 4) is ApiResult.Success)
                assertNull(port.pending)
                assertFalse(repo.deleteRating("item") is ApiResult.Success)
                assertEquals(2, sends); assertEquals(0, port.completed); assertNull(port.pending)
            } finally { client.close() }
        }
    }

    @Test fun lostResponseReleasesItemAndConsumedIntentCannotReplay() = runTest {
        val identity = Identity(); val port = Port(identity); var sends = 0
        val client = HttpClient(MockEngine { sends++; throw IllegalStateException("lost response") })
        try {
            val repo = PersonalDataRepository(PersonalDataApi(client, tokenManager = identity), port)
            val intent = repo.beginWatched("item", true)
            assertIs<ApiResult.NetworkError>(repo.performPersonalWrite(intent))
            assertNull(port.pending)
            assertEquals("personal_write_consumed", assertIs<ApiResult.Error>(repo.performPersonalWrite(intent)).error)
            assertIs<ApiResult.NetworkError>(repo.setWatched("item", false))
            assertEquals(2, sends); assertEquals(0, port.completed)
        } finally { client.close() }
    }

    @Test fun changedProfileOrOriginCannotSendEvenWithSameGeneration() = runTest {
        val identity = Identity(); var sends = 0
        val client = HttpClient(MockEngine { sends++; error("must not send") })
        try {
            val api = PersonalDataApi(client, tokenManager = identity)
            val handle = PersonalWriteHandle(1, identity.scope, PersonalWrite.Watched("item", true))
            identity.scope = identity.scope.copy(profileId = "other")
            assertIs<ApiResult.Error>(api.writePersonal(handle))
            identity.scope = handle.scope.copy(serverUrl = "https://other.invalid")
            assertIs<ApiResult.Error>(api.writePersonal(handle))
            assertEquals(0, sends)
        } finally { client.close() }
    }

    @Test fun actionCapturedBeforeIdentityChangeCannotJournalOrSendAndConsumedActionCannotReplay() = runTest {
        val identity = Identity(); val port = Port(identity); val barrier = DefaultIdentityTransitionBarrier(); var sends = 0
        val client = HttpClient(MockEngine { sends++; respond("", HttpStatusCode.NoContent) })
        try {
            val repo = PersonalDataRepository(PersonalDataApi(client, tokenManager = identity), port, identityTransitions = barrier)
            val stale = repo.beginWatched("item", true)
            barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) { }
            assertIs<ApiResult.Error>(repo.performPersonalWrite(stale))
            assertNull(port.pending); assertEquals(0, sends)
            val fresh = repo.beginWatched("item", true)
            assertIs<ApiResult.Success<*>>(repo.performPersonalWrite(fresh))
            assertEquals("personal_write_consumed", assertIs<ApiResult.Error>(repo.performPersonalWrite(fresh)).error)
            assertEquals(1, sends)
        } finally { client.close() }
    }

    @Test fun invalidRatingAndMissingDurableStoreFailBeforeTransport() = runTest {
        val client = HttpClient(MockEngine { error("must not send") })
        try {
            val repo = PersonalDataRepository(PersonalDataApi(client))
            assertEquals(422, assertIs<ApiResult.Error>(repo.setRating("item", 6)).code)
            assertIs<ApiResult.Error>(repo.setWatched("item", true))
            assertIs<ApiResult.Error>(repo.syncProgress(emptyList()))
        } finally { client.close() }
    }
}
