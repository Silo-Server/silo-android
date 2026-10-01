package org.siloserver.silo.model.feature

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.model.request.AdminRequestAction
import org.siloserver.silo.model.request.AdminRequestCapabilities
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
    fun moderationFollowsTheAdminCapabilityProbe() = runTest {
        val admin = RequestsFeatureStore(
            RequestsRepository(
                FakeRequestsApi(
                    ApiResult.Success(available()),
                    capabilities = ApiResult.Success(AdminRequestCapabilities(available = true)),
                ),
            ),
        )
        val member = RequestsFeatureStore(RequestsRepository(FakeRequestsApi(ApiResult.Success(available()))))

        admin.refresh()
        member.refresh()

        assertTrue(admin.canModerate.value)
        assertFalse(member.canModerate.value)
        admin.reset()
        assertFalse(admin.canModerate.value)
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
    private val capabilities: ApiResult<AdminRequestCapabilities> = ApiResult.Error(403, "forbidden", ""),
) : RequestsApi {
    private val statusResults = statusResults.toMutableList()

    override suspend fun status(): ApiResult<RequestsFeatureStatus> =
        if (statusResults.isNotEmpty()) {
            statusResults.removeAt(0)
        } else {
            ApiResult.NetworkError(IllegalStateException("no fake status result"))
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

    override suspend fun adminCapabilities(): ApiResult<AdminRequestCapabilities> = capabilities

    override suspend fun adminRequests(
        status: String?,
        outcome: String?,
        mediaType: String?,
        tmdbId: Int?,
    ): ApiResult<RequestsListResponse> = ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun adminAction(id: String, action: AdminRequestAction, reason: String?): ApiResult<MediaRequest> =
        ApiResult.NetworkError(IllegalStateException("not used"))
}
