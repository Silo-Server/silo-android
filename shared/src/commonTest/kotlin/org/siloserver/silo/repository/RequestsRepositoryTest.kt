package org.siloserver.silo.repository

import org.siloserver.silo.model.request.AdminRequestAction
import org.siloserver.silo.model.request.AdminRequestCapabilities
import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaPage
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestOutcome
import org.siloserver.silo.model.request.RequestsDiscoverResponse
import org.siloserver.silo.model.request.RequestsFeatureStatus
import org.siloserver.silo.model.request.RequestsListResponse
import org.siloserver.silo.model.request.RequestStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.RequestsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun stubRequest(
    id: String,
    tmdbId: Int,
    status: String = RequestStatus.Pending,
    outcome: String = RequestOutcome.Active,
): MediaRequest = MediaRequest(
    id = id,
    mediaType = RequestMediaType.Movie,
    tmdbId = tmdbId,
    title = "Movie $tmdbId",
    year = 2026,
    status = status,
    outcome = outcome,
    createdAt = "2026-06-01T00:00:00Z",
    updatedAt = "2026-06-01T00:00:00Z",
)

private class FakeRequestsApi(
    private val mineResult: ApiResult<RequestsListResponse> = ApiResult.Success(RequestsListResponse()),
    private val createResult: ApiResult<MediaRequest> = ApiResult.NetworkError(IllegalStateException("no fake")),
    private val cancelResult: ApiResult<MediaRequest> = ApiResult.NetworkError(IllegalStateException("no fake")),
    private val mineGate: CompletableDeferred<Unit>? = null,
) : RequestsApi {

    var mineCalls = 0

    override suspend fun status(): ApiResult<RequestsFeatureStatus> =
        ApiResult.Success(RequestsFeatureStatus(requestsEnabled = true))

    override suspend fun discover(): ApiResult<RequestsDiscoverResponse> =
        ApiResult.Success(RequestsDiscoverResponse())

    override suspend fun discoverSection(section: String, page: Int): ApiResult<RequestMediaPage> =
        ApiResult.Success(RequestMediaPage(page = page))

    override suspend fun search(
        query: String,
        mediaType: String?,
        page: Int,
    ): ApiResult<RequestMediaPage> = ApiResult.Success(RequestMediaPage(page = page))

    override suspend fun detail(mediaType: String, tmdbId: Int): ApiResult<RequestMediaDetail> =
        ApiResult.NetworkError(IllegalStateException("no fake"))

    override suspend fun create(request: CreateMediaRequest): ApiResult<MediaRequest> = createResult

    override suspend fun mine(
        status: String?,
        outcome: String?,
        limit: Int?,
        offset: Int?,
    ): ApiResult<RequestsListResponse> {
        mineCalls++
        mineGate?.await()
        return mineResult
    }

    override suspend fun get(id: String): ApiResult<MediaRequest> =
        ApiResult.NetworkError(IllegalStateException("no fake"))

    override suspend fun cancel(id: String): ApiResult<MediaRequest> = cancelResult

    override suspend fun adminCapabilities(): ApiResult<AdminRequestCapabilities> =
        ApiResult.NetworkError(IllegalStateException("no fake"))

    override suspend fun adminRequests(
        status: String?,
        outcome: String?,
        mediaType: String?,
        tmdbId: Int?,
    ): ApiResult<RequestsListResponse> = ApiResult.NetworkError(IllegalStateException("no fake"))

    override suspend fun adminAction(id: String, action: AdminRequestAction, reason: String?): ApiResult<MediaRequest> =
        ApiResult.NetworkError(IllegalStateException("no fake"))
}

class RequestsRepositoryTest {

    @Test
    fun `mine refresh populates state flow`() = runTest {
        val seeded = listOf(stubRequest("a", 1), stubRequest("b", 2))
        val api = FakeRequestsApi(mineResult = ApiResult.Success(RequestsListResponse(seeded)))
        val repo = RequestsRepository(api)

        assertTrue(repo.mine.first().isEmpty())
        val result = repo.refreshMine()

        assertTrue(result is ApiResult.Success)
        assertEquals(seeded, repo.mine.first())
        assertEquals(1, api.mineCalls)
    }

