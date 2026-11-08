package org.siloserver.silo.model.feature

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import org.siloserver.silo.model.request.RequestDiscoverySection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.RequestsApi
import org.siloserver.silo.repository.RequestDetailCache
import org.siloserver.silo.repository.RequestsRepository

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun removeFailedFollowsTheProbeAndStaysOffWhenRequestsAre() = runTest {
        val store = RequestsFeatureStore(
            RequestsRepository(
                FakeRequestsApi(
                    ApiResult.Success(available().copy(removeFailedSupported = true)),
                    ApiResult.NetworkError(IllegalStateException("offline")),
                    ApiResult.Success(available(allowed = false).copy(removeFailedSupported = true)),
                ),
            ),
        )
        assertFalse(store.removeFailedSupported.value)

        store.refresh()
        assertTrue(store.removeFailedSupported.value)

        // A failed probe keeps the previous answer.
        store.refresh()
        assertTrue(store.removeFailedSupported.value)

        store.refresh()
        assertFalse(store.removeFailedSupported.value)

        store.reset()
        assertFalse(store.removeFailedSupported.value)
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
        // An answer, so a Requests screen that is open knows to close.
        assertTrue(store.isKnownUnavailable.value)
    }

    @Test
    fun onlyAnAnsweredUnavailableProbeCountsAsOff() = runTest {
        val store = RequestsFeatureStore(
            RequestsRepository(
                FakeRequestsApi(
                    ApiResult.NetworkError(IllegalStateException("offline")),
                    ApiResult.Success(available()),
                    ApiResult.Success(disabled()),
                    ApiResult.Error(503, "unavailable", "Down for maintenance"),
                ),
            ),
        )
        assertFalse(store.isKnownUnavailable.value, "unknown before the first answer")

        store.refresh()
        assertTrue(store.isResolved.value)
        assertFalse(store.isKnownUnavailable.value, "a failed first probe is still unknown")

        store.refresh()
        assertFalse(store.isKnownUnavailable.value)

        store.refresh()
        assertTrue(store.isKnownUnavailable.value)

        store.refresh()
        assertTrue(store.isKnownUnavailable.value, "a failed probe keeps the previous answer")

        store.reset()
        assertFalse(store.isKnownUnavailable.value, "a reset (profile or server switch) is unknown again")
    }

    @Test
    fun anOlderProbeCannotOverwriteANewerAnswer() = runTest {
        val api = FakeRequestsApi()
        val older = api.holdNextStatus()
        val newer = api.holdNextStatus()
        val store = RequestsFeatureStore(RequestsRepository(api))
        launch { store.refresh() }
        launch { store.refresh() }
        runCurrent()

        newer.complete(ApiResult.Success(disabled()))
        runCurrent()
        older.complete(ApiResult.Success(available()))
        advanceUntilIdle()

        assertFalse(store.isEnabled.value)
        assertTrue(store.isKnownUnavailable.value, "the newer disabled answer stands")
    }

    @Test
    fun aNewerProbeThatFailsKeepsAnOlderAnswer() = runTest {
        val api = FakeRequestsApi()
        val older = api.holdNextStatus()
        val newer = api.holdNextStatus()
        val store = RequestsFeatureStore(RequestsRepository(api))
        launch { store.refresh() }
        launch { store.refresh() }
        runCurrent()

        newer.complete(ApiResult.NetworkError(IllegalStateException("offline")))
        runCurrent()
        older.complete(ApiResult.Success(disabled()))
        advanceUntilIdle()

        assertTrue(store.isKnownUnavailable.value, "the older answer is the newest one there is")
    }

    @Test
    fun aProbeFromBeforeAResetIsDiscarded() = runTest {
        val api = FakeRequestsApi()
        val pending = api.holdNextStatus()
        val store = RequestsFeatureStore(RequestsRepository(api))
        launch { store.refresh() }
        runCurrent()

        store.reset()
        pending.complete(ApiResult.Success(disabled()))
        advanceUntilIdle()

        assertFalse(store.isResolved.value)
        assertFalse(store.isKnownUnavailable.value)
    }

    @Test
    fun aRefusedRequestsCallProbesAgain() = runTest {
        val api = FakeRequestsApi(ApiResult.Success(available()), ApiResult.Success(disabled()))
        val repository = RequestsRepository(api)
        val store = RequestsFeatureStore(repository, scope = backgroundScope)
        runCurrent()
        store.refresh()
        assertEquals(1, api.statusCalls)

        api.searchResult = ApiResult.Error(409, "conflict", "Already requested")
        repository.search("dune")
        // The store listens on backgroundScope, which advanceUntilIdle() doesn't run.
        runCurrent()
        assertEquals(1, api.statusCalls, "a refusal for another reason doesn't probe")

        api.searchResult = ApiResult.Error(409, "capability_disabled", "The requests capability is disabled by an administrator.")
        val result = repository.search("dune")
        runCurrent()

        assertEquals("capability_disabled", (result as ApiResult.Error).error, "the caller still gets the refusal")
        assertEquals(2, api.statusCalls)
        assertTrue(store.isKnownUnavailable.value)
    }

    @Test
    fun aRefusalWhileTheFirstProbeIsOutStillProbes() = runTest {
        // The shell's probe read the status before requests were turned off.
        val api = FakeRequestsApi()
        val stale = api.holdNextStatus()
        val fresh = api.holdNextStatus()
        val repository = RequestsRepository(api)
        val store = RequestsFeatureStore(repository, scope = backgroundScope)
        runCurrent()
        launch { store.refresh() }
        runCurrent()

        api.searchResult = ApiResult.Error(409, "capability_disabled", "The requests capability is disabled by an administrator.")
        repository.search("dune")
        // The store listens on backgroundScope, which advanceUntilIdle() doesn't run.
        runCurrent()
        fresh.complete(ApiResult.Success(disabled()))
        runCurrent()
        stale.complete(ApiResult.Success(available()))
        runCurrent()

        assertEquals(2, api.statusCalls)
        assertTrue(store.isKnownUnavailable.value)
    }

    @Test
    fun onlyAServerRefusalForRequestsBeingOffCounts() {
        val refused = listOf("capability_disabled", "capability_not_configured", "capability_unsupported", "permission_denied")
        for (code in refused) {
            assertTrue(RequestsRepository.isRefusedAsRequestsOff(ApiResult.Error(409, code, "")), code)
        }
        // Another refusal, a client-side stand-in (status 0), and no answer at all.
        assertFalse(RequestsRepository.isRefusedAsRequestsOff(ApiResult.Error(409, "conflict", "")))
        assertFalse(RequestsRepository.isRefusedAsRequestsOff(ApiResult.Error(0, "capability_unsupported", "")))
        assertFalse(RequestsRepository.isRefusedAsRequestsOff(ApiResult.NetworkError(IllegalStateException("offline"))))
        assertFalse(RequestsRepository.isRefusedAsRequestsOff(ApiResult.Success(Unit)))
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

private fun disabled() = RequestsFeatureStatus(requestsEnabled = false, allowed = false, state = "disabled")

private class FakeRequestsApi(
    vararg statusResults: ApiResult<RequestsFeatureStatus>,
) : RequestsApi {
    private val statusResults = statusResults.toMutableList()
    private val heldStatuses = mutableListOf<CompletableDeferred<ApiResult<RequestsFeatureStatus>>>()
    var statusCalls = 0
        private set
    var searchResult: ApiResult<RequestMediaPage> = ApiResult.NetworkError(IllegalStateException("not used"))

    /** The next status call waits for the returned answer, so a test decides the order probes answer in. */
    fun holdNextStatus(): CompletableDeferred<ApiResult<RequestsFeatureStatus>> =
        CompletableDeferred<ApiResult<RequestsFeatureStatus>>().also { heldStatuses += it }

    override suspend fun status(): ApiResult<RequestsFeatureStatus> {
        statusCalls += 1
        if (heldStatuses.isNotEmpty()) return heldStatuses.removeAt(0).await()
        return if (statusResults.isNotEmpty()) {
            statusResults.removeAt(0)
        } else {
            ApiResult.NetworkError(IllegalStateException("no fake status result"))
        }
    }

    override suspend fun discover(): ApiResult<RequestsDiscoverResponse> =
        ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun discoverRow(rowKey: String, page: Int): ApiResult<RequestDiscoverySection> =
        ApiResult.NetworkError(IllegalStateException("not used"))

    override suspend fun search(
        query: String,
        mediaType: String?,
        page: Int,
    ): ApiResult<RequestMediaPage> = searchResult

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
