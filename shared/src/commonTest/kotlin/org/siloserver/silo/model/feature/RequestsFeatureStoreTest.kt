package org.siloserver.silo.model.feature

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaPage
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestsDiscoverResponse
import org.siloserver.silo.model.request.RequestsFeatureStatus
import org.siloserver.silo.model.request.RequestsListResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.RequestsApi
import org.siloserver.silo.repository.RequestDetailCache
import org.siloserver.silo.repository.RequestsRepository

class RequestsFeatureStoreTest {

    @Test
    fun aBlockedProfileKeepsRequestsHidden() = runTest {
        val store = RequestsFeatureStore(RequestsRepository(FakeRequestsApi(ApiResult.Success(available(allowed = false)))))

        store.refresh()

        assertFalse(store.isEnabled.value)
    }

    @Test
    fun startsHiddenAndFollowsSuccessfulStatusProbe() = runTest {
        val store = RequestsFeatureStore(
            RequestsRepository(
                FakeRequestsApi(
                    ApiResult.Success(available()),
                ),
            ),
        )

        assertFalse(store.isEnabled.value)

        store.refresh()

        assertTrue(store.isEnabled.value)
    }

    @Test
    fun transientFailureKeepsPreviousCapabilityValue() = runTest {
        val api = FakeRequestsApi(
            ApiResult.Success(available()),
            ApiResult.NetworkError(IllegalStateException("offline")),
        )
        val store = RequestsFeatureStore(RequestsRepository(api))

        store.refresh()
        store.refresh()

        assertTrue(store.isEnabled.value)
    }

    @Test
    fun returningToTheForegroundSeesRequestsTurnedOff() = runTest {
        val store = RequestsFeatureStore(
            RequestsRepository(
                FakeRequestsApi(
                    ApiResult.Success(available()),
                    ApiResult.Success(RequestsFeatureStatus(requestsEnabled = false, allowed = true, state = "disabled")),
                ),
            ),
        )
        store.refresh()
        assertTrue(store.isEnabled.value)

        store.refreshAfterReturnToForeground()

        assertFalse(store.isEnabled.value)
        // Resolved, so a Requests screen that is open knows to close.
        assertTrue(store.isResolved.value)
    }

    @Test
    fun returningToTheForegroundLeavesAnUnansweredSessionToTheShellProbe() = runTest {
        val api = FakeRequestsApi(ApiResult.Success(available()), ApiResult.Success(available()))
        val store = RequestsFeatureStore(RequestsRepository(api))

        // Before the shell's first probe (signed out, or still starting).
        store.refreshAfterReturnToForeground()
        assertFalse(store.isResolved.value)
        assertEquals(0, api.statusCalls)

        // After a server or profile switch reset.
        store.refresh()
        store.reset()
        store.refreshAfterReturnToForeground()
        assertFalse(store.isResolved.value)
        assertEquals(1, api.statusCalls)
    }

    @Test
    fun resetHidesRequestsBeforeNextProbe() = runTest {
        val repository = RequestsRepository(
            FakeRequestsApi(
                ApiResult.Success(available()),
            ),
        )
        val store = RequestsFeatureStore(repository)
        val seen = RequestMediaResult(mediaType = RequestMediaType.Movie, tmdbId = 7, title = "Seen")
        repository.cache.seed(seen)

        store.refresh()
        store.reset()

        assertFalse(store.isEnabled.value)
        // Sign-out and server or profile switches all reset here, so the
        // previous session's request pages go with it.
        assertNull(repository.cache.firstFrameDetail(RequestDetailCache.Key(RequestMediaType.Movie, 7)))
    }
}

private fun available(allowed: Boolean = true) =
    RequestsFeatureStatus(requestsEnabled = true, allowed = allowed, state = "available")

private class FakeRequestsApi(
    vararg statusResults: ApiResult<RequestsFeatureStatus>,
) : RequestsApi {
    private val statusResults = statusResults.toMutableList()
    var statusCalls = 0
        private set

    override suspend fun status(): ApiResult<RequestsFeatureStatus> {
        statusCalls += 1
        return if (statusResults.isNotEmpty()) {
            statusResults.removeAt(0)
        } else {
            ApiResult.NetworkError(IllegalStateException("no fake status result"))
        }
    }

    override suspend fun discover(): ApiResult<RequestsDiscoverResponse> =
        ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun discoverSection(section: String, page: Int): ApiResult<RequestMediaPage> =
        ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun search(
        query: String,
        mediaType: String?,
        page: Int,
    ): ApiResult<RequestMediaPage> = ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun detail(mediaType: String, tmdbId: Int): ApiResult<RequestMediaDetail> =
        ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun create(request: CreateMediaRequest): ApiResult<MediaRequest> =
        ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun mine(
        status: String?,
        outcome: String?,
        limit: Int?,
        offset: Int?,
    ): ApiResult<RequestsListResponse> = ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun get(id: String): ApiResult<MediaRequest> =
        ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun cancel(id: String): ApiResult<MediaRequest> =
        ApiResult.NetworkError(IllegalStateException("not used"))
}
