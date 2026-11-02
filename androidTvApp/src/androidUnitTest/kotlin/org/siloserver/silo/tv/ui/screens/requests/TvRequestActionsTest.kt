package org.siloserver.silo.tv.ui.screens.requests

import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestOutcome
import org.siloserver.silo.model.request.RequestStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** The long-press actions a Requests page card offers. */
class TvRequestActionsTest {

    private val failed = MediaRequest(
        id = "r-1",
        mediaType = RequestMediaType.Movie,
        tmdbId = 1,
        title = "Gone",
        status = RequestStatus.Failed,
        outcome = RequestOutcome.Failed,
        createdAt = "2026-06-01T00:00:00Z",
        updatedAt = "2026-06-01T00:00:00Z",
    )

    @Test fun aRemovableRequestCardOffersRemove() {
        val actions = TvRequestItem.Record(failed).actions(canCancel = { false }, canRemove = { it.id == "r-1" })
        assertEquals(listOf(TvRequestAction.Remove), actions)
        assertEquals("Remove request", TvRequestAction.Remove.label)
    }

    @Test fun aCardThatCannotBeRemovedOrCancelledOffersNothing() {
        assertEquals(emptyList(), TvRequestItem.Record(failed).actions(canCancel = { false }, canRemove = { false }))
        val title = TvRequestItem.Result(RequestMediaResult(mediaType = RequestMediaType.Movie, tmdbId = 1, title = "Gone"))
        assertEquals(emptyList(), title.actions(canCancel = { true }, canRemove = { true }))
    }
}
