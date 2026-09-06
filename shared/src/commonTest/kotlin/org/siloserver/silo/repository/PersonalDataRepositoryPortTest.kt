package org.siloserver.silo.repository

import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.repository.port.OutboxHandle
import org.siloserver.silo.repository.port.UserItemStatePort
import org.siloserver.silo.repository.port.WriteOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the Track B dual-path contract on [PersonalDataRepository]: each
 * content-level mutation records through the [UserItemStatePort] and then
 * resolves the op with the network outcome. Guards the 401-retriable boundary
 * (a 401 reaching the classifier means token refresh could not complete, so the
 * write may still succeed once auth is restored — it must not be dropped).
 */
class PersonalDataRepositoryPortTest {

    private class RecordingPort : UserItemStatePort {
        val recorded = mutableListOf<String>()
        var resolvedWith: WriteOutcome? = null
        private var seq = 0L

        override suspend fun recordWatched(contentId: String, watched: Boolean): OutboxHandle {
            recorded += "watched=$watched"
            return OutboxHandle(seq++)
        }

        override suspend fun recordFavorite(contentId: String, favorite: Boolean): OutboxHandle {
            recorded += "favorite=$favorite"
            return OutboxHandle(seq++)
        }

        override suspend fun recordRating(contentId: String, rating: Int?): OutboxHandle {
            recorded += "rating=$rating"
            return OutboxHandle(seq++)
        }

        override suspend fun resolve(handle: OutboxHandle, outcome: WriteOutcome) {
            resolvedWith = outcome
        }

        val confirmed = mutableListOf<Pair<List<String>, Boolean>>()
        override suspend fun recordConfirmedWatched(contentIds: List<String>, watched: Boolean) {
            confirmed += contentIds to watched
        }
    }

    private fun repo(
        status: HttpStatusCode,
        port: UserItemStatePort,
        reconciliationScope: CoroutineScope? = null,
    ): PersonalDataRepository {
        val client = HttpClient(
            MockEngine { respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json")) },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return if (reconciliationScope == null) {
            PersonalDataRepository(PersonalDataApi(client), port)
        } else {
            PersonalDataRepository(PersonalDataApi(client), port, reconciliationScope = reconciliationScope)
        }
    }

    /**
     * A season write fans out on the server. The children must be confirmed
     * locally even after the screen that started the write is gone, so the
     * confirmation runs on the repository's scope, not the caller's.
     */
    @Test
    fun setContainerWatchedConfirmsKnownAndResolvedChildrenOnItsOwnScope() = runTest {
        val port = RecordingPort()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repoScope = CoroutineScope(Job() + dispatcher)
        val callerScope = CoroutineScope(Job() + dispatcher)
        val repository = repo(HttpStatusCode.OK, port, reconciliationScope = repoScope)

        var result: Any? = null
        val call = callerScope.launch {
            result = repository.setContainerWatched(
                itemId = "season-1",
                watched = true,
                knownChildIds = listOf("e1"),
                resolveChildIds = { listOf("e1", "e2", "e3") },
            )
        }
        call.join()
        // The caller leaves before reconciliation runs.
        callerScope.cancel()
        advanceUntilIdle()

        assertTrue(result is org.siloserver.silo.network.ApiResult.Success<*>)
        assertEquals(listOf("watched=true"), port.recorded)
        assertEquals(listOf(listOf("e1") to true, listOf("e2", "e3") to true), port.confirmed)
    }

    @Test
    fun setContainerWatchedDoesNotConfirmChildrenOnFailure() = runTest {
        val port = RecordingPort()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = repo(
            HttpStatusCode.Forbidden,
            port,
            reconciliationScope = CoroutineScope(Job() + dispatcher),
        )
        repository.setContainerWatched("season-1", true, listOf("e1")) { listOf("e1", "e2") }
        advanceUntilIdle()
        assertTrue(port.confirmed.isEmpty())
    }

    @Test
    fun setWatchedRecordsThenResolvesSyncedOnSuccess() = runTest {
        val port = RecordingPort()
        repo(HttpStatusCode.OK, port).setWatched("c1", watched = true)
        assertEquals(listOf("watched=true"), port.recorded)
        assertEquals(WriteOutcome.SYNCED, port.resolvedWith)
    }

    @Test
    fun networkUnauthorizedIsRetriableNotTerminal() = runTest {
        val port = RecordingPort()
        repo(HttpStatusCode.Unauthorized, port).toggleFavorite("c1", isFavorite = true)
        assertEquals(WriteOutcome.RETRIABLE, port.resolvedWith)
    }

    @Test
    fun forbiddenIsTerminal() = runTest {
        val port = RecordingPort()
        repo(HttpStatusCode.Forbidden, port).setRating("c1", rating = 4)
        assertEquals(WriteOutcome.TERMINAL, port.resolvedWith)
    }

    @Test
    fun serverErrorIsRetriable() = runTest {
        val port = RecordingPort()
        repo(HttpStatusCode.InternalServerError, port).deleteRating("c1")
        assertEquals(WriteOutcome.RETRIABLE, port.resolvedWith)
    }
}
