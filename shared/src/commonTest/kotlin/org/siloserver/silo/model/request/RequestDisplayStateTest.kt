package org.siloserver.silo.model.request

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.RequestDetailCache

class RequestDisplayStateTest {

    @Test
    fun serverStateDecidesBeforeStatus() {
        // A finished download the library hasn't picked up yet: `completed`
        // alone would read as in library.
        assertEquals(RequestDisplayState.OnTheWay, display(status = RequestStatus.Completed, state = RequestUserState.Processing))
        assertEquals(RequestDisplayState.OnTheWay, display(status = RequestStatus.Completed, state = RequestUserState.PartiallyAvailable))
        assertEquals(RequestDisplayState.InLibrary, display(status = RequestStatus.Completed, state = RequestUserState.Available))
        assertEquals(
            RequestDisplayState.NeedsAttention(RequestAttention.Declined, null),
            display(status = RequestStatus.Pending, state = RequestUserState.Declined),
        )
    }

    @Test
    fun unknownOrMissingStateFallsBackToStatusAndOutcome() {
        assertEquals(RequestDisplayState.InLibrary, display(status = RequestStatus.Completed))
        assertEquals(RequestDisplayState.OnTheWay, display(status = "brand_new_stage", state = "brand_new_state"))
        assertEquals(
            RequestDisplayState.NeedsAttention(RequestAttention.Failed, "No indexer"),
            display(status = RequestStatus.Downloading, outcome = RequestOutcome.Failed, lastError = "No indexer"),
        )
        assertTrue(display(status = RequestStatus.Pending, outcome = RequestOutcome.Cancelled) is RequestDisplayState.Unavailable)
    }

    @Test
    fun cardStateIgnoresTheRequestabilityReasonOnceARequestExists() {
        val annotated = RequestState(status = RequestStatus.Approved, requestable = false, reason = RequestReason.AlreadyRequested)
        assertEquals(RequestDisplayState.OnTheWay, RequestDisplayState.of(RequestAvailability.Missing, annotated))
        assertNull(RequestDisplayState.of(RequestAvailability.Missing, RequestState(requestable = true)))
        // A series in the library with its missing seasons on the way reads as on the way.
        val seasons = RequestState(status = RequestStatus.Downloading, state = RequestUserState.Approved)
        assertEquals(RequestDisplayState.OnTheWay, RequestDisplayState.of(RequestAvailability.Available, seasons))
        assertNull(RequestDisplayState.OnTheWay.libraryItemToOpen("item-1"))
        assertEquals("item-1", RequestDisplayState.InLibrary.libraryItemToOpen("item-1"))
    }

    @Test
    fun progressPlacesEachStateOnTheTrack() {
        val pending = RequestProgress.of(record(status = RequestStatus.Pending))
        assertEquals(1, pending.completedSteps)
        assertEquals(RequestStep.Approval, pending.currentStep)

        val queued = RequestProgress.of(record(status = RequestStatus.Queued))
        assertEquals(RequestStep.Download, queued.currentStep)
        assertEquals("Queued", queued.shortLabel)

        val adding = RequestProgress.of(record(status = RequestStatus.Completed, state = RequestUserState.Processing))
        assertEquals(RequestStep.Library, adding.currentStep)
        assertEquals(3, adding.completedSteps)
        assertEquals("Adding to your library", adding.longLabel)

        val landed = RequestProgress.of(record(status = RequestStatus.Completed, state = RequestUserState.Available))
        assertEquals(4, landed.completedSteps)
        assertNull(landed.currentStep)

        val failed = RequestProgress.of(record(status = RequestStatus.Downloading, outcome = RequestOutcome.Failed))
        assertEquals(RequestStep.Download, failed.currentStep)
        assertEquals(RequestStatusTint.Rose, failed.tint)
    }

    @Test
    fun bucketsPutRequestsThatNeedTheUserAboveFinishedOnes() {
        val buckets = MyRequestsBucket.bucket(
            listOf(
                record(id = "landed", status = RequestStatus.Completed, state = RequestUserState.Available),
                record(id = "old", status = RequestStatus.Pending, createdAt = "2026-09-01T00:00:00Z"),
                record(id = "new", status = RequestStatus.Approved, createdAt = "2026-09-02T00:00:00.5Z"),
                record(id = "failed", status = RequestStatus.Pending, outcome = RequestOutcome.Failed),
                record(id = "gone", status = RequestStatus.Pending, outcome = RequestOutcome.Cancelled),
            ),
        )
        assertEquals(
            listOf(MyRequestsBucket.InMotion, MyRequestsBucket.NeedsAttention, MyRequestsBucket.Landed),
            buckets.map { it.first },
        )
        assertEquals(listOf("new", "old"), buckets.first().second.map { it.id })
    }