    @Test
    fun `create upserts returned request into mine`() = runTest {
        val existing = stubRequest("existing", 1)
        val created = stubRequest("created", 2)
        val api = FakeRequestsApi(
            mineResult = ApiResult.Success(RequestsListResponse(listOf(existing))),
            createResult = ApiResult.Success(created),
        )
        val repo = RequestsRepository(api)
        repo.refreshMine()

        val result = repo.create(
            CreateMediaRequest(
                mediaType = RequestMediaType.Movie,
                tmdbId = 2,
                title = "Movie 2",
            ),
        )

        assertTrue(result is ApiResult.Success)
        assertEquals(listOf(existing, created), repo.mine.first())
        assertEquals(1, api.mineCalls)
    }

    @Test
    fun `cancel replaces matching request in mine`() = runTest {
        val existing = stubRequest("a", 1, status = RequestStatus.Pending)
        val other = stubRequest("b", 2)
        val cancelled = existing.copy(
            status = RequestStatus.Failed,
            outcome = RequestOutcome.Cancelled,
            updatedAt = "2026-06-02T00:00:00Z",
        )
        val api = FakeRequestsApi(
            mineResult = ApiResult.Success(RequestsListResponse(listOf(existing, other))),
            cancelResult = ApiResult.Success(cancelled),
        )
        val repo = RequestsRepository(api)
        repo.refreshMine()

        val result = repo.cancel("a")

        assertTrue(result is ApiResult.Success)
        assertEquals(listOf(cancelled, other), repo.mine.first())
    }

    @Test
    fun `reset clears mine state flow`() = runTest {
        val seeded = listOf(stubRequest("a", 1), stubRequest("b", 2))
        val api = FakeRequestsApi(mineResult = ApiResult.Success(RequestsListResponse(seeded)))
        val repo = RequestsRepository(api)

        repo.refreshMine()
        assertTrue(repo.mine.first().isNotEmpty())

        repo.reset()

        assertTrue(repo.mine.first().isEmpty())
    }

    @Test
    fun `a read that answers after reset stores nothing`() = runTest {
        val previous = stubRequest("a", 1)
        val gate = CompletableDeferred<Unit>()
        val api = FakeRequestsApi(mineResult = ApiResult.Success(RequestsListResponse(listOf(previous))), mineGate = gate)
        val repo = RequestsRepository(api)

        val read = async { repo.refreshMine() }
        runCurrent()
        // The profile switches while the previous profile's list is in flight.
        repo.reset()
        gate.complete(Unit)

        assertTrue(read.await() is ApiResult.Error)
        assertTrue(repo.mine.first().isEmpty())
        assertNull(repo.cache.ownRecord(previous.cacheKey()))
    }

    @Test
    fun `a recent complete read answers for one title until reset`() = runTest {
        val api = FakeRequestsApi(mineResult = ApiResult.Success(RequestsListResponse(listOf(stubRequest("a", 1)))))
        val repo = RequestsRepository(api)

        repo.refreshMine()
        repo.ensureMine()
        assertEquals(1, api.mineCalls)

        repo.reset()
        repo.ensureMine()
        assertEquals(2, api.mineCalls)
    }

    @Test
    fun `a list read that a create overtook keeps the created request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val created = stubRequest("created", 2)
        val api = FakeRequestsApi(
            mineResult = ApiResult.Success(RequestsListResponse(listOf(stubRequest("old", 1)))),
            createResult = ApiResult.Success(created),
            mineGate = gate,
        )
        val repo = RequestsRepository(api)

        val read = async { repo.refreshMine() }
        runCurrent()
        repo.create(CreateMediaRequest(mediaType = RequestMediaType.Movie, tmdbId = 2, title = "Movie 2"))
        gate.complete(Unit)
        read.await()

        assertEquals(listOf(created), repo.mine.first())
        // The snapshot doesn't count as fresh, so the next caller reads again.
        repo.ensureMine()
        assertEquals(2, api.mineCalls)
    }
}
