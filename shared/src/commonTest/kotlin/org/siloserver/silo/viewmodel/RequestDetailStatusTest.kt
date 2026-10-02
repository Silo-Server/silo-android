package org.siloserver.silo.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.siloserver.silo.model.request.AdminRequestAction
import org.siloserver.silo.model.request.RequestAttention
import org.siloserver.silo.model.request.RequestAvailability
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestOutcome
import org.siloserver.silo.model.request.RequestState
import org.siloserver.silo.model.request.RequestStatus
import org.siloserver.silo.model.request.RequestUserState
import org.siloserver.silo.model.request.record

/** The detail page's status decisions (Apple `RequestDetailStatusTitleTests` / `RequestsV2Tests` parity). */
class RequestDetailStatusTest {

    private val requestable = RequestMediaDetail(
        mediaType = RequestMediaType.Movie,
        tmdbId = 1,
        title = "Film",
        availability = RequestAvailability.Missing,
        request = RequestState(requestable = true),
    )

    @Test
    fun aFinishedDownloadTheTitleReadCallsRequestableStaysOnItsWay() {
        // The server's per-title state skips a completed request that hasn't
        // reached the library; the user's own record still knows.
        val state = RequestDetailUiState(
            detail = requestable,
            record = record(status = RequestStatus.Completed, state = RequestUserState.Processing),
        )
        assertEquals(RequestPrimaryAction.Status(RequestDisplayState.OnTheWay), state.primaryAction)
        assertEquals("Adding to your library", state.primaryActionTitle)
    }

    @Test
    fun aFailedRequestBecomesThePageStatusAndTheRequesterCanAskAgain() {
        val failed = record(status = RequestStatus.Downloading, outcome = RequestOutcome.Failed)
        val requester = RequestDetailUiState(detail = requestable, record = failed)
        assertEquals(RequestPrimaryAction.Request, requester.primaryAction)
        assertEquals("Request Again", requester.primaryActionTitle)
        assertEquals(RequestDisplayState.NeedsAttention(RequestAttention.Failed, null), requester.progress?.display)

        // An admin who can retry it gets Retry as the only action.
        val admin = RequestDetailUiState(detail = requestable, moderationRecord = failed)
        assertEquals(RequestPrimaryAction.Status(RequestDisplayState.NeedsAttention(RequestAttention.Failed, null)), admin.primaryAction)
        assertEquals(listOf(AdminRequestAction.Retry), admin.moderationActions)
    }

    @Test
    fun anOldFailureStopsCountingOnceAnotherRequestIsUnderWay() {
        val detail = requestable.copy(
            request = RequestState(status = RequestStatus.Approved, requestable = false, requestId = "someone-else"),
        )
        val state = RequestDetailUiState(detail = detail, record = record(id = "mine", outcome = RequestOutcome.Failed))
        assertNull(state.endedRequest)
        assertEquals(RequestPrimaryAction.Status(RequestDisplayState.OnTheWay), state.primaryAction)
    }

    @Test
    fun pendingRequestsOfferCancelToTheRequesterAndDecisionsToTheAdmin() {
        val pending = record(status = RequestStatus.Pending)
        val detail = requestable.copy(request = RequestState(status = RequestStatus.Pending, requestId = pending.id))
        assertEquals(true, RequestDetailUiState(detail = detail, record = pending).canCancel)
        val moderating = RequestDetailUiState(detail = detail, moderationRecord = pending, openedForModeration = true)
        assertEquals(false, moderating.canCancel)
        assertEquals(listOf(AdminRequestAction.Approve, AdminRequestAction.Decline), moderating.moderationActions)
        assertEquals("Requested by someone on this server", moderating.eyebrow)
    }

    @Test
    fun aRequestableFailedAnnotationStillOffersRequestToTheRequester() {
        // A card patched from the user's failed record carries its state but
        // stays requestable; the page must not block on it.
        val detail = requestable.copy(
            request = RequestState(status = RequestStatus.Downloading, state = RequestUserState.Failed, requestable = true),
        )
        assertEquals(RequestPrimaryAction.Request, RequestDetailUiState(detail = detail).primaryAction)
    }
}