    @Test
    fun theNewestRequestSpeaksForItsTitle() {
        val declined = record(id = "declined", outcome = RequestOutcome.Declined, createdAt = "2026-09-01T00:00:00Z")
        val cancelled = record(id = "cancelled", outcome = RequestOutcome.Cancelled, createdAt = "2026-09-02T00:00:00Z")
        // Cancelling a newer request doesn't bring back an older decline.
        assertNull(RequestDetailCache.currentRecord(listOf(declined, cancelled)))
        val active = record(id = "active", createdAt = "2026-08-01T00:00:00Z")
        assertEquals("active", RequestDetailCache.currentRecord(listOf(declined, cancelled, active))?.id)
    }

    @Test
    fun aRequestActionHoldEndsOnlyWhenTheRequestChangesLeavesOrLapses() {
        val clock = TestTimeSource()
        val request = record(id = "r1", updatedAt = "2026-09-01T00:00:00Z")
        val hold = RequestActionHold(request, clock, lifetime = 60.seconds)

        assertFalse(hold.isSettled(request))
        assertTrue(hold.isSettled(request.copy(updatedAt = "2026-09-01T00:01:00Z")))
        assertTrue(hold.isSettled(null))
        clock += 60.seconds
        assertTrue(hold.isSettled(request))
    }

    @Test
    fun aProxyGatewayErrorIsUncertainButTheServersOwnProblemIsNot() {
        assertTrue(RequestMutationFailure.isUncertain(ApiResult.Error(504, "", "")))
        assertTrue(RequestMutationFailure.isUncertain(ApiResult.Error(502, "", "")))
        assertFalse(RequestMutationFailure.isUncertain(ApiResult.Error(503, "capability_unavailable", "Requests are unavailable.")))
        assertFalse(RequestMutationFailure.isUncertain(ApiResult.Error(500, "", "")))
    }

    @Test
    fun aDeclineExplainsItselfWithTheOutcomeReasonNotTheDownloadError() {
        val declined = record(state = RequestUserState.Declined, lastError = "Radarr unreachable").copy(outcomeReason = "Already on disc")
        assertEquals(RequestDisplayState.NeedsAttention(RequestAttention.Declined, "Already on disc"), RequestDisplayState.of(declined))
        val failed = record(outcome = RequestOutcome.Failed, lastError = "Radarr unreachable")
        assertEquals(RequestDisplayState.NeedsAttention(RequestAttention.Failed, "Radarr unreachable"), RequestDisplayState.of(failed))
    }

    @Test
    fun reasonCopyHidesUnknownCodesButKeepsServerSentences() {
        assertEquals("Request limit reached", requestReasonCopy(RequestReason.QuotaExceeded))
        assertEquals("One or more fulfillment targets failed", requestReasonCopy("one or more fulfillment targets failed"))
        assertNull(requestReasonCopy("some_new_code"))
        assertEquals("Request failed · No indexer", RequestDisplayState.NeedsAttention(RequestAttention.Failed, "No indexer").detailTitle)
    }

    private fun display(
        status: String,
        outcome: String = RequestOutcome.Active,
        state: String? = null,
        lastError: String = "",
    ): RequestDisplayState = RequestDisplayState.of(record(status = status, outcome = outcome, state = state, lastError = lastError))
}

internal fun record(
    id: String = "r1",
    tmdbId: Int = 1,
    status: String = RequestStatus.Pending,
    outcome: String = RequestOutcome.Active,
    state: String? = null,
    lastError: String = "",
    createdAt: String = "2026-09-01T00:00:00Z",
    updatedAt: String = createdAt,
): MediaRequest = MediaRequest(
    id = id,
    mediaType = RequestMediaType.Movie,
    tmdbId = tmdbId,
    title = "Film",
    status = status,
    outcome = outcome,
    state = state,
    lastError = lastError,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
