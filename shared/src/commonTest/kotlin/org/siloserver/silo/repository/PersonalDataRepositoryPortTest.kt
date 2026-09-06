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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
    }

    private fun repo(status: HttpStatusCode, port: UserItemStatePort): PersonalDataRepository {
        val client = HttpClient(
            MockEngine { respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json")) },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return PersonalDataRepository(PersonalDataApi(client), port)
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
        repo(HttpStatusCode.Unauthorized, port).setWatched("c1", watched = true)
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
