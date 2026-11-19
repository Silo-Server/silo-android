package org.siloserver.silo.model.request

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The server's rule for `removeFailedRequest`: failed, and no target delivered a copy. */
class RequestRemovalRuleTest {

    @Test fun aFailedRequestThatDeliveredNothingIsRemovable() {
        assertTrue(request(RequestOutcome.Failed, targets = listOf(target(RequestStatus.Failed))).isRemovableFailure)
        assertTrue(request(RequestOutcome.Failed).isRemovableFailure)
    }

    @Test fun aFailedRequestThatDeliveredOneQualityKeepsItsRecord() {
        val partly = request(RequestOutcome.Failed, targets = listOf(target(RequestStatus.Completed), target(RequestStatus.Failed)))
        assertFalse(partly.isRemovableFailure)
    }

    @Test fun onlyFailedRequestsAreRemovable() {
        listOf(RequestOutcome.Active, RequestOutcome.Declined, RequestOutcome.Cancelled).forEach {
            assertFalse(request(it).isRemovableFailure, it)
        }
    }

    private fun request(outcome: String, targets: List<RequestTarget> = emptyList()) = MediaRequest(
        id = "r-1",
        mediaType = RequestMediaType.Movie,
        tmdbId = 1,
        title = "Gone",
        status = RequestStatus.Failed,
        outcome = outcome,
        targets = targets,
        createdAt = "2026-06-01T00:00:00Z",
        updatedAt = "2026-06-01T00:00:00Z",
    )

    private fun target(status: String) = RequestTarget(
        id = "t-$status",
        requestId = "r-1",
        status = status,
        createdAt = "2026-06-01T00:00:00Z",
        updatedAt = "2026-06-01T00:00:00Z",
    )
}
