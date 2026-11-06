package org.siloserver.silo.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.repository.port.PersonalWrite
import org.siloserver.silo.repository.port.PersonalWriteHandle
import org.siloserver.silo.repository.port.UserItemStatePort
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleRatingControllerTest {
    private class Identity : TokenManager by TokenManagerImpl() {
        val scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof", identityGeneration = 1)
        override suspend fun snapshotCurrentScope() = scope
    }

    private class Journal(private val identity: Identity) : UserItemStatePort by NoOpUserItemStatePort {
        override suspend fun beginPersonalWrite(command: PersonalWrite) = PersonalWriteHandle(1, identity.scope, command)
    }

    private class Server(val client: HttpClient, val calls: MutableList<String>)

    private fun server(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("", HttpStatusCode.NoContent) },
    ): Server {
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            calls += request.method.value
            handler(request)
        }) { install(ContentNegotiation) { json(SiloJson) } }
        return Server(client, calls)
    }

    private fun controller(server: Server, scope: CoroutineScope, saved: MutableList<Int?> = mutableListOf(), barrier: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier()): TitleRatingController {
        val identity = Identity()
        return TitleRatingController(
            itemId = "item",
            personalData = PersonalDataRepository(PersonalDataApi(server.client, tokenManager = identity), Journal(identity), identityTransitions = barrier),
            scope = scope,
            onSaved = { saved += it },
        )
    }

    @Test fun savedRatingStaysAndIsRecorded() = runTest {
        val server = server()
        val saved = mutableListOf<Int?>()
        val ratings = controller(server, backgroundScope, saved)
        ratings.adoptRead(ratings.beginRead(), null)

        val save = assertNotNull(ratings.set(4))
        assertEquals(4, ratings.state.value.rating)
        save.join()

        assertEquals(listOf("PUT"), server.calls)
        assertEquals(4, ratings.state.value.rating)
        assertNull(ratings.state.value.failureMessage)
        assertEquals(listOf<Int?>(4), saved)
    }

    @Test fun pickingTheCurrentRatingSendsNothing() = runTest {
        val server = server()
        val ratings = controller(server, backgroundScope)
        ratings.adoptRead(ratings.beginRead(), 4)

        assertNull(ratings.set(4))

        assertEquals(emptyList(), server.calls)
        assertEquals(4, ratings.state.value.rating)
    }

    @Test fun aPickDuringASaveIsDropped() = runTest {
        val release = CompletableDeferred<Unit>()
        val server = server { release.await(); respond("", HttpStatusCode.NoContent) }
        val ratings = controller(server, backgroundScope)

        val save = assertNotNull(ratings.set(4))
        assertNull(ratings.set(2))
        assertEquals(4, ratings.state.value.rating)
        release.complete(Unit)
        save.join()

        assertEquals(listOf("PUT"), server.calls)
        assertEquals(4, ratings.state.value.rating)
    }

    @Test fun aRefusedSavePutsTheOldRatingBackAndSaysSo() = runTest {
        val server = server { respond("", HttpStatusCode.InternalServerError) }
        val saved = mutableListOf<Int?>()
        val ratings = controller(server, backgroundScope, saved)
        ratings.adoptRead(ratings.beginRead(), 3)

        assertNotNull(ratings.set(5)).join()

        assertEquals(3, ratings.state.value.rating)
        assertEquals(TitleRatingController.NOT_SAVED_MESSAGE, ratings.state.value.failureMessage)
        assertEquals(emptyList(), saved)

        ratings.dismissFailure()
        assertNull(ratings.state.value.failureMessage)
    }

    private fun MockRequestHandleScope.rating(stars: Int) = respond(
        """{"rating":$stars,"rated_at":"2026-11-06T12:00:00Z"}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.unrated() = respond(
        """{"type":"https://example.invalid/problems/not_found","title":"Not Found","status":404}""",
        HttpStatusCode.NotFound,
        headersOf(HttpHeaders.ContentType, "application/problem+json"),
    )

    @Test fun aLostReplyToASaveThatLandedCountsAsSaved() = runTest {
        val server = server { request ->
            if (request.method.value == "GET") rating(5) else throw IOException("reply lost")
        }
        val saved = mutableListOf<Int?>()
        val ratings = controller(server, backgroundScope, saved)
        ratings.adoptRead(ratings.beginRead(), 3)

        assertNotNull(ratings.set(5)).join()

        assertEquals(listOf("PUT", "GET"), server.calls)
        assertEquals(5, ratings.state.value.rating)
        assertNull(ratings.state.value.failureMessage)
        assertEquals(listOf<Int?>(5), saved)
    }

    @Test fun aLostReplyToAClearThatLandedCountsAsSaved() = runTest {
        val server = server { request ->
            if (request.method.value == "GET") unrated() else throw IOException("reply lost")
        }
        val saved = mutableListOf<Int?>()
        val ratings = controller(server, backgroundScope, saved)
        ratings.adoptRead(ratings.beginRead(), 3)

        assertNotNull(ratings.set(null)).join()

        assertEquals(listOf("DELETE", "GET"), server.calls)
        assertNull(ratings.state.value.rating)
        assertNull(ratings.state.value.failureMessage)
        assertEquals(listOf<Int?>(null), saved)
    }

    @Test fun aLostReplyToASaveThatMissedShowsWhatTheServerHolds() = runTest {
        val server = server { request ->
            if (request.method.value == "GET") rating(2) else throw IOException("reply lost")
        }
        val saved = mutableListOf<Int?>()
        val ratings = controller(server, backgroundScope, saved)
        ratings.adoptRead(ratings.beginRead(), 3)

        assertNotNull(ratings.set(5)).join()

        assertEquals(2, ratings.state.value.rating)
        assertEquals(TitleRatingController.NOT_SAVED_MESSAGE, ratings.state.value.failureMessage)
        assertEquals(listOf<Int?>(2), saved)
    }

    @Test fun aLostReplyThatCannotBeCheckedPutsTheOldRatingBackAndSaysSo() = runTest {
        val server = server { throw IOException("offline") }
        val saved = mutableListOf<Int?>()
        val ratings = controller(server, backgroundScope, saved)
        ratings.adoptRead(ratings.beginRead(), 3)

        assertNotNull(ratings.set(5)).join()

        assertEquals(listOf("PUT", "GET"), server.calls)
        assertEquals(3, ratings.state.value.rating)
        assertEquals(TitleRatingController.UNCONFIRMED_MESSAGE, ratings.state.value.failureMessage)
        assertEquals(emptyList(), saved)
    }

    @Test fun aReadThatStartedBeforeASaveCannotBringTheOldRatingBack() = runTest {
        val ratings = controller(server(), backgroundScope)
        val staleRead = ratings.beginRead()

        assertNotNull(ratings.set(4)).join()
        ratings.adoptRead(staleRead, 3)

        assertEquals(4, ratings.state.value.rating)
    }

    @Test fun aReadThatStartedDuringASaveCannotBringTheOldRatingBack() = runTest {
        val release = CompletableDeferred<Unit>()
        val ratings = controller(server { release.await(); respond("", HttpStatusCode.NoContent) }, backgroundScope)

        val save = assertNotNull(ratings.set(4))
        assertTrue(ratings.state.value.isSaving)
        val overlapping = ratings.beginRead()
        ratings.adoptRead(overlapping, 3)
        assertEquals(4, ratings.state.value.rating)
        assertTrue(ratings.state.value.isSaving)
        release.complete(Unit)
        save.join()
        assertFalse(ratings.state.value.isSaving)
        ratings.adoptRead(overlapping, 3)

        assertEquals(4, ratings.state.value.rating)
    }

    @Test fun aReadThatStartedAfterASaveIsAdopted() = runTest {
        val ratings = controller(server(), backgroundScope)

        assertNotNull(ratings.set(4)).join()
        ratings.adoptRead(ratings.beginRead(), 2)

        assertEquals(2, ratings.state.value.rating)
    }

    @Test fun aProfileSwitchDuringASaveLeavesTheNewProfilesRatingAlone() = runTest {
        val release = CompletableDeferred<Unit>()
        val barrier = DefaultIdentityTransitionBarrier()
        val saved = mutableListOf<Int?>()
        val server = server { release.await(); respond("", HttpStatusCode.InternalServerError) }
        val ratings = controller(server, backgroundScope, saved, barrier)
        ratings.adoptRead(ratings.beginRead(), 3)

        val save = assertNotNull(ratings.set(5))
        withContext(Dispatchers.Default) { while (server.calls.isEmpty()) yield() }
        barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) { }
        ratings.adoptRead(ratings.beginRead(), 1)
        assertEquals(1, ratings.state.value.rating)
        assertFalse(ratings.state.value.isSaving)
        release.complete(Unit)
        save.join()

        assertEquals(1, ratings.state.value.rating)
        assertNull(ratings.state.value.failureMessage)
        assertEquals(emptyList(), saved)
    }

    @Test fun leavingThePageDoesNotAbandonASave() = runTest {
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<Int?>()
        val server = server { release.await(); respond("", HttpStatusCode.NoContent) }
        val page = CoroutineScope(backgroundScope.coroutineContext + Job())
        val ratings = controller(server, page, saved)

        val save = assertNotNull(ratings.set(4))
        withContext(Dispatchers.Default) { while (server.calls.isEmpty()) yield() }
        page.cancel()
        release.complete(Unit)
        save.join()

        assertEquals(listOf<Int?>(4), saved)
    }
}
